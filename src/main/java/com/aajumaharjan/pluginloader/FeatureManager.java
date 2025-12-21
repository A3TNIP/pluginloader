package com.aajumaharjan.pluginloader;

import com.aajumaharjan.pluginloader.build.MavenBuilder;
import com.aajumaharjan.pluginloader.fetch.GitFeatureFetcher;
import com.aajumaharjan.pluginloader.load.JarClassLoader;
import com.aajumaharjan.pluginloader.model.FeatureConfig;
import com.aajumaharjan.pluginloader.config.PluginLoaderProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.beans.Introspector;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

@Component
@Slf4j
public class FeatureManager implements ApplicationListener<ContextClosedEvent> {
    private final GitFeatureFetcher featureFetcher;
    private final PluginLoaderProperties pluginLoaderProperties;
    private final MavenBuilder mavenBuilder;
    private final JarClassLoader jarClassLoader;

    private final List<GenericApplicationContext> featureContexts = new CopyOnWriteArrayList<>();
    private final List<URLClassLoader> featureLoaders = new CopyOnWriteArrayList<>();

    public FeatureManager(GitFeatureFetcher featureFetcher,
                          PluginLoaderProperties pluginLoaderProperties,
                          MavenBuilder mavenBuilder,
                          JarClassLoader jarClassLoader) {
        this.featureFetcher = featureFetcher;
        this.pluginLoaderProperties = pluginLoaderProperties;
        this.mavenBuilder = mavenBuilder;
        this.jarClassLoader = jarClassLoader;
    }

    public void initialize(ConfigurableApplicationContext parentContext) {
        if (pluginLoaderProperties.getFeatures() == null || pluginLoaderProperties.getFeatures().isEmpty()) {
            return;
        }

        for (FeatureConfig feature : pluginLoaderProperties.getFeatures()) {
            try {
                log.info("Processing feature {}", feature.getRepository());

                // 1) Try to find a generated descriptor on the client's classpath
                Optional<DescriptorInfo> desc = loadGeneratedDescriptorIfPresent(feature);
                if (desc.isPresent()) {
                    DescriptorInfo di = desc.get();
                    log.info("Found generated descriptor for feature: {} -> jarPath={}", feature.getRepository(), di.jarPath);
                    // if jarPath is present, create classloader for that jar and register beans
                    if (di.jarPath != null && !di.jarPath.isBlank()) {
                        File jarFile = resolveJarPath(di.jarPath);
                        URLClassLoader featureLoader = jarClassLoader.createClassLoaderForJar(jarFile);
                        GenericApplicationContext featureContext = createFeatureContext(parentContext, featureLoader, di.packages, di.beanClasses);
                        featureContexts.add(featureContext);
                        featureLoaders.add(featureLoader);
                        exposeBeansToParent(parentContext, featureContext, di.beanClasses);
                        log.info("Integrated feature from descriptor: {}", feature.getRepository());
                        continue;
                    }
                    // if no jarPath but bean class names exist and those classes are on classpath already,
                    // register them into a child context using the parent classloader and expose to parent
                    if (!di.beanClasses.isEmpty()) {
                        GenericApplicationContext featureContext = createFeatureContext(parentContext, parentContext.getClassLoader(), di.packages, di.beanClasses);
                        featureContexts.add(featureContext);
                        // expose beans so parent can autowire by type/name
                        exposeBeansToParent(parentContext, featureContext, di.beanClasses);
                        log.info("Integrated feature by registering bean names (no jar) {}", feature.getRepository());
                        continue;
                    }
                }

                // 2) Fallback: fetch & build at runtime
                log.info("No descriptor found; falling back to fetch/build for {}", feature.getRepository());
                File repoDir = featureFetcher.fetchRepository(feature);

                // Try to copy compiled classes into client's target/classes (makes impl classes visible to parent classloader).
                boolean copied = copyCompiledClassesToClient(repoDir.toPath(), feature.getPackages());
                if (copied) {
                    // Only packages are provided in YAML; use package scanning (no feature.getBeanClasses())
                    List<String> packages = feature.getPackages() == null ? Collections.emptyList() : feature.getPackages();
                    GenericApplicationContext featureContext = createFeatureContext(parentContext, parentContext.getClassLoader(), packages, Collections.emptyList());
                    featureContexts.add(featureContext);
                    log.info("Feature {} integrated by copying compiled classes into client classpath and scanning packages", feature.getRepository());
                    continue;
                }

                // If classes could not be copied, build artifact and load from jar
                File artifactJar = mavenBuilder.build(repoDir);
                URLClassLoader featureLoader = jarClassLoader.createClassLoaderForJar(artifactJar);
                GenericApplicationContext featureContext = createFeatureContext(parentContext, featureLoader, feature.getPackages(), Collections.emptyList());
                featureContexts.add(featureContext);
                featureLoaders.add(featureLoader);
                log.info("Feature {} integrated (fetched & built at runtime)", feature.getRepository());

            } catch (Exception e) {
                log.error("Failed to integrate feature {}: {}", feature.getRepository(), e.getMessage(), e);
            }
        }
    }

    // Try to load a generated descriptor class (convention: generated.<FeatureName>Descriptor)
    private Optional<DescriptorInfo> loadGeneratedDescriptorIfPresent(FeatureConfig feature) {
        List<String> candidates = new ArrayList<>();
        String repo = feature.getRepository();
        if (repo != null) {
            String name = repo.substring(Math.max(repo.lastIndexOf('/'), repo.lastIndexOf('\\')) + 1);
            if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
            name = sanitizeIdentifier(name);
            candidates.add("generated." + capitalize(name) + "Descriptor");
        }
        if (feature.getPackages() != null && !feature.getPackages().isEmpty()) {
            for (String p : feature.getPackages()) {
                String last = p.substring(p.lastIndexOf('.') + 1);
                candidates.add("generated." + capitalize(sanitizeIdentifier(last)) + "Descriptor");
            }
        }

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String c : candidates) {
            try {
                Class<?> descriptor = Class.forName(c, true, cl);
                DescriptorInfo di = new DescriptorInfo();
                di.packages = readStringArrayField(descriptor, "PACKAGES");
                di.beanClasses = readStringArrayField(descriptor, "BEAN_CLASSES");
                di.jarPath = readStringField(descriptor, "JAR_PATH").orElse(null);
                return Optional.of(di);
            } catch (ClassNotFoundException ignored) {
                // continue searching
            } catch (Exception e) {
                log.warn("Failed reading descriptor {}: {}", c, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private File resolveJarPath(String jarPath) {
        Path p = Path.of(jarPath);
        if (p.isAbsolute() && p.toFile().exists()) return p.toFile();
        Path cwd = Path.of(System.getProperty("user.dir")).resolve(jarPath);
        if (cwd.toFile().exists()) return cwd.toFile();
        URL res = Thread.currentThread().getContextClassLoader().getResource(jarPath);
        if (res != null) {
            try {
                return Path.of(res.toURI()).toFile();
            } catch (Exception ignored) {}
        }
        throw new RuntimeException("Cannot resolve jar path: " + jarPath);
    }

    // Try to copy compiled classes from repo/target/classes -> client/target/classes, scoped to requested packages when provided
    private boolean copyCompiledClassesToClient(Path repoDir, List<String> requestedPackages) {
        try {
            Path repoClasses = repoDir.resolve("target").resolve("classes");
            if (!Files.exists(repoClasses)) {
                log.debug("No compiled classes at {}; skipping copy", repoClasses);
                return false;
            }

            Path clientClasses = Path.of(System.getProperty("user.dir")).resolve("target").resolve("classes");
            Files.createDirectories(clientClasses);
            Set<String> packageFilters = normalizePackages(requestedPackages);

            try (Stream<Path> paths = Files.walk(repoClasses)) {
                paths.filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> shouldCopyClass(repoClasses, p, packageFilters))
                        .forEach(source -> {
                            try {
                                Path relative = repoClasses.relativize(source);
                                Path target = clientClasses.resolve(relative);
                                Files.createDirectories(target.getParent());
                                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                                log.debug("Copied class {}", relative);
                            } catch (IOException e) {
                                log.warn("Failed copying class {}: {}", source, e.getMessage());
                            }
                        });
            }
            return true;
        } catch (Exception e) {
            log.warn("copyCompiledClassesToClient failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean shouldCopyClass(Path classesRoot, Path classFile, Set<String> packageFilters) {
        if (packageFilters.isEmpty()) {
            return true;
        }
        Path relative = classesRoot.relativize(classFile);
        Path parent = relative.getParent();
        String pkg = parent == null ? "" : parent.toString().replace(File.separatorChar, '.');
        for (String allowed : packageFilters) {
            if (pkg.equals(allowed) || pkg.startsWith(allowed + ".")) {
                return true;
            }
        }
        return false;
    }

    private Set<String> normalizePackages(List<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new HashSet<>();
        for (String pkg : packages) {
            if (pkg != null && !pkg.isBlank()) {
                normalized.add(pkg.trim());
            }
        }
        return normalized;
    }

    // Expose plugin beans (interfaces visible to parent) as proxies in parent context
    // Modified: also register concrete instances in parent when the class is visible
    private void exposeBeansToParent(ConfigurableApplicationContext parent, GenericApplicationContext child, List<String> beanClassNames) {
        if (beanClassNames == null || beanClassNames.isEmpty()) return;
        ClassLoader parentCl = parent.getClassLoader();

        var beanFactory = (org.springframework.beans.factory.support.DefaultListableBeanFactory) parent.getBeanFactory();

        for (String fqcn : beanClassNames) {
            try {
                // Try to load the class/interface with the parent classloader
                Class<?> cls = Class.forName(fqcn, true, parentCl);

                // Obtain implementation from child context
                Object childBean = null;
                try {
                    childBean = child.getBean(cls);
                } catch (Exception e) {
                    try { childBean = child.getBean(fqcn); } catch (Exception ignored) {}
                }
                if (childBean == null) continue;

                // pick bean name (avoid collisions)
                String beanName = Introspector.decapitalize(cls.getSimpleName());
                if (beanFactory.containsBeanDefinition(beanName) || beanFactory.containsSingleton(beanName)) {
                    beanName = beanName + "-" + UUID.randomUUID();
                }

                if (cls.isInterface()) {
                    Object finalChildBean = childBean;
                    Object proxy = Proxy.newProxyInstance(parentCl, new Class[]{cls}, (proxyObj, method, args) -> {
                        try {
                            Method implMethod = finalChildBean.getClass().getMethod(method.getName(), method.getParameterTypes());
                            implMethod.setAccessible(true);
                            return implMethod.invoke(finalChildBean, args);
                        } catch (NoSuchMethodException ns) {
                            return method.invoke(finalChildBean, args);
                        }
                    });

                    var def = new org.springframework.beans.factory.support.RootBeanDefinition(cls);
                    def.setInstanceSupplier(() -> proxy);
                    beanFactory.registerBeanDefinition(beanName, def);
                    log.info("Registered interface-proxy bean definition {} -> {}", fqcn, beanName);

                } else {
                    // Concrete class: register a bean definition with an instance supplier returning the child instance
                    var def = new org.springframework.beans.factory.support.RootBeanDefinition(cls);
                    Object finalChildBean1 = childBean;
                    def.setInstanceSupplier(() -> finalChildBean1);
                    beanFactory.registerBeanDefinition(beanName, def);
                    log.info("Registered concrete bean definition {} -> {}", fqcn, beanName);
                }

            } catch (ClassNotFoundException cnf) {
                // interface/class not visible to parent classloader -> cannot create a bean definition; skip
                log.debug("Class {} not visible to parent classloader, skipping exposure", fqcn);
            } catch (Exception e) {
                log.warn("Failed to expose bean {}: {}", fqcn, e.getMessage());
            }
        }
    }
    private GenericApplicationContext createFeatureContext(ConfigurableApplicationContext parentContext, ClassLoader featureLoader, List<String> packagesToScan, List<String> beanClassNames) {
        GenericApplicationContext child = new GenericApplicationContext();
        child.setParent(parentContext);
        child.setClassLoader(featureLoader);

        AnnotationConfigUtils.registerAnnotationConfigProcessors(child.getDefaultListableBeanFactory());

        boolean scannedPackages = packagesToScan != null && !packagesToScan.isEmpty();
        if (scannedPackages) {
            ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(child);
            scanner.setResourceLoader(new DefaultResourceLoader(featureLoader));
            scanner.scan(packagesToScan.toArray(new String[0]));
        }

        // register explicit bean classes by name (if any)
        if (beanClassNames != null && !beanClassNames.isEmpty() && !scannedPackages) {
            for (String fqcn : beanClassNames) {
                try {
                    Class<?> beanClass = Class.forName(fqcn, true, featureLoader);
                    child.registerBean(beanClass);
                } catch (ClassNotFoundException e) {
                    log.warn("Bean class {} not found on feature loader: {}", fqcn, e.getMessage());
                }
            }
        }

        child.refresh();
        return child;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        for (GenericApplicationContext ctx : featureContexts) {
            try { ctx.close(); } catch (Exception e) { log.warn("Failed to close feature context: {}", e.getMessage()); }
        }
        featureContexts.clear();

        for (URLClassLoader loader : featureLoaders) {
            try { loader.close(); } catch (IOException e) { log.warn("Failed to close feature classloader: {}", e.getMessage()); }
        }
        featureLoaders.clear();
    }

    // helpers
    private static class DescriptorInfo {
        String jarPath;
        List<String> packages = Collections.emptyList();
        List<String> beanClasses = Collections.emptyList();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String sanitizeIdentifier(String s) {
        return s.replaceAll("[^A-Za-z0-9_]", "");
    }

    private static Optional<String> readStringField(Class<?> cls, String name) {
        try {
            Field f = cls.getField(name);
            Object v = f.get(null);
            return Optional.ofNullable(v != null ? v.toString() : null);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<String> readStringArrayField(Class<?> cls, String name) {
        try {
            Field f = cls.getField(name);
            Object v = f.get(null);
            if (v instanceof String[] arr) return Arrays.asList(arr);
            return Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}

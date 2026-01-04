package com.aajumaharjan.pluginloader;

import com.aajumaharjan.pluginloader.model.FeatureConfig;
import com.aajumaharjan.pluginloader.config.PluginLoaderProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Slf4j
public class FeatureManager implements ApplicationListener<ContextClosedEvent> {
    private final PluginLoaderProperties pluginLoaderProperties;

    private final List<GenericApplicationContext> featureContexts = new CopyOnWriteArrayList<>();

    public FeatureManager(PluginLoaderProperties pluginLoaderProperties) {
        this.pluginLoaderProperties = pluginLoaderProperties;
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
                DescriptorInfo descriptorInfo = desc.orElseGet(() -> {
                    DescriptorInfo fallback = new DescriptorInfo();
                    fallback.packages = feature.getPackages() == null ? Collections.emptyList() : feature.getPackages();
                    fallback.beanClasses = Collections.emptyList();
                    return fallback;
                });

                if ((descriptorInfo.packages == null || descriptorInfo.packages.isEmpty())
                        && (descriptorInfo.beanClasses == null || descriptorInfo.beanClasses.isEmpty())) {
                    log.info("No packages or bean classes to register for feature {}", feature.getRepository());
                    continue;
                }

                ClassLoader loader = parentContext.getClassLoader();
                GenericApplicationContext featureContext = createFeatureContext(parentContext, loader, descriptorInfo.packages, descriptorInfo.beanClasses);
                featureContexts.add(featureContext);
                exposeBeansToParent(parentContext, featureContext, descriptorInfo.beanClasses);
                log.info("Integrated feature {}", feature.getRepository());

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
                return Optional.of(di);
            } catch (ClassNotFoundException ignored) {
                // continue searching
            } catch (Exception e) {
                log.warn("Failed reading descriptor {}: {}", c, e.getMessage());
            }
        }
        return Optional.empty();
    }

    // Expose plugin beans (interfaces visible to parent) as proxies in parent context
    // Modified: also register concrete instances in parent when the class is visible
    private void exposeBeansToParent(ConfigurableApplicationContext parent, GenericApplicationContext child, List<String> beanClassNames) {
        if (beanClassNames == null || beanClassNames.isEmpty()) return;
        ClassLoader parentCl = parent.getClassLoader();

        var beanFactory = (DefaultListableBeanFactory) parent.getBeanFactory();

        for (String fullyQualifiedClassName : beanClassNames) {
            try {
                // Try to load the class/interface with the parent classloader
                Class<?> clazz = Class.forName(fullyQualifiedClassName, true, parentCl);

                // Obtain implementation from child context
                Object childBean = null;
                try {
                    childBean = child.getBean(clazz);
                } catch (Exception e) {
                    try { childBean = child.getBean(fullyQualifiedClassName); } catch (Exception ignored) {}
                }
                if (childBean == null) continue;

                // pick bean name (avoid collisions)
                String beanName = Introspector.decapitalize(clazz.getSimpleName());
                if (beanFactory.containsBeanDefinition(beanName) || beanFactory.containsSingleton(beanName)) {
                    beanName = beanName + "-" + UUID.randomUUID();
                }

                if (clazz.isInterface()) {
                    Object finalChildBean = childBean;
                    Object proxy = Proxy.newProxyInstance(parentCl, new Class[]{clazz}, (proxyObj, method, args) -> {
                        try {
                            Method implMethod = finalChildBean.getClass().getMethod(method.getName(), method.getParameterTypes());
                            implMethod.setAccessible(true);
                            return implMethod.invoke(finalChildBean, args);
                        } catch (NoSuchMethodException ns) {
                            return method.invoke(finalChildBean, args);
                        }
                    });

                    var def = new RootBeanDefinition(clazz);
                    def.setInstanceSupplier(() -> proxy);
                    beanFactory.registerBeanDefinition(beanName, def);
                    log.info("Registered interface-proxy bean definition {} -> {}", fullyQualifiedClassName, beanName);

                } else {
                    // Concrete class: register a bean definition with an instance supplier returning the child instance
                    var beanDefinition = new RootBeanDefinition(clazz);
                    Object finalChildBean = childBean;
                    beanDefinition.setInstanceSupplier(() -> finalChildBean);
                    beanFactory.registerBeanDefinition(beanName, beanDefinition);
                    log.info("Registered concrete bean definition {} -> {}", fullyQualifiedClassName, beanName);
                }

            } catch (ClassNotFoundException cnf) {
                // interface/class not visible to parent classloader -> cannot create a bean definition; skip
                log.debug("Class {} not visible to parent classloader, skipping exposure", fullyQualifiedClassName);
            } catch (Exception e) {
                log.warn("Failed to expose bean {}: {}", fullyQualifiedClassName, e.getMessage());
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
            for (String fullyQualifiedClassName : beanClassNames) {
                try {
                    Class<?> beanClass = Class.forName(fullyQualifiedClassName, true, featureLoader);
                    child.registerBean(beanClass);
                } catch (ClassNotFoundException e) {
                    log.warn("Bean class {} not found on feature loader: {}", fullyQualifiedClassName, e.getMessage());
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
    }

    // helpers
    private static class DescriptorInfo {
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

    private static List<String> readStringArrayField(Class<?> clazz, String name) {
        try {
            Field f = clazz.getField(name);
            Object v = f.get(null);
            if (v instanceof String[] arr) return Arrays.asList(arr);
            return Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}

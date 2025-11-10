package com.aajumaharjan.pluginloader.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;




@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@Slf4j
public class PluginLoaderProcessor extends AbstractProcessor {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([\\w\\.]+)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\b(class|interface|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final List<String> STEREOTYPES = List.of(
            "@Component", "@Service", "@Repository", "@Controller", "@RestController", "@Configuration", "@Bean"
    );

    private final Set<String> processedRepos = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> generatedTypes = Collections.synchronizedSet(new HashSet<>());

    // explicit public no-arg constructor to satisfy ServiceLoader reflective instantiation
    public PluginLoaderProcessor() {}

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "[PluginLoaderProcessor] running");

        try {
            Path yamlPath = Path.of("src/main/resources/application.yml");
            if (!Files.exists(yamlPath)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "No application.yml found at src/main/resources/application.yml");
                return false;
            }

            // Create ObjectMapper: try to use YAMLFactory if available, otherwise fallback to default ObjectMapper.
            ObjectMapper mapper;
            try {
                // detect YAMLFactory class
                Class<?> yamlFactoryClass = Class.forName("com.fasterxml.jackson.dataformat.yaml.YAMLFactory");
                Object yamlFactoryInstance = yamlFactoryClass.getDeclaredConstructor().newInstance();

                // ObjectMapper constructor expects com.fasterxml.jackson.core.JsonFactory (supertype)
                Class<?> jsonFactoryClass = Class.forName("com.fasterxml.jackson.core.JsonFactory");
                Constructor<?> omCtor = ObjectMapper.class.getConstructor(jsonFactoryClass);
                mapper = (ObjectMapper) omCtor.newInstance(yamlFactoryInstance);
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Using jackson-dataformat-yaml via reflection for YAML parsing");
            } catch (ClassNotFoundException cnf) {
                // YAML dataformat not present on annotation-processor classpath — fallback
                mapper = new ObjectMapper();
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "jackson-dataformat-yaml not present on annotation-processor classpath; using default ObjectMapper (JSON parser)");
            } catch (NoSuchMethodException | IllegalArgumentException ex) {
                // Reflection couldn't find the expected constructor — fallback to default
                mapper = new ObjectMapper();
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Failed to create YAML ObjectMapper via reflection; using default. " + ex.toString());
            } catch (Exception ex) {
                mapper = new ObjectMapper();
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Unexpected error creating YAML ObjectMapper; using default. " + ex.toString());
            }

            Map<String, Object> config = mapper.readValue(Files.newInputStream(yamlPath), Map.class);
            Object pluginloaderObj = config.get("pluginloader");
            if (!(pluginloaderObj instanceof Map)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "No pluginloader section in application.yml");
                return false;
            }
            Map<?, ?> pluginloader = (Map<?, ?>) pluginloaderObj;
            Object featuresObj = pluginloader.get("features");
            if (!(featuresObj instanceof List)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "No features configured under pluginloader.features");
                return false;
            }

            List<?> featuresList = (List<?>) featuresObj;

            for (Object fObj : featuresList) {
                if (!(fObj instanceof Map)) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "Skipping invalid feature entry (not a map)");
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> feature = (Map<String, Object>) fObj;

                String repoUrl = firstString(feature, "repo", "repository");
                if (repoUrl == null || repoUrl.isBlank()) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "Skipping feature with no repo/repository entry");
                    continue;
                }

                if (!processedRepos.add(repoUrl)) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Already processed repo " + repoUrl + " - skipping duplicate round");
                    continue;
                }

                String branch = firstString(feature, "branch");
                if (branch == null) branch = "main";

                String pkgHint = null;
                Object pkgObj = feature.get("package");
                if (pkgObj == null) pkgObj = feature.get("packages");
                if (pkgObj instanceof String) {
                    pkgHint = (String) pkgObj;
                } else if (pkgObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> pkgList = (List<Object>) pkgObj;
                    pkgHint = pkgList.stream().map(Object::toString).filter(s -> !s.isBlank()).reduce((a, b) -> a + "," + b).orElse(null);
                }

                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Cloning feature from " + repoUrl);
                Path tempDir = Files.createTempDirectory("feature-");
                try (Git _git = Git.cloneRepository().setURI(repoUrl).setBranch(branch).setDirectory(tempDir.toFile()).call()) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Cloned to " + tempDir);

                    Path srcRoot = tempDir.resolve("src").resolve("main").resolve("java");
                    Set<String> beanClasses = new TreeSet<>();
                    Set<String> packages = new TreeSet<>();

                    if (Files.exists(srcRoot)) {
                        try (Stream<Path> paths = Files.walk(srcRoot)) {
                            paths.filter(p -> p.toString().endsWith(".java"))
                                    .forEach(p -> {
                                        try {
                                            String content = Files.readString(p, StandardCharsets.UTF_8);
                                            boolean hasStereo = STEREOTYPES.stream().anyMatch(content::contains);
                                            if (!hasStereo) return;

                                            String pkg = extractGroup(PACKAGE_PATTERN, content, 1);
                                            String className = extractGroup(CLASS_PATTERN, content, 2);
                                            if (className == null) return;

                                            String fqcn = (pkg != null ? pkg + "." + className : className);
                                            beanClasses.add(fqcn);
                                            if (pkg != null) packages.add(pkg);

                                            // Write plugin source into compiler's generated sources so it is compiled.
                                            try {
                                                JavaFileObject src = processingEnv.getFiler().createSourceFile(fqcn);
                                                try (Writer w = src.openWriter()) {
                                                    w.write(content);
                                                }
                                                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                        "Created generated source for " + fqcn);
                                            } catch (FilerException fe) {
                                                // file already created in this run/round — ignore
                                                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                        "Source already generated for " + fqcn + " : " + fe.getMessage());
                                            } catch (Exception e) {
                                                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                                        "Failed to generate source for " + fqcn + ": " + e.toString());
                                            }

                                        } catch (IOException ignored) {
                                        }
                                    });
                        }
                    } else {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "No src/main/java in cloned repo; scanning skipped");
                    }

                    if (pkgHint != null && !pkgHint.isBlank()) {
                        packages.addAll(Arrays.asList(pkgHint.split(",")));
                    }

                    String featureName = deriveFeatureName(repoUrl, pkgHint, beanClasses);
                    String className = featureName + "Descriptor";
                    String packageName = "generated";
                    String fqcn = packageName + "." + className;

                    if (!generatedTypes.add(fqcn)) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Descriptor " + fqcn + " already generated in this run - skipping");
                    } else {
                        // try to build the feature and copy jar into client target dir
                        String jarPathForDescriptor = null;
                        try {
                            Optional<Path> builtJar = buildAndCopyArtifact(tempDir, featureName);
                            if (builtJar.isPresent()) {
                                Path rel = Path.of("target").resolve("pluginloader").resolve("features").resolve(builtJar.get().getFileName());
                                jarPathForDescriptor = rel.toString().replace(java.io.File.separatorChar, '/');
                                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Copied feature jar to " + rel);
                            } else {
                                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "No jar produced/available for " + repoUrl + " - descriptor will not include JAR_PATH");
                            }
                        } catch (Exception e) {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Build/copy step failed: " + e.toString());
                        }

                        try {
                            JavaFileObject builderFile = processingEnv.getFiler().createSourceFile(packageName + "." + className);
                            try (Writer writer = builderFile.openWriter()) {
                                writer.write("package " + packageName + ";\n\n");
                                writer.write("public final class " + className + " {\n");

                                if (jarPathForDescriptor != null) {
                                    writer.write("    // path relative to project root\n");
                                    writer.write("    public static final String JAR_PATH = \"" + escapeForJava(jarPathForDescriptor) + "\";\n\n");
                                }

                                writer.write("    public static final String[] PACKAGES = new String[] {");
                                boolean first = true;
                                for (String p : packages) {
                                    if (!first) writer.write(", ");
                                    writer.write("\"" + escapeForJava(p) + "\"");
                                    first = false;
                                }
                                writer.write("};\n\n");

                                writer.write("    public static final String[] BEAN_CLASSES = new String[] {");
                                first = true;
                                for (String b : beanClasses) {
                                    if (!first) writer.write(", ");
                                    writer.write("\"" + escapeForJava(b) + "\"");
                                    first = false;
                                }
                                writer.write("};\n\n");

                                writer.write("    private " + className + "(){}\n");
                                writer.write("}\n");
                            }
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Generated " + packageName + "." + className);
                        } catch (IOException ioe) {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to write descriptor " + fqcn + ": " + ioe.toString());
                        }
                    }
                } catch (Exception e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed processing feature " + repoUrl + ": " + e.toString());
                } finally {
                    try {
                        Files.walk(tempDir)
                                .sorted(Comparator.reverseOrder())
                                .forEach(p -> {
                                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                                });
                    } catch (Exception ignored) {}
                }
            }

            return true;

        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "PluginLoaderProcessor failure: " + e.toString());
            return false;
        }
    }

    // Run 'mvn -DskipTests clean package' in repoDir, find produced jar and copy to client target/pluginloader/features/<featureName>.jar
    private Optional<Path> buildAndCopyArtifact(Path repoDir, String featureName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "-DskipTests", "clean", "package");
            pb.directory(repoDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (var isr = new java.io.InputStreamReader(p.getInputStream());
                 var br = new java.io.BufferedReader(isr)) {
                String line;
                while ((line = br.readLine()) != null) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "[mvn] " + line);
                }
            }

            boolean finished = p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Maven build failed or timed out for " + repoDir);
                return Optional.empty();
            }

            Path targetDir = repoDir.resolve("target");
            if (!Files.exists(targetDir)) return Optional.empty();

            // prefer *.jar.original (unrepackaged), then fallback to plain jars
            try (Stream<Path> stream = Files.list(targetDir)) {
                Optional<Path> originalJar = stream
                        .filter(pt -> pt.getFileName().toString().endsWith(".jar.original"))
                        .findFirst();

                Path chosen;
                if (originalJar.isPresent()) {
                    chosen = originalJar.get();
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Found original jar: " + chosen.getFileName());
                } else {
                    // search again for normal jars (exclude sources/javadoc)
                    try (Stream<Path> stream2 = Files.list(targetDir)) {
                        Optional<Path> jar = stream2
                                .filter(pt -> {
                                    String n = pt.getFileName().toString();
                                    return n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar");
                                })
                                .findFirst();
                        if (jar.isEmpty()) return Optional.empty();
                        chosen = jar.get();
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Found repackaged jar (fallback): " + chosen.getFileName());
                    }
                }

                Path destDir = Path.of(System.getProperty("user.dir")).resolve("target").resolve("pluginloader").resolve("features");
                Files.createDirectories(destDir);
                Path dest = destDir.resolve(featureName + "-" + System.currentTimeMillis() + ".jar");
                Files.copy(chosen, dest, StandardCopyOption.REPLACE_EXISTING);
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Copied feature artifact to " + dest);
                return Optional.of(dest);
            }
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "buildAndCopyArtifact error: " + e.toString());
            return Optional.empty();
        }
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v instanceof String) return (String) v;
            if (v != null) return v.toString();
        }
        return null;
    }

    // new: extract a specific regex group index (1-based)
    private static String extractGroup(Pattern pattern, String content, int groupIndex) {
        Matcher m = pattern.matcher(content);
        if (m.find()) {
            try {
                return m.group(groupIndex);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String deriveFeatureName(String repoUrl, String pkgHint, Set<String> beanClasses) {
        if (pkgHint != null && !pkgHint.isBlank()) {
            String[] parts = pkgHint.split("\\.");
            return capitalize(parts[parts.length - 1]);
        }
        if (!beanClasses.isEmpty()) {
            String any = beanClasses.iterator().next();
            String[] parts = any.split("\\.");
            return capitalize(parts[parts.length - 1]);
        }
        String name = repoUrl;
        int slash = Math.max(repoUrl.lastIndexOf('/'), repoUrl.lastIndexOf('\\'));
        if (slash != -1 && slash + 1 < repoUrl.length()) name = repoUrl.substring(slash + 1);
        if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
        return capitalize(name.replaceAll("[^A-Za-z0-9]", ""));
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String escapeForJava(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
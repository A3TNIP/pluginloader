package com.aajumaharjan.pluginloader.processor;

import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class SourceGenerator {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([\\w\\.]+)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\b(class|interface|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");

    public ScanResult generateSources(Path srcRoot,
                               Set<String> configuredPackages,
                               ProcessingEnvironment processingEnv,
                               Messager messager) {
        Set<String> beanClasses = new HashSet<>();
        Set<String> packages = new HashSet<>();

        if (!Files.exists(srcRoot)) {
            messager.printMessage(Diagnostic.Kind.NOTE, "No src/main/java in cloned repo; scanning skipped");
            return new ScanResult(packages, beanClasses);
        }

        try (Stream<Path> paths = Files.walk(srcRoot)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> handleSourceFile(p, configuredPackages, beanClasses, packages, processingEnv, messager));
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.NOTE, "Failed walking sources: " + e);
        }
        return new ScanResult(packages, beanClasses);
    }

    private void handleSourceFile(Path path,
                                  Set<String> configuredPackages,
                                  Set<String> beanClasses,
                                  Set<String> packages,
                                  ProcessingEnvironment processingEnv,
                                  Messager messager) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String pkg = extractGroup(PACKAGE_PATTERN, content, 1);
            if (!configuredPackages.isEmpty() && !isPackageAllowed(pkg, configuredPackages)) {
                return;
            }

            String className = extractGroup(CLASS_PATTERN, content, 2);
            if (className == null) return;

            String fullyQualifiedClassName = (pkg != null ? pkg + "." + className : className);
            beanClasses.add(fullyQualifiedClassName);
            if (pkg != null) packages.add(pkg);

            writeGeneratedSource(processingEnv.getFiler(), fullyQualifiedClassName, content, messager);
        } catch (IOException ignored) {
        }
    }

    private void writeGeneratedSource(Filer filer, String fullyQualifiedClassName, String content, Messager messager) {
        try {
            JavaFileObject src = filer.createSourceFile(fullyQualifiedClassName);
            try (Writer w = src.openWriter()) {
                w.write(content);
            }
            messager.printMessage(Diagnostic.Kind.NOTE, "Created generated source for " + fullyQualifiedClassName);
        } catch (FilerException fe) {
            messager.printMessage(Diagnostic.Kind.NOTE, "Source already generated for " + fullyQualifiedClassName + " : " + fe.getMessage());
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.NOTE, "Failed to generate source for " + fullyQualifiedClassName + ": " + e);
        }
    }

    private String extractGroup(Pattern pattern, String content, int groupIndex) {
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

    private boolean isPackageAllowed(String pkg, Set<String> configuredPackages) {
        if (pkg == null || configuredPackages.isEmpty()) {
            return false;
        }
        for (String configured : configuredPackages) {
            if (Objects.equals(pkg, configured) || pkg.startsWith(configured + ".")) {
                return true;
            }
        }
        return false;
    }
}

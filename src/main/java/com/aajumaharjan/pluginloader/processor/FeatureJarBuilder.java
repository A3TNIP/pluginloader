package com.aajumaharjan.pluginloader.processor;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.stream.Stream;

class FeatureJarBuilder {

    Optional<Path> buildAndCopyArtifact(Path repoDir, String featureName, Messager messager) {
        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "-DskipTests", "clean", "package");
            pb.directory(repoDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (var isr = new java.io.InputStreamReader(p.getInputStream());
                 var br = new java.io.BufferedReader(isr)) {
                String line;
                while ((line = br.readLine()) != null) {
                    messager.printMessage(Diagnostic.Kind.NOTE, "[mvn] " + line);
                }
            }

            boolean finished = p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                messager.printMessage(Diagnostic.Kind.NOTE, "Maven build failed or timed out for " + repoDir);
                return Optional.empty();
            }

            Path targetDir = repoDir.resolve("target");
            if (!Files.exists(targetDir)) return Optional.empty();

            try (Stream<Path> stream = Files.list(targetDir)) {
                Optional<Path> originalJar = stream
                        .filter(pt -> pt.getFileName().toString().endsWith(".jar.original"))
                        .findFirst();

                Path chosen;
                if (originalJar.isPresent()) {
                    chosen = originalJar.get();
                    messager.printMessage(Diagnostic.Kind.NOTE, "Found original jar: " + chosen.getFileName());
                } else {
                    try (Stream<Path> stream2 = Files.list(targetDir)) {
                        Optional<Path> jar = stream2
                                .filter(pt -> {
                                    String n = pt.getFileName().toString();
                                    return n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar");
                                })
                                .findFirst();
                        if (jar.isEmpty()) return Optional.empty();
                        chosen = jar.get();
                        messager.printMessage(Diagnostic.Kind.NOTE, "Found repackaged jar (fallback): " + chosen.getFileName());
                    }
                }

                Path destDir = Path.of(System.getProperty("user.dir")).resolve("target").resolve("pluginloader").resolve("features");
                Files.createDirectories(destDir);
                Path dest = destDir.resolve(featureName + "-" + System.currentTimeMillis() + ".jar");
                Files.copy(chosen, dest, StandardCopyOption.REPLACE_EXISTING);
                messager.printMessage(Diagnostic.Kind.NOTE, "Copied feature artifact to " + dest);
                return Optional.of(dest);
            }
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.NOTE, "buildAndCopyArtifact error: " + e);
            return Optional.empty();
        }
    }
}

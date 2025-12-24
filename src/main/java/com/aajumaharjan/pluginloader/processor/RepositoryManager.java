package com.aajumaharjan.pluginloader.processor;

import org.eclipse.jgit.api.Git;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

class RepositoryManager {

    Path cloneRepository(FeatureRequest feature, Messager messager) {
        try {
            messager.printMessage(Diagnostic.Kind.NOTE, "Cloning feature from " + feature.repository());
            Path tempDir = Files.createTempDirectory("feature-");
            Git.cloneRepository()
                    .setURI(feature.repository())
                    .setBranch(feature.branch())
                    .setDirectory(tempDir.toFile())
                    .call()
                    .close();
            messager.printMessage(Diagnostic.Kind.NOTE, "Cloned to " + tempDir);
            return tempDir;
        } catch (Exception e) {
            throw new RuntimeException("Failed to clone repository " + feature.repository(), e);
        }
    }

    void cleanup(Path directory, Messager messager) {
        try {
            Files.walk(directory)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.NOTE, "Cleanup failed for " + directory + ": " + e);
        }
    }
}

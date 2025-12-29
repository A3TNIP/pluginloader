package com.aajumaharjan.pluginloader.processor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FeatureJarBuilderTest {
    private final TestSupport.RecordingMessager messager = new TestSupport.RecordingMessager();

    @Test
    void buildAndCopyArtifactValid() throws Exception {
        Path repo = Files.createTempDirectory("repo");
        Path fakeBin = Files.createTempDirectory("fake-mvn");
        Path script = fakeBin.resolve("mvn");
        Files.writeString(script, "#!/bin/bash\nmkdir -p target\necho \"fake build\" > target/log.txt\ntouch target/output.jar.original\nexit 0\n");
        script.toFile().setExecutable(true);
        FeatureJarBuilder builder = builderWithPath(fakeBin);

        Optional<Path> jar = builder.buildAndCopyArtifact(repo, "feature", messager);
        assertTrue(jar.isPresent());
        assertTrue(Files.exists(jar.get()));
        assertTrue(messager.getMessages().stream().anyMatch(m -> m.contains("Found original jar")));
    }

    @Test
    void buildAndCopyArtifactInvalidBuild() throws Exception {
        Path repo = Files.createTempDirectory("repo-fail");
        Path fakeBin = Files.createTempDirectory("fake-mvn-fail");
        Path script = fakeBin.resolve("mvn");
        Files.writeString(script, "#!/bin/bash\nexit 1\n");
        script.toFile().setExecutable(true);
        FeatureJarBuilder builder = builderWithPath(fakeBin);

        Optional<Path> jar = builder.buildAndCopyArtifact(repo, "feature", messager);
        assertTrue(jar.isEmpty());
        assertTrue(messager.getMessages().stream().anyMatch(m -> m.contains("Maven build failed")));
    }

    @Test
    void buildAndCopyArtifactBoundaryNoJarProduced() throws Exception {
        Path repo = Files.createTempDirectory("repo-empty");
        Path fakeBin = Files.createTempDirectory("fake-mvn-empty");
        Path script = fakeBin.resolve("mvn");
        Files.writeString(script, "#!/bin/bash\nmkdir -p target\nexit 0\n");
        script.toFile().setExecutable(true);
        FeatureJarBuilder builder = builderWithPath(fakeBin);

        Optional<Path> jar = builder.buildAndCopyArtifact(repo, "feature", messager);
        assertTrue(jar.isEmpty());
    }

    private FeatureJarBuilder builderWithPath(Path fakeBin) {
        return new FeatureJarBuilder() {
            @Override
            public ProcessBuilder createProcessBuilder(Path repoDir) {
                ProcessBuilder pb = new ProcessBuilder(fakeBin.resolve("mvn").toString(), "-DskipTests", "clean", "package");
                pb.directory(repoDir.toFile());
                pb.redirectErrorStream(true);
                return pb;
            }
        };
    }
}

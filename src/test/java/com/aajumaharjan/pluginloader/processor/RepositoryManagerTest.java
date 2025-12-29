package com.aajumaharjan.pluginloader.processor;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryManagerTest {
    private final RepositoryManager manager = new RepositoryManager();
    private final TestSupport.RecordingMessager messager = new TestSupport.RecordingMessager();

    @Test
    void cloneRepositoryValid() throws Exception {
        Path source = Files.createTempDirectory("source-repo");
        try (Git git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            Files.writeString(source.resolve("README.md"), "demo");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("init").call();
        }

        FeatureRequest req = new FeatureRequest(source.toUri().toString(), "main", Set.of());
        Path cloned = manager.cloneRepository(req, messager);
        assertTrue(Files.exists(cloned.resolve(".git")));
    }

    @Test
    void cloneRepositoryInvalid() {
        FeatureRequest req = new FeatureRequest("file:///nonexistent/repo.git", "main", Set.of());
        assertThrows(RuntimeException.class, () -> manager.cloneRepository(req, messager));
    }

    @Test
    void cleanupBoundary() throws Exception {
        Path dir = Files.createTempDirectory("cleanup");
        Files.deleteIfExists(dir);
        manager.cleanup(dir, messager); // should not throw
    }
}

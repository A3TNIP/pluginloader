package com.aajumaharjan.pluginloader.fetch;

import com.aajumaharjan.pluginloader.model.FeatureConfig;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Slf4j
public class GitFeatureFetcher {
    private final Path baseDir;

    public GitFeatureFetcher() {
        String configured = System.getProperty("pluginloader.features.dir");
        if (configured != null && !configured.isBlank()) {
            baseDir = Path.of(configured);
        } else {
            baseDir = Path.of(System.getProperty("java.io.tmpdir"), "pluginloader", "features");
        }
        try {
            Files.createDirectories(baseDir);
            log.info("Using feature base directory: {}", baseDir.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create base feature directory: " + baseDir, e);
        }
    }

    public File fetchRepository(FeatureConfig featureConfig) {
        String repositoryUrl = featureConfig.getRepository();
        String branch = featureConfig.getBranch() == null ? "refs/heads/main" : featureConfig.getBranch();
        File repoDir = baseDir.resolve(String.valueOf(repositoryUrl.hashCode())).toFile();
        try {
            if (repoDir.exists() && new File(repoDir, ".git").exists()) {
                log.info("Found existing repository at {}. Attempting fetch/pull.", repoDir.getAbsolutePath());
                FileRepositoryBuilder builder = new FileRepositoryBuilder();
                try (Repository repo = builder.setGitDir(new File(repoDir, ".git"))
                        .readEnvironment()
                        .findGitDir()
                        .build();
                     Git git = new Git(repo)) {
                    try {
                        git.fetch().call();
                    } catch (Exception fe) {
                        log.warn("Fetch failed for {}: {}", repositoryUrl, fe.getMessage());
                    }
                    try {
                        git.checkout().setName(branch).setForced(true).call();
                    } catch (Exception co) {
                        log.debug("Checkout failed/ignored: {}", co.getMessage());
                    }
                    try {
                        git.pull().call();
                    } catch (Exception pe) {
                        log.warn("Pull failed for {}: {}", repositoryUrl, pe.getMessage());
                    }
                }
                return repoDir;
            }

            log.info("Cloning repository {} (branch: {}) into {}", repositoryUrl, branch, repoDir.getAbsolutePath());
            try (Git ignored = Git.cloneRepository()
                    .setURI(repositoryUrl)
                    .setBranch(branch)
                    .setDirectory(repoDir)
                    .call()) {
                log.info("Cloned repository to {}", repoDir.getAbsolutePath());
            }
            return repoDir;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch repository: " + repositoryUrl, e);
        }
    }
}

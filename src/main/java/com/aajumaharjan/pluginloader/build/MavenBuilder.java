package com.aajumaharjan.pluginloader.build;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class MavenBuilder {
    private final long timeoutSeconds;

    public MavenBuilder() {
        String cfg = System.getProperty("pluginloader.build.timeout.seconds");
        this.timeoutSeconds = (cfg != null ? Long.parseLong(cfg) : 300L);
    }

    public File build(File repoDir) {
        try {
            // quick check that mvn is available
            Process check = new ProcessBuilder("mvn", "-v").redirectErrorStream(true).start();
            boolean ok = check.waitFor(10, TimeUnit.SECONDS) && check.exitValue() == 0;
            if (!ok) {
                throw new RuntimeException("`mvn` not available on PATH or returned non-zero");
            }

            ProcessBuilder processBuilder = new ProcessBuilder("mvn", "-DskipTests", "clean", "package");
            processBuilder.directory(repoDir);
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            Thread logger = new Thread(() -> streamToLog(process.getInputStream()));
            logger.setDaemon(true);
            logger.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Maven build timed out after " + timeoutSeconds + " seconds");
            }

            int exit = process.exitValue();
            if (exit != 0) {
                throw new RuntimeException("Maven build failed with exit code " + exit);
            }

            File target = new File(repoDir, "target");
            if (!target.exists()) {
                throw new RuntimeException("target directory does not exist after build");
            }

            // prefer original jar produced by Spring Boot repackager, if present
            File[] originalJars = target.listFiles((d, name) -> name.endsWith(".jar.original"));
            if (originalJars != null && originalJars.length > 0) {
                log.info("Using original jar: {}", originalJars[0].getName());
                return originalJars[0];
            }

            File[] jars = target.listFiles((d, name) -> name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"));
            if (jars == null || jars.length == 0) {
                throw new RuntimeException("No jar artifact found in target directory after build");
            }
            // choose the first artifact (could be refined)
            log.info("Build produced artifact: {}", jars[0].getName());
            return jars[0];
        } catch (Exception e) {
            throw new RuntimeException("Failed to build repository: " + repoDir, e);
        }
    }

    private void streamToLog(InputStream in) {
        try (var isr = new java.io.InputStreamReader(in);
             var br = new java.io.BufferedReader(isr)) {
            String line;
            while ((line = br.readLine()) != null) {
                log.info("[mvn] {}", line);
            }
        } catch (Exception ignored) {
        }
    }
}

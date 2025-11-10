package com.aajumaharjan.pluginloader.load;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

@Component
@Slf4j
public class JarClassLoader {
    public URLClassLoader createClassLoaderForJar(File jarFile) {
        try {
            URL jarUrl = jarFile.toURI().toURL();
            ClassLoader parent = Thread.currentThread().getContextClassLoader();
            URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, parent);
            log.info("Created URLClassLoader for {}", jarFile.getAbsolutePath());
            return loader;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create classloader for " + jarFile, e);
        }
    }
}

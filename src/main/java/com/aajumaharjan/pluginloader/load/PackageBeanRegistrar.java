package com.aajumaharjan.pluginloader.load;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class PackageBeanRegistrar {
    public void registerPackages(ConfigurableApplicationContext appContext, ClassLoader classLoader, List<String> packages) {
        if (packages == null || packages.isEmpty()) {
            log.warn("No packages specified to register");
            return;
        }

        try {
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) appContext.getBeanFactory();

            // Create a resource loader bound to our plugin's classloader
            DefaultResourceLoader resourceLoader = new DefaultResourceLoader(classLoader);

            // Create a scanner that uses our custom resource loader
            ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(registry, false);
            scanner.setResourceLoader(resourceLoader);
            scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);

            for (String pkg : packages) {
                log.info("Scanning and registering beans from package '{}' using custom classloader", pkg);
                int count = scanner.scan(pkg);
                log.info("Registered {} bean definitions from package {}", count, pkg);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to register packages " + packages, e);
        }
    }
}

package com.aajumaharjan.pluginloader;

import com.aajumaharjan.pluginloader.config.PluginLoaderProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "com.aajumaharjan.pluginloader")
@EnableConfigurationProperties(PluginLoaderProperties.class)
@ConditionalOnProperty(prefix = "pluginloader", name = "enabled", havingValue = "true")
public class PluginloaderAutoConfiguration {

    private final FeatureManager featureManager;
    private final ApplicationContext applicationContext;

    public PluginloaderAutoConfiguration(FeatureManager featureManager, ApplicationContext applicationContext) {
        this.featureManager = featureManager;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        featureManager.initialize((ConfigurableApplicationContext) applicationContext);
    }

}

package com.aajumaharjan.pluginloader.config;

import com.aajumaharjan.pluginloader.model.FeatureConfig;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "pluginloader")
@Getter
@Setter
public class PluginLoaderProperties {
    private boolean enabled = false;
    private List<FeatureConfig> features;
}

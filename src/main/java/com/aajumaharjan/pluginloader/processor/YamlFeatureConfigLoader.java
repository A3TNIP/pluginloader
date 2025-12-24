package com.aajumaharjan.pluginloader.processor;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

class YamlFeatureConfigLoader {

    List<FeatureRequest> load(Path yamlPath, Messager messager) {
        try {
            if (!Files.exists(yamlPath)) {
                messager.printMessage(Diagnostic.Kind.NOTE, "No application.yml found at " + yamlPath);
                return Collections.emptyList();
            }

            ObjectMapper mapper = createObjectMapper(messager);
            Map<String, Object> config = mapper.readValue(Files.newInputStream(yamlPath), Map.class);
            Object pluginloaderObj = config.get("pluginloader");
            if (!(pluginloaderObj instanceof Map<?, ?> pluginloader)) {
                messager.printMessage(Diagnostic.Kind.NOTE, "No pluginloader section in application.yml");
                return Collections.emptyList();
            }
            Object featuresObj = pluginloader.get("features");
            if (!(featuresObj instanceof List<?> featuresList)) {
                messager.printMessage(Diagnostic.Kind.NOTE, "No features configured under pluginloader.features");
                return Collections.emptyList();
            }

            List<FeatureRequest> requests = new ArrayList<>();
            for (Object fObj : featuresList) {
                if (!(fObj instanceof Map<?, ?> featureMap)) {
                    messager.printMessage(Diagnostic.Kind.WARNING, "Skipping invalid feature entry (not a map)");
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> feature = (Map<String, Object>) featureMap;

                String repoUrl = firstString(feature, "repo", "repository");
                if (repoUrl == null || repoUrl.isBlank()) {
                    messager.printMessage(Diagnostic.Kind.WARNING, "Skipping feature with no repo/repository entry");
                    continue;
                }
                String branch = Optional.ofNullable(firstString(feature, "branch")).orElse("main");
                Set<String> packages = readConfiguredPackages(feature);
                requests.add(new FeatureRequest(repoUrl, branch, packages));
            }
            return requests;
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Failed to load pluginloader configuration: " + e);
            return Collections.emptyList();
        }
    }

    private ObjectMapper createObjectMapper(Messager messager) {
        try {
            Class<?> yamlFactoryClass = Class.forName("com.fasterxml.jackson.dataformat.yaml.YAMLFactory");
            Object yamlFactoryInstance = yamlFactoryClass.getDeclaredConstructor().newInstance();
            Class<?> jsonFactoryClass = Class.forName("com.fasterxml.jackson.core.JsonFactory");
            Constructor<?> omCtor = ObjectMapper.class.getConstructor(jsonFactoryClass);
            messager.printMessage(Diagnostic.Kind.NOTE, "Using jackson-dataformat-yaml via reflection for YAML parsing");
            return (ObjectMapper) omCtor.newInstance(yamlFactoryInstance);
        } catch (ClassNotFoundException cnf) {
            messager.printMessage(Diagnostic.Kind.NOTE, "jackson-dataformat-yaml not present on annotation-processor classpath; using default ObjectMapper (JSON parser)");
            return new ObjectMapper();
        } catch (Exception ex) {
            messager.printMessage(Diagnostic.Kind.NOTE, "Failed to create YAML ObjectMapper via reflection; using default. " + ex);
            return new ObjectMapper();
        }
    }

    private String firstString(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v instanceof String) return (String) v;
            if (v != null) return v.toString();
        }
        return null;
    }

    private Set<String> readConfiguredPackages(Map<String, Object> feature) {
        Object pkgObj = feature.get("packages");
        if (pkgObj == null) {
            pkgObj = feature.get("package");
        }

        Set<String> packages = new LinkedHashSet<>();
        if (pkgObj instanceof String str && !str.isBlank()) {
            packages.add(str.trim());
        } else if (pkgObj instanceof List<?> list) {
            list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .forEach(packages::add);
        }
        return packages;
    }
}

package com.aajumaharjan.pluginloader.processor;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@Slf4j
public class PluginLoaderProcessor extends AbstractProcessor {
    private volatile boolean processedOnce = false;
    private final Set<String> processedRepos = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> generatedTypes = Collections.synchronizedSet(new HashSet<>());

    private final YamlFeatureConfigLoader configLoader = new YamlFeatureConfigLoader();
    private final RepositoryManager repositoryManager = new RepositoryManager();
    private final SourceGenerator sourceGenerator = new SourceGenerator();
    private final FeatureJarBuilder jarBuilder = new FeatureJarBuilder();
    private final DescriptorGenerator descriptorGenerator = new DescriptorGenerator();
    private final FeatureNameResolver featureNameResolver = new FeatureNameResolver();

    // explicit public no-arg constructor to satisfy ServiceLoader reflective instantiation
    public PluginLoaderProcessor() {}

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || processedOnce) {
            return false;
        }
        processedOnce = true;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "[PluginLoaderProcessor] running");

        try {
            Path yamlPath = Path.of("src/main/resources/application.yml");

            var features = configLoader.load(yamlPath, processingEnv.getMessager());
            if (features.isEmpty()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "No pluginloader.features entries to process");
                return false;
            }

            for (FeatureRequest feature : features) {
                if (!processedRepos.add(feature.repository())) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Already processed repo " + feature.repository() + " - skipping duplicate round");
                    continue;
                }

                Path tempDir = repositoryManager.cloneRepository(feature, processingEnv.getMessager());
                try {
                    var scanResult = sourceGenerator.generateSources(tempDir.resolve("src/main/java"),
                            feature.packages(), processingEnv, processingEnv.getMessager());

                    Set<String> packages = new HashSet<>(scanResult.packages());
                    if (!feature.packages().isEmpty()) {
                        packages.clear();
                        packages.addAll(feature.packages());
                    }

                    String pkgHint = packages.stream().findFirst().orElse(null);
                    String featureName = featureNameResolver.deriveFeatureName(feature.repository(), pkgHint, scanResult.beanClasses());
                    String descriptorFqcn = descriptorGenerator.descriptorFqcn(featureName);

                    if (!generatedTypes.add(descriptorFqcn)) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Descriptor " + descriptorFqcn + " already generated in this run - skipping");
                        continue;
                    }

                    String jarPathForDescriptor = null;
                    try {
                        Optional<Path> builtJar = jarBuilder.buildAndCopyArtifact(tempDir, featureName, processingEnv.getMessager());
                        if (builtJar.isPresent()) {
                            Path rel = Path.of("target").resolve("pluginloader").resolve("features").resolve(builtJar.get().getFileName());
                            jarPathForDescriptor = rel.toString().replace(java.io.File.separatorChar, '/');
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Copied feature jar to " + rel);
                        } else {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "No jar produced/available for " + feature.repository() + " - descriptor will not include JAR_PATH");
                        }
                    } catch (Exception e) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Build/copy step failed: " + e.toString());
                    }

                    descriptorGenerator.writeDescriptor(featureName, packages, scanResult.beanClasses(), jarPathForDescriptor, processingEnv, processingEnv.getMessager());
                } catch (Exception e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed processing feature " + feature.repository() + ": " + e.toString());
                } finally {
                    repositoryManager.cleanup(tempDir, processingEnv.getMessager());
                }
            }

            return true;

        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "PluginLoaderProcessor failure: " + e.toString());
            return false;
        }
    }
}

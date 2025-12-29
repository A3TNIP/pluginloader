package com.aajumaharjan.pluginloader.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class YamlFeatureConfigLoaderTest {
    private final YamlFeatureConfigLoader loader = new YamlFeatureConfigLoader();
    private final TestSupport.RecordingMessager messager = new TestSupport.RecordingMessager();

    @Test
    void loadValidConfigProducesRequests() throws IOException {
        Path temp = Files.createTempFile("app", ".yml");
        String yaml = """
                pluginloader:
                  features:
                    - repo: https://example.com/repo.git
                      branch: dev
                      packages:
                        - com.demo
                        - com.demo.inner
                """;
        Files.writeString(temp, yaml);

        List<FeatureRequest> requests = loader.load(temp, messager);
        assertEquals(1, requests.size());
        FeatureRequest fr = requests.get(0);
        assertEquals("https://example.com/repo.git", fr.repository());
        assertEquals("dev", fr.branch());
        assertEquals(Set.of("com.demo", "com.demo.inner"), fr.packages());
    }

    @Test
    void loadInvalidStructureReturnsEmptyAndWarns() throws IOException {
        Path temp = Files.createTempFile("app", ".yml");
        Files.writeString(temp, "pluginloader: 123");

        List<FeatureRequest> requests = loader.load(temp, messager);
        assertTrue(requests.isEmpty());
        assertTrue(messager.getMessages().stream().anyMatch(m -> m.contains("No pluginloader section")));
    }

    @Test
    void loadBoundaryMissingFileAndMissingEntries() {
        Path missing = Path.of("nonexistent.yml");
        List<FeatureRequest> none = loader.load(missing, messager);
        assertTrue(none.isEmpty());
        assertTrue(messager.getMessages().stream().anyMatch(m -> m.contains("No application.yml")));

        // boundary: feature without repo should be skipped
        Map<String, Object> feature = Map.of("packages", List.of("a.b"));
        assertNull(invokeFirstString(feature, "repo"));
    }

    @Test
    void privateHelpers() throws Exception {
        Method firstString = YamlFeatureConfigLoader.class.getDeclaredMethod("firstString", Map.class, String[].class);
        firstString.setAccessible(true);
        Map<String, Object> map = Map.of("key", 123, "text", "value");
        assertEquals("value", firstString.invoke(loader, map, (Object) new String[]{"text", "other"}));
        assertNull(firstString.invoke(loader, map, (Object) new String[]{"missing"}));
        assertEquals("123", firstString.invoke(loader, map, (Object) new String[]{"key"}));

        Method readConfiguredPackages = YamlFeatureConfigLoader.class.getDeclaredMethod("readConfiguredPackages", Map.class);
        readConfiguredPackages.setAccessible(true);
        Map<String, Object> mapList = Map.of("packages", List.of("a.b", "a.b", " ", "c.d"));
        assertEquals(Set.of("a.b", "c.d"), readConfiguredPackages.invoke(loader, mapList));
        Map<String, Object> mapString = Map.of("package", "single.pkg");
        assertEquals(Set.of("single.pkg"), readConfiguredPackages.invoke(loader, mapString));
        assertEquals(Set.of(), readConfiguredPackages.invoke(loader, Map.of()));
    }

    private String invokeFirstString(Map<String, Object> map, String key) {
        try {
            Method firstString = YamlFeatureConfigLoader.class.getDeclaredMethod("firstString", Map.class, String[].class);
            firstString.setAccessible(true);
            return (String) firstString.invoke(loader, map, (Object) new String[]{key});
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

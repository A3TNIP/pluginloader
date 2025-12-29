package com.aajumaharjan.pluginloader.processor;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FeatureNameResolverTest {
    private final FeatureNameResolver resolver = new FeatureNameResolver();

    @Test
    void deriveFeatureNameValidInvalidBoundary() {
        assertEquals("Service", resolver.deriveFeatureName("http://repo.git", "com.example.service", Set.of()));
        assertEquals("Sample", resolver.deriveFeatureName("http://git/repo.git", null, Set.of("com.demo.Sample")));
        assertEquals("Repo", resolver.deriveFeatureName("http://git/repo.git", null, Set.of()));
        assertThrows(NullPointerException.class, () -> resolver.deriveFeatureName(null, null, Set.of()));
    }

    @Test
    void capitalizeReflection() throws Exception {
        Method cap = FeatureNameResolver.class.getDeclaredMethod("capitalize", String.class);
        cap.setAccessible(true);
        assertEquals("Name", cap.invoke(resolver, "name"));
        assertNull(cap.invoke(resolver, (Object) null));
        assertEquals("", cap.invoke(resolver, ""));
    }
}

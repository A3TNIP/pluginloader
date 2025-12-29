package com.aajumaharjan.pluginloader.processor;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SourceGeneratorTest {
    private final SourceGenerator generator = new SourceGenerator();
    private final TestSupport.RecordingMessager messager = new TestSupport.RecordingMessager();
    private final TestSupport.InMemoryFiler filer = new TestSupport.InMemoryFiler();
    private final ProcessingEnvironment processingEnv = new TestSupport.StubProcessingEnvironment(filer, messager);

    @Test
    void generateSourcesValidAndBoundary() throws Exception {
        Path srcRoot = TestSupport.createTempJavaFile("com.demo", "Sample", "public void run(){}");
        ScanResult result = generator.generateSources(srcRoot, Set.of(), processingEnv, messager);
        assertTrue(result.beanClasses().contains("com.demo.Sample"));
        assertTrue(result.packages().contains("com.demo"));
        assertNotNull(filer.getGeneratedContent("com.demo.Sample"));

        // boundary: package filter excludes class
        ScanResult filtered = generator.generateSources(srcRoot, Set.of("other.pkg"), processingEnv, messager);
        assertTrue(filtered.beanClasses().isEmpty());
        assertTrue(filtered.packages().isEmpty());
    }

    @Test
    void generateSourcesMissingRoot() {
        Path missing = Path.of("does-not-exist");
        ScanResult result = generator.generateSources(missing, Set.of(), processingEnv, messager);
        assertTrue(result.beanClasses().isEmpty());
        assertTrue(messager.getMessages().stream().anyMatch(m -> m.contains("No src/main/java")));
    }

    @Test
    void handleSourceFileAndWriteGeneratedSourceViaReflection() throws Exception {
        Path srcRoot = TestSupport.createTempJavaFile("com.demo.handle", "HandleMe", "");
        Path javaFile = srcRoot.resolve("com/demo/handle/HandleMe.java");
        Method handle = SourceGenerator.class.getDeclaredMethod("handleSourceFile", Path.class, Set.class, Set.class, Set.class, ProcessingEnvironment.class, Messager.class);
        handle.setAccessible(true);
        Set<String> beans = new HashSet<>();
        Set<String> pkgs = new HashSet<>();
        handle.invoke(generator, javaFile, Set.of(), beans, pkgs, processingEnv, messager);
        assertTrue(beans.contains("com.demo.handle.HandleMe"));
        assertTrue(pkgs.contains("com.demo.handle"));

        // writeGeneratedSource failure path
        Filer throwingFiler = new Filer() {
            @Override public javax.tools.JavaFileObject createSourceFile(CharSequence name, javax.lang.model.element.Element... originatingElements) throws java.io.IOException { throw new FilerException("dup"); }
            @Override public javax.tools.JavaFileObject createClassFile(CharSequence name, javax.lang.model.element.Element... originatingElements) { throw new RuntimeException("unsupported"); }
            @Override public javax.tools.FileObject createResource(javax.tools.JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName, javax.lang.model.element.Element... originatingElements) { throw new RuntimeException("unsupported"); }
            @Override public javax.tools.FileObject getResource(javax.tools.JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName) { throw new RuntimeException("unsupported"); }
        };
        ProcessingEnvironment env = new TestSupport.StubProcessingEnvironment(throwingFiler, messager);
        Method writeGenerated = SourceGenerator.class.getDeclaredMethod("writeGeneratedSource", Filer.class, String.class, String.class, Messager.class);
        writeGenerated.setAccessible(true);
        writeGenerated.invoke(generator, throwingFiler, "com.demo.handle.HandleMe", "content", messager);
        assertTrue(messager.getMessages().stream().anyMatch(m -> m.contains("already generated")));
    }

    @Test
    void extractGroupAndPackageAllowedReflection() throws Exception {
        Method extract = SourceGenerator.class.getDeclaredMethod("extractGroup", Pattern.class, String.class, int.class);
        extract.setAccessible(true);
        Pattern pkgPattern = Pattern.compile("package\\s+([\\w\\.]+)");
        assertEquals("com.demo", extract.invoke(generator, pkgPattern, "package com.demo;", 1));
        assertNull(extract.invoke(generator, pkgPattern, "no package", 1));
        assertNull(extract.invoke(generator, pkgPattern, "package com.demo", 3)); // boundary invalid group

        Method isAllowed = SourceGenerator.class.getDeclaredMethod("isPackageAllowed", String.class, Set.class);
        isAllowed.setAccessible(true);
        assertTrue((Boolean) isAllowed.invoke(generator, "a.b.c", Set.of("a.b")));
        assertFalse((Boolean) isAllowed.invoke(generator, null, Set.of("a.b")));
        assertFalse((Boolean) isAllowed.invoke(generator, "x.y", Set.of()));
    }
}

package com.aajumaharjan.pluginloader.processor;

import org.junit.jupiter.api.Test;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DescriptorGeneratorTest {

    private final DescriptorGenerator generator = new DescriptorGenerator();
    private final TestSupport.RecordingMessager messager = new TestSupport.RecordingMessager();
    private final TestSupport.InMemoryFiler filer = new TestSupport.InMemoryFiler();
    private final ProcessingEnvironment processingEnv = new TestSupport.StubProcessingEnvironment(filer, messager);

    @Test
    void getDescriptorNameValidInvalidBoundary() {
        assertEquals("generated.AuthDescriptor", generator.getDescriptorName("Auth"));
        assertEquals("generated.nullDescriptor", generator.getDescriptorName(null));
        assertEquals("generated.Descriptor", generator.getDescriptorName(""));
    }

    @Test
    void writeDescriptorWritesContent() {
        LinkedHashSet<String> packages = new LinkedHashSet<>(Set.of("com.demo", "com.demo.service"));
        LinkedHashSet<String> beans = new LinkedHashSet<>(Set.of("com.demo.Service"));

        generator.writeDescriptor("Demo", packages, beans, "target/pluginloader/features/demo.jar", processingEnv, messager);

        String content = filer.getGeneratedContent("generated.DemoDescriptor");
        assertNotNull(content);
        assertTrue(content.contains("class DemoDescriptor"));
        assertTrue(content.contains("JAR_PATH = \"target/pluginloader/features/demo.jar\""));
        assertTrue(content.contains("\"com.demo\""));
        assertTrue(content.contains("\"com.demo.service\""));
        assertTrue(content.contains("\"com.demo.Service\""));
        assertTrue(messager.getMessages().stream().anyMatch(m -> m.contains("Generated generated.DemoDescriptor")));
    }

    @Test
    void writeDescriptorHandlesFailure() throws IOException {
        Filer throwingFiler = new Filer() {
            @Override
            public JavaFileObject createSourceFile(CharSequence name, javax.lang.model.element.Element... originatingElements) throws IOException {
                throw new IOException("boom");
            }
            @Override public JavaFileObject createClassFile(CharSequence name, javax.lang.model.element.Element... originatingElements) throws IOException { throw new IOException("boom"); }
            @Override public javax.tools.FileObject createResource(javax.tools.JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName, javax.lang.model.element.Element... originatingElements) throws IOException { throw new IOException("boom"); }
            @Override public javax.tools.FileObject getResource(javax.tools.JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName) throws IOException { throw new IOException("boom"); }
        };
        ProcessingEnvironment env = new TestSupport.StubProcessingEnvironment(throwingFiler, messager);

        generator.writeDescriptor("Broken", Set.of(), Set.of(), null, env, messager);

        assertTrue(messager.getMessages().stream().anyMatch(m -> m.contains("Failed to write descriptor")));
    }

    @Test
    void writeDescriptorWithEmptyCollections() {
        generator.writeDescriptor("Empty", Set.of(), Set.of(), null, processingEnv, messager);
        String content = filer.getGeneratedContent("generated.EmptyDescriptor");
        assertNotNull(content);
        assertFalse(content.contains("JAR_PATH"));
        assertTrue(content.contains("PACKAGES = new String[] {}"));
        assertTrue(content.contains("BEAN_CLASSES = new String[] {}"));
    }

    @Test
    void escapeAndWriteArrayReflection() throws Exception {
        Method escape = DescriptorGenerator.class.getDeclaredMethod("escapeForJava", String.class);
        escape.setAccessible(true);
        assertEquals("a\\\"b\\\\c", escape.invoke(generator, "a\"b\\c"));
        var ex = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> escape.invoke(generator, (Object) null));
        assertTrue(ex.getCause() instanceof NullPointerException);
        assertEquals("", escape.invoke(generator, ""));

        Method writeArray = DescriptorGenerator.class.getDeclaredMethod("writeArray", java.io.Writer.class, Set.class);
        writeArray.setAccessible(true);
        StringWriter writer = new StringWriter();
        writeArray.invoke(generator, writer, Set.of("x", "y"));
        assertEquals("\"x\", \"y\"", writer.toString());
    }
}

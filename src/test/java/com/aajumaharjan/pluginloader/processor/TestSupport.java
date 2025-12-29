package com.aajumaharjan.pluginloader.processor;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Test helpers for in-memory filer and message capture.
 */
class TestSupport {
    static class RecordingMessager implements Messager {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
            messages.add("[" + kind + "] " + msg);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
            printMessage(kind, msg);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
            printMessage(kind, msg);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) {
            printMessage(kind, msg);
        }

        List<String> getMessages() {
            return messages;
        }
    }

    static class InMemoryFiler implements Filer {
        private final Map<String, String> generatedSources = new HashMap<>();

        @Override
        public JavaFileObject createSourceFile(CharSequence name, Element... originatingElements) throws IOException {
            String path = name.toString();
            return new SimpleJavaFileObject(URI.create("string:///" + path.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension), JavaFileObject.Kind.SOURCE) {
                @Override
                public Writer openWriter() {
                    return new java.io.StringWriter() {
                        @Override
                        public void close() throws IOException {
                            super.close();
                            generatedSources.put(path, toString());
                        }
                    };
                }
            };
        }

        @Override
        public JavaFileObject createClassFile(CharSequence name, Element... originatingElements) {
            throw new UnsupportedOperationException("createClassFile not supported in tests");
        }

        @Override
        public FileObject createResource(javax.tools.JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName, Element... originatingElements) {
            throw new UnsupportedOperationException("createResource not supported in tests");
        }

        @Override
        public FileObject getResource(javax.tools.JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName) {
            throw new UnsupportedOperationException("getResource not supported in tests");
        }

        String getGeneratedContent(String name) {
            return generatedSources.get(name);
        }
    }

    static Path createTempJavaFile(String pkg, String className, String body) throws IOException {
        Path tempDir = Files.createTempDirectory("src");
        Path pkgDir = pkg == null ? tempDir : tempDir.resolve(pkg.replace('.', '/'));
        Files.createDirectories(pkgDir);
        Path javaFile = pkgDir.resolve(className + ".java");
        StringBuilder content = new StringBuilder();
        if (pkg != null && !pkg.isBlank()) {
            content.append("package ").append(pkg).append(";\n\n");
        }
        content.append("public class ").append(className).append(" {\n")
                .append(body == null ? "" : body)
                .append("\n}");
        Files.writeString(javaFile, content.toString());
        return tempDir;
    }

    static class StubProcessingEnvironment implements ProcessingEnvironment {
        private final Filer filer;
        private final Messager messager;

        StubProcessingEnvironment(Filer filer, Messager messager) {
            this.filer = filer;
            this.messager = messager;
        }

        @Override
        public Map<String, String> getOptions() {
            return Collections.emptyMap();
        }

        @Override
        public Messager getMessager() {
            return messager;
        }

        @Override
        public Filer getFiler() {
            return filer;
        }

        @Override
        public Elements getElementUtils() {
            return null;
        }

        @Override
        public Types getTypeUtils() {
            return null;
        }

        @Override
        public SourceVersion getSourceVersion() {
            return SourceVersion.latest();
        }

        @Override
        public Locale getLocale() {
            return Locale.getDefault();
        }
    }
}

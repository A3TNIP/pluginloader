package com.aajumaharjan.pluginloader.processor;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;

class DescriptorGenerator {
    private static final String DESCRIPTOR_PACKAGE = "generated";

    String descriptorFqcn(String featureName) {
        return DESCRIPTOR_PACKAGE + "." + featureName + "Descriptor";
    }

    void writeDescriptor(String featureName,
                         Set<String> packages,
                         Set<String> beanClasses,
                         String jarPathForDescriptor,
                         ProcessingEnvironment processingEnv,
                         Messager messager) {
        String className = featureName + "Descriptor";
        String fqcn = DESCRIPTOR_PACKAGE + "." + className;
        try {
            JavaFileObject builderFile = processingEnv.getFiler().createSourceFile(fqcn);
            try (Writer writer = builderFile.openWriter()) {
                writer.write("package " + DESCRIPTOR_PACKAGE + ";\n\n");
                writer.write("public final class " + className + " {\n");

                if (jarPathForDescriptor != null) {
                    writer.write("    // path relative to project root\n");
                    writer.write("    public static final String JAR_PATH = \"" + escapeForJava(jarPathForDescriptor) + "\";\n\n");
                }

                writer.write("    public static final String[] PACKAGES = new String[] {");
                writeArray(writer, packages);
                writer.write("};\n\n");

                writer.write("    public static final String[] BEAN_CLASSES = new String[] {");
                writeArray(writer, beanClasses);
                writer.write("};\n\n");

                writer.write("    private " + className + "(){}\n");
                writer.write("}\n");
            }
            messager.printMessage(Diagnostic.Kind.NOTE, "Generated " + fqcn);
        } catch (IOException ioe) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Failed to write descriptor " + fqcn + ": " + ioe);
        }
    }

    private void writeArray(Writer writer, Set<String> values) throws IOException {
        boolean first = true;
        for (String v : values) {
            if (!first) writer.write(", ");
            writer.write("\"" + escapeForJava(v) + "\"");
            first = false;
        }
    }

    private String escapeForJava(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

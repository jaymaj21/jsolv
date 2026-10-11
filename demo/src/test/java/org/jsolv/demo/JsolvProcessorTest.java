package org.jsolv.demo;

import org.jsolv.checker.JsolvProcessor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsolvProcessorTest {
    @Test
    void acceptsValuesReturnedFromPersistedMethods() throws IOException {
        CompileResult result = compile("""
                package demo;

                import org.jsolv.Persisted;

                final class GoodCase {
                    static final class Article {}

                    @Persisted Article persist(Article article) {
                        return article;
                    }

                    void publish(@Persisted Article article) {}

                    void run() {
                        Article draft = new Article();
                        Article saved = persist(draft);
                        publish(saved);
                        publish(persist(new Article()));
                    }
                }
                """);

        assertTrue(result.success(), result.formattedDiagnostics());
    }

    @Test
    void rejectsUnpersistedArguments() throws IOException {
        CompileResult result = compile("""
                package demo;

                import org.jsolv.Persisted;

                final class BadCase {
                    static final class Article {}

                    @Persisted Article persist(Article article) {
                        return article;
                    }

                    void publish(@Persisted Article article) {}

                    void run() {
                        Article draft = new Article();
                        publish(draft);
                    }
                }
                """);

        assertFalse(result.success(), "compile unexpectedly succeeded");
        assertTrue(result.formattedDiagnostics().contains("missing @Persisted"), result.formattedDiagnostics());
    }

    @Test
    void forgetsTagAfterReassignment() throws IOException {
        CompileResult result = compile("""
                package demo;

                import org.jsolv.Persisted;

                final class ReassignmentCase {
                    static final class Article {}

                    @Persisted Article persist(Article article) {
                        return article;
                    }

                    void publish(@Persisted Article article) {}

                    void run() {
                        Article article = persist(new Article());
                        article = new Article();
                        publish(article);
                    }
                }
                """);

        assertFalse(result.success(), "compile unexpectedly succeeded");
        assertTrue(result.formattedDiagnostics().contains("missing @Persisted"), result.formattedDiagnostics());
    }

    @Test
    void keepsOnlyFactsPresentOnBothIfBranches() throws IOException {
        CompileResult result = compile("""
                package demo;

                import org.jsolv.Persisted;

                final class BranchCase {
                    static final class Article {}

                    @Persisted Article persist(Article article) {
                        return article;
                    }

                    void publish(@Persisted Article article) {}

                    void run(boolean condition) {
                        Article article = new Article();
                        if (condition) {
                            article = persist(article);
                        }
                        publish(article);
                    }
                }
                """);

        assertFalse(result.success(), "compile unexpectedly succeeded");
        assertTrue(result.formattedDiagnostics().contains("missing @Persisted"), result.formattedDiagnostics());
    }

    private static CompileResult compile(String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> options = Arrays.asList(
                "--release", "17",
                "-classpath", System.getProperty("java.class.path"));

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            Path outputDirectory = Files.createTempDirectory("jsolv-checker-test-classes");
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outputDirectory));
            JavaFileObject file = new StringJavaFileObject("demo/TestCase.java", source);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    List.of(file));
            task.setProcessors(List.of(new JsolvProcessor()));
            boolean success = task.call();
            return new CompileResult(success, diagnostics.getDiagnostics());
        }
    }

    private record CompileResult(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        String formattedDiagnostics() {
            List<String> lines = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                lines.add(diagnostic.getKind() + " line " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT));
            }
            return String.join(System.lineSeparator(), lines);
        }
    }

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String source;

        private StringJavaFileObject(String fileName, String source) {
            super(URI.create("string:///" + fileName), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}

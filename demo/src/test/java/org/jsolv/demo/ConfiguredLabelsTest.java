package org.jsolv.demo;

import org.jsolv.checker.JsolvProcessor;
import org.jsolv.generator.LabelGenerator;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredLabelsTest {
    @TempDir Path temporary;
    private static final String PRELUDE = """
        package demo;
        import org.jsolv.*;
        import java.lang.annotation.*;
        class Cases {
            @StaticTag @Target({ElementType.TYPE_USE,ElementType.PARAMETER}) @Retention(RetentionPolicy.CLASS)
            @interface Validated {}
            @StaticTag @Target({ElementType.TYPE_USE,ElementType.PARAMETER}) @Retention(RetentionPolicy.CLASS)
            @interface Authenticated {}
            static class Article {}
            @Persisted Article persist(Article a) { return a; }
            @PreservesTags @Validated Article validate(Article a) { return a; }
            @PreservesTags @Authenticated Article authenticate(Article a) { return a; }
            Article modify(Article a) { return a; }
            void publish(@Persisted @Validated @Authenticated Article a) {}
            void savedOnly(@Persisted Article a) {}
        """;

    private record Result(boolean success, String diagnostics) {}
    private Result compile(Map<String,String> sources, List<String> extras, boolean checker) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path output = temporary.resolve("classes");
        Files.createDirectories(output);
        List<String> options = new ArrayList<>(List.of("--release", "17", "-d", output.toString(),
                "-classpath", System.getProperty("java.class.path") + java.io.File.pathSeparator + output));
        options.addAll(extras);
        if (!checker) options.add("-proc:none");
        List<JavaFileObject> files = new ArrayList<>();
        sources.forEach((name, source) -> files.add(new SimpleJavaFileObject(URI.create("string:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        }));
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            var task = compiler.getTask(null, manager, diagnostics, options, null, files);
            if (checker) task.setProcessors(List.of(new JsolvProcessor()));
            boolean success = task.call();
            return new Result(success, diagnostics.getDiagnostics().toString());
        }
    }
    private Result check(String code) throws Exception {
        return compile(Map.of("demo/Cases.java", PRELUDE + code + "\n}"), List.of(), true);
    }
    private void accepts(String code) throws Exception {
        Result result = check(code); assertTrue(result.success, result.diagnostics);
    }
    private void rejects(String code, String label) throws Exception {
        Result result = check(code); assertFalse(result.success, result.diagnostics);
        assertTrue(result.diagnostics.contains(label), result.diagnostics);
    }

    @Test void composesIndependentLabelsAndChecksNestedCalls() throws Exception {
        accepts("void run() { Article a = authenticate(validate(persist(new Article()))); publish(a); }");
        rejects("void run() { publish(validate(persist(new Article()))); }", "missing @Authenticated");
    }
    @Test void trustsParameterContractsAndCopiesAliases() throws Exception {
        accepts("void run(@Persisted @Validated @Authenticated Article a) { Article copy = a; publish(copy); }");
    }
    @Test void unannotatedReturnDropsEveryLabelEvenWithSameObject() throws Exception {
        rejects("void run() { Article a = authenticate(validate(persist(new Article()))); a = modify(a); publish(a); }", "@Persisted");
    }
    @Test void preservationUsesSelectedArgument() throws Exception {
        accepts("""
            @PreservesTags(2) @Validated Article second(Article ignored, Article a) { return a; }
            void run() { publish(authenticate(second(new Article(), persist(new Article())))); }
            """);
    }
    @Test void rejectsInvalidPreservationContracts() throws Exception {
        rejects("@PreservesTags(0) Article invalid(Article a) { return a; }", "one-based");
        rejects("@PreservesTags void invalid(Article a) {}", "non-void");
        rejects("@PreservesTags Article invalid(Article... a) { return a[0]; }", "non-varargs");
    }
    @Test void branchJoinIntersectsEachLabel() throws Exception {
        rejects("""
            void run(boolean condition) {
                Article a;
                if (condition) a = authenticate(validate(persist(new Article())));
                else a = validate(persist(new Article()));
                publish(a);
            }
            """, "missing @Authenticated");
        accepts("""
            void run(boolean condition) {
                Article a = condition ? persist(new Article()) : persist(new Article());
                savedOnly(a);
            }
            """);
    }
    @Test void localAnnotationDoesNotManufactureProof() throws Exception {
        rejects("void run() { @Validated Article a = new Article(); }", "missing @Validated");
        rejects("void run() { @Persisted Article a = persist(new Article()); a = new Article(); }", "missing @Persisted");
    }
    @Test void catchesCallsInsideInitializersAndConstructorArguments() throws Exception {
        rejects("Article needs(@Persisted Article a) { return a; } void run() { Article a = needs(new Article()); }", "missing @Persisted");
        rejects("class Sink { Sink(@Persisted Article a) {} } void run() { new Sink(new Article()); }", "missing @Persisted");
        accepts("class Sink { Sink(@Persisted Article a) {} } void run() { new Sink(persist(new Article())); }");
    }
    @Test void checksEveryExpandedVararg() throws Exception {
        rejects("void batch(@Persisted Article... articles) {} void run() { batch(persist(new Article()), new Article()); }", "missing @Persisted");
    }
    @Test void castsCannotForgeLabelsAndNestedScopesDoNotLeak() throws Exception {
        rejects("void run() { savedOnly((@Persisted Article) new Article()); }", "missing @Persisted");
        rejects("void run() { { Article a = persist(new Article()); } { Article a = new Article(); savedOnly(a); } }", "missing @Persisted");
        accepts("void run() { Article a = persist(new Article()); savedOnly(((Article) a)); }");
    }
    @Test void loopCannotSupplyPostLoopProof() throws Exception {
        rejects("void run(boolean c) { Article a = persist(new Article()); while(c) { a = new Article(); } savedOnly(a); }", "missing @Persisted");
    }
    @Test void shortCircuitCannotManufactureProof() throws Exception {
        rejects("void run(boolean c) { Article a = new Article(); if(c && ((a = persist(a)) != null)) {} savedOnly(a); }", "missing @Persisted");
    }
    @Test void independentControlPathsDoNotLeakFacts() throws Exception {
        rejects("void run(int n) { Article a = new Article(); switch(n) { case 0: a = persist(a); break; default: savedOnly(a); } }", "missing @Persisted");
        rejects("void run() { Article a = new Article(); try { a = persist(a); } catch(RuntimeException e) { savedOnly(a); } }", "missing @Persisted");
        rejects("void run(boolean c) { Article a = new Article(); for(; c; a = persist(a)) { savedOnly(a); } }", "missing @Persisted");
    }
    @Test void annotationsAndPreservationSurviveSeparateCompilation() throws Exception {
        Result library = compile(Map.of("lib/Api.java", """
            package lib;
            import org.jsolv.*;
            import java.lang.annotation.*;
            public class Api {
                @StaticTag @Target({ElementType.TYPE_USE,ElementType.PARAMETER,ElementType.METHOD}) @Retention(RetentionPolicy.CLASS)
                public @interface Custom {}
                public static @Persisted Object save(Object o) { return o; }
                @PreservesTags public static @Custom Object check(Object o) { return o; }
                public static void consume(@Persisted @Custom Object o) {}
            }
            """), List.of(), false);
        assertTrue(library.success, library.diagnostics);
        Result app = compile(Map.of("app/App.java", """
            package app;
            import lib.Api;
            class App { void run() { Api.consume(Api.check(Api.save(new Object()))); } }
            """), List.of(), true);
        assertTrue(app.success, app.diagnostics);
        Result bad = compile(Map.of("app/Bad.java", """
            package app;
            import lib.Api;
            class Bad { void run() { Api.consume(Api.check(new Object())); } }
            """), List.of(), true);
        assertFalse(bad.success, bad.diagnostics);
        assertTrue(bad.diagnostics.contains("missing @Persisted"), bad.diagnostics);
    }
    @Test void reportIncludesRequiredKnownAndMissingLabels() throws Exception {
        Path report = temporary.resolve("report.json");
        Result result = compile(Map.of("demo/Cases.java", PRELUDE
                + "void run() { publish(validate(persist(new Article()))); } }"),
                List.of("-Ajsolv.report=" + report), true);
        assertFalse(result.success);
        String json = Files.readString(report);
        assertTrue(json.contains("\"schema\": 2"));
        assertTrue(json.contains("\"accepted\": false"));
        assertTrue(json.contains("\"missing\": [\"demo.Cases.Authenticated\"]"), json);
        assertTrue(json.contains("\"known\": [\"demo.Cases.Validated\", \"org.jsolv.Persisted\"]"), json);
    }
    @Test void addingConfigLabelRequiresNoCheckerChanges() throws Exception {
        Path config = temporary.resolve("jsolv.properties");
        Path output = temporary.resolve("generated");
        Files.writeString(config, "package=app.tags\nlabels=Persisted, Validated, Target, Retention\n");
        LabelGenerator.generate(config, output);
        Files.writeString(config, "package=app.tags\nlabels=Persisted, Validated, Target, Retention, Approved\n");
        LabelGenerator.generate(config, output);
        Map<String,String> sources = new HashMap<>();
        try (var files = Files.walk(output)) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                sources.put(output.relativize(file).toString().replace('\\', '/'), Files.readString(file));
        }
        sources.put("app/App.java", """
            package app;
            import app.tags.*;
            import org.jsolv.PreservesTags;
            class App {
                @Persisted Object save(Object o) { return o; }
                @PreservesTags @Approved Object approve(Object o) { return o; }
                void consume(@Persisted @Approved Object o) {}
                void run() { consume(approve(save(new Object()))); }
            }
            """);
        Result good = compile(sources, List.of(), true);
        assertTrue(good.success, good.diagnostics);
        sources.put("app/App.java", sources.get("app/App.java").replace("consume(approve(save(new Object())))", "consume(save(new Object()))"));
        Result bad = compile(sources, List.of(), true);
        assertFalse(bad.success, bad.diagnostics);
        assertTrue(bad.diagnostics.contains("missing @Approved"), bad.diagnostics);
    }
    @Test void qualifiedIdentityDistinguishesSameSpelling() throws Exception {
        Map<String,String> sources = new HashMap<>();
        String annotation = """
            import java.lang.annotation.*;
            @org.jsolv.StaticTag
            @Target({ElementType.TYPE_USE,ElementType.PARAMETER,ElementType.METHOD})
            @Retention(RetentionPolicy.CLASS)
            public @interface Validated {}
            """;
        sources.put("one/Validated.java", "package one;\n" + annotation);
        sources.put("two/Validated.java", "package two;\n" + annotation);
        sources.put("app/App.java", """
            package app;
            class App {
                @one.Validated Object check(Object o) { return o; }
                void consume(@two.Validated Object o) {}
                void run() { consume(check(new Object())); }
            }
            """);
        Result result = compile(sources, List.of(), true);
        assertFalse(result.success, result.diagnostics);
        assertTrue(result.diagnostics.contains("two.Validated"), result.diagnostics);
    }
}

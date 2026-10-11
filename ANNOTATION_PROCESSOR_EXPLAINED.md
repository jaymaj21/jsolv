# Annotation processor implementation

JSOLV uses `org.jsolv.checker.JsolvProcessor` to check any annotation whose
annotation type is marked `org.jsolv.StaticTag`. The architecture
and label semantics are described in [DESIGN.md](DESIGN.md); complete setup and
examples are in [USAGE.md](USAGE.md).

## Compilation hook

`AbstractProcessor` connects the checker to javac. `Trees.instance` bridges
compiler elements and syntax trees. The checker supports `*` annotation types
so application-defined labels trigger processing, but returns false so other
processors can handle the same annotations.

The service registration is:

```text
checker/src/main/resources/META-INF/services/javax.annotation.processing.Processor
```

Maven places the checker JAR on `annotationProcessorPaths`. The tests install it
explicitly through `JavaCompiler.CompilationTask.setProcessors`.

## Tree walking and flow

`MethodBodyScanner` finds method declarations, validates preservation indices,
and seeds method state with parameter contracts. `FlowScanner` evaluates method
bodies. Its state maps compiler Element symbols to immutable sets of fully
qualified label names; it does not use variable spelling as identity.

The expression evaluator handles nested method/constructor calls, assignments,
casts, parentheses, and conditional expressions. It checks required arguments
and derives return facts from annotated contracts. Ordinary calls establish only
explicit return labels. `@PreservesTags(k)` additionally copies the kth argument's
facts. Local annotations require proof rather than creating it.

`if` branches copy incoming state and join by per-variable intersection.
Short-circuit conditions also join executed/skipped alternatives. Other control
flow uses deliberately conservative treatment; see the design's limitations.

At the final processing round, an optional `jsolv.report` path causes a
schema-2 JSON report to be written. Argument checks record required, known, and
missing labels, the source location, and acceptance. Missing labels produce
`Diagnostic.Kind.ERROR` at the relevant expression.

## Generated annotation lifecycle

Generation is a separate Maven goal in `generate-sources`, not a processor round.
The plugin registers generated Java as a compile source root. Generated annotations
compile normally alongside the app and carry the shared marker in their class
files. The checker needs neither the config path nor a registry of label names.

The tool reactor disables annotation processing while compiling the checker and
its demonstration module. Its tests explicitly run the processor. The separate
application enables normal build-time checking with `proc=full`.

## Reading order

1. `annotations/.../StaticTag.java` and `PreservesTags.java`
2. The application's `jsolv.properties` and `ConfiguredLabelsWorkflow.java`
3. `generator/.../LabelGenerator.java` and `GenerateMojo.java`
4. `demo/.../ConfiguredLabelsTest.java`
5. `checker/.../JsolvProcessor.java`

Use a JDK, not a minimal runtime: annotation processing, Trees, and the tests'
`ToolProvider.getSystemJavaCompiler()` require compiler tooling.

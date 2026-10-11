# JSOLV — Java Static Object Labelling for Verification

JSOLV checks whether ordinary Java values have passed through the workflow
stages their consumers require. An `Article` can be **Persisted**, **Validated**
and **Authenticated** while remaining an ordinary `Article`: no wrapper class
or new nominal type is required for every combination of states.

Application programmers own the label vocabulary. Declare names in a small
configuration file; a Maven plugin generates the annotation classes before
compilation. A generic javac annotation processor follows supported value flows
and reports missing evidence as compiler errors. Adding a label does not require
editing the checker or its support library.

## A workflow expressed in ordinary Java

```java
import com.myapp.tags.*;
import org.jsolv.PreservesTags;

@Persisted
Article persist(Article article) {
    // Perform persistence according to the application's contract.
    return article;
}

@PreservesTags
@Validated
Article validate(Article article) {
    // Validate or throw; every incoming label must remain true on the result.
    return article;
}

@PreservesTags
@Authenticated
Article authenticate(Article article) {
    // Establish the application's authentication guarantee.
    return article;
}

void publish(@Persisted @Validated @Authenticated Article article) {
    // All three facts are required at every checked call site.
}
```

These methods illustrate contracts; their comments stand for real domain
operations, which JSOLV does not generate or prove.

```java
Article draft = new Article("Example");
Article ready = authenticate(validate(persist(draft)));
publish(ready);                        // accepted
publish(validate(persist(draft)));     // rejected: missing Authenticated
publish(draft);                        // rejected: missing all three labels
```

`@PreservesTags` carries the first argument's known labels into the result,
alongside the result's declared labels. It is equivalent to `@PreservesTags(1)`;
`@PreservesTags(2)` selects the second argument. This lets a transformation
retain arbitrary application facts without enumerating their combinations.

## Quick start

Use **JDK 17** and **Maven 3.9.11 or newer**. From the repository root:

```sh
mvn clean install
mvn -f example-app/pom.xml clean compile
java -cp example-app/target/classes org.jsolv.example.Main
```

The first command builds and tests the toolchain and installs its artifacts in
your local Maven repository. The second generates the application's labels and
compiles it with JSOLV enabled. The third runs its illustrative publishing
workflows. Those examples demonstrate contract propagation, rather than
production persistence or authentication.

The example's generated annotations appear in
`example-app/target/generated-sources/jsolv/`; its optional check report is
`example-app/target/jsolv-report.json`. Uncomment one of the marked invalid
calls in the example sources and run `clean compile` to see a compiler error.

The independent application is deliberately outside the root Maven reactor:
install the tools first, then build it as an ordinary consumer.

## Define your own labels

Place `jsolv.properties` beside your application's POM:

```properties
package=com.myapp.tags
labels=Persisted, Validated, Authenticated, Approved
```

Add a name and rebuild to generate its annotation. Names are case-sensitive,
and their qualified package names determine identity. The UTF-8 properties
format supports comments and continuations; invalid identifiers, duplicate
names, missing or unknown keys fail the build.

An application needs the `org.jsolv:jsolv-annotations` dependency, the
`org.jsolv:jsolv-maven-plugin` generation goal, and `org.jsolv:jsolv-checker` on
its annotation-processor path. All currently use version `1.0-SNAPSHOT`.
[USAGE.md](USAGE.md) provides the complete Maven configuration, examples,
report schema and instructions for sharing labels across application modules.
These are development artifacts; this repository does not imply publication
on Maven Central.

## How the checker reasons

- Annotated parameters require every listed label and supply those facts inside
  their method body.
- Annotated returns establish the labels declared by their trusted contract.
- Preservation adds a selected input's known labels to the result.
- Assignments replace the destination's facts with the new value's evidence.
- Branches and conditional expressions retain only facts common to both paths.
- Local annotations impose requirements; they cannot manufacture evidence.
- Cast annotations cannot create missing facts.

Labels are independent positive facts. `Authenticated` does not imply
`Validated`, and a missing label means unknown evidence rather than a proven
negative property. Compiler symbols distinguish variables with the same name
in different scopes. Generated annotations use `@StaticTag` metadata and CLASS
retention, allowing application-owned vocabularies and ordinary contract JARs
without a hard-coded registry in the checker.

## Scope and trust

JSOLV is a lightweight workflow value-flow checker, **not a general Java
verification or security system**. Return annotations and preservation promises
are trusted: domain operations must actually establish or retain those facts.
The checker does not prove their postconditions.

The current analysis does not model arbitrary object mutation through aliases,
method effects, concurrency, general heap state, generic type-argument qualifier
flow or override compatibility. An unannotated mutator does not automatically
invalidate a previously labelled binding. Loops, exceptions, switches and
reachability use restricted conservative approximations. Immutable values and
disciplined snapshot workflows are the clearest fit.

Read [DESIGN.md](DESIGN.md) for the exact supported rules and limitations before
relying on a contract. Ordinary javac compilation with processing disabled does
not enforce JSOLV: keep the configured processor active in the builds that matter.

## Repository layout

| Path | Purpose |
| --- | --- |
| `annotations/` | Shared `@StaticTag`, `@PreservesTags` and bundled `@Persisted` |
| `checker/` | Generic javac processor and processor service registration |
| `generator/` | Maven plugin that generates application annotation classes |
| `demo/` | Toolchain examples and compiler regression tests |
| `example-app/` | Independently built application with its own label configuration |
| `docs/papers/` | LaTeX manuscripts and readable PDF editions |

The toolchain currently has **24 regression tests**, including generator
validation, positive and negative compilation, preservation and separate
compilation. Run `mvn test` for the reactor tests, or `mvn clean install` before
building the independent consumer. Commit configuration and handwritten source;
Maven output and generated annotations are ignored. Use a clean application
build after removing or renaming labels to discard obsolete class files.

## Documentation and papers

- [Usage and complete Maven setup](USAGE.md)
- [Design, philosophy, implementation plan and analysis limits](DESIGN.md)
- [How the annotation processor works](ANNOTATION_PROCESSOR_EXPLAINED.md)
- [Migration from earlier names and the original workspace](MIGRATION.md)
- [JSOLV implementation paper](docs/papers/jsolv.pdf)
  · [LaTeX source](docs/papers/jsolv.tex)
- [Comprehensive synthesis: ideation, JSOLV and ClangSOLV](docs/papers/static_object_labelling.pdf)
  · [LaTeX source](docs/papers/static_object_labelling.tex)
- [Original property-tag proposal](docs/papers/static_property_tags.pdf)
  · [LaTeX source](docs/papers/static_property_tags.tex)
- [Original semantic-category proposal](docs/papers/semantic_roles_beyond_class.pdf)
  · [LaTeX source](docs/papers/semantic_roles_beyond_class.tex)

The synthesis discusses the companion C++ implementation, ClangSOLV; this
repository contains the Java implementation. The original research artifacts
were developed in [jmtools](https://github.com/jaymaj21/jmtools), and historical
workspace paths in the papers identify that provenance.

To rebuild a PDF, use a LaTeX installation with the packages requested by its
source. Run `pdflatex -interaction=nonstopmode -halt-on-error jsolv.tex` twice
from `docs/papers/`; repeat for another manuscript as needed. Curated PDFs are
versioned for readers, while LaTeX intermediate files are ignored.

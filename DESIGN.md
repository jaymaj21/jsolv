# Configurable static tags: intent, philosophy, and design

## Intent

Application teams own their workflow vocabulary: Persisted, Validated,
Authenticated, Approved, or any other meaningful label. Adding a label requires
editing an application configuration file, building, and importing the generated
annotation. It requires no change to the checker or shared annotation library.

The existing Article examples provide the motivating rule: publishing requires
evidence that the value passed through the appropriate workflow steps. Java's
ordinary Article type remains useful throughout that workflow.

## Implementation plan and outcome

1. Separate vocabulary generation from checking with a Maven `generate` goal.
2. Mark generated annotations with a small, stable `@StaticTag` meta-annotation.
3. Replace the hard-coded Persisted boolean with sets of fully qualified labels.
4. Resolve variables by compiler symbols, and evaluate nested calls recursively.
5. Add explicit preservation to compose transformations without enumerating
   combinations of input labels.
6. Exercise generation, positive and negative compilation, compiled library
   boundaries, and the separately built application.
7. Document setup, semantics, trust boundaries, and remaining analysis limits.

These steps are implemented in JSOLV (Java Static Object Labelling for Verification).
The tool uses `org.jsolv` packages and Maven coordinates. The processor is
`org.jsolv.checker.JsolvProcessor`; `org.jsolv.Persisted` remains a bundled
example label. The earlier prototype coordinates and imports must be migrated.

## Philosophy: small vocabulary, explicit contracts

The configuration declares names. Java method signatures declare behavior.
Keeping these responsibilities separate makes contracts visible beside the code
they describe and prevents a second language of method selectors or wildcard
rules from emerging in the configuration file.

Labels are independent facts. A parameter with multiple annotations requires all
of them. There is no implied relationship between names: Authenticated does not
automatically imply Validated, and labels from different packages remain distinct.
The analysis uses fully qualified names; diagnostics also include these identities.

The configuration uses UTF-8 Java properties with exactly two keys. This provides
comments and line continuation without adding a YAML/JSON parsing dependency.
Unknown keys, missing values, duplicate keys/names, empty comma entries, Java
keywords, restricted type names, and invalid identifiers fail the build.

## Architecture

```text
application/jsolv.properties
    -> jsolv-maven-plugin:generate (generate-sources)
    -> target/generated-sources/jsolv/<package>/*.java
    -> javac compiles application and generated annotations
    -> generic processor checks @StaticTag contracts
    -> compiler diagnostics + optional JSON report
```

The generator registers the output as a Maven compile source root. Generation
occurs before compilation, so javac resolves annotations normally. A processor
that both generated the labels and checked application source in its first round
would have to deal with unresolved imports and processing-round ordering; the
separate Maven phase keeps that complexity out of the checker.

Generated annotations have CLASS retention and TYPE_USE, PARAMETER, and METHOD
targets. They use fully qualified support annotations, avoiding import collisions
when an application chooses names such as Target or Retention. The marker and
preservation contract live in the shared `jsolv-annotations` JAR.

METHOD is intentional. Older javac versions do not reliably expose type-use
annotations from class files through the annotation-processing model. Generated
labels also annotate method declarations when placed before a simple return
type. The checker reads both declaration and return-type metadata. This allows
the standard syntax to work across compilation boundaries on the tested JDK 17.
See [OpenJDK's type-annotation visibility fix](https://mail.openjdk.org/pipermail/jdk-updates-dev/2025-March/041891.html).

The checker supports `*` annotation types and returns false from processing, so
it runs for application-defined annotations without claiming other processors'
annotations. It recognizes labels through the meta-annotation, not a package
convention or a copied configuration registry. Application libraries can publish
generated labels and contracts in ordinary JARs.

## Label algebra and propagation

For an expression e, let L(e) be its known set of labels:

| Construct | Known labels |
| --- | --- |
| Fresh object or unsupported value expression | Empty set |
| Local variable or parameter | Current tracked set |
| Parentheses or cast | Operand's set; a cast cannot create proof |
| Ordinary method call | Labels explicitly declared on the return/method |
| `@PreservesTags(k)` method call | Return labels union labels of argument k |
| Local declaration or assignment | Replace the variable's set with the RHS set |
| Conditional expression | Intersection of both alternative values |
| `if` join | Per-variable intersection of both branch states |
| Required parameter | Accept iff required set is a subset of known set |

Each method starts with the labels promised on its parameters. Local annotations
express additional requirements; they do not manufacture facts. Calls in
initializers, assignments, receivers, constructor arguments, and nested calls are
checked. Expanded varargs are checked argument by argument.

State maps compiler Element identities to immutable label sets. Variables with
the same spelling in different scopes therefore do not share state. Assigning an
alias copies value facts; later reassignment of either variable does not change
the other variable's binding.

## Expressive improvement: preservation

Without preservation, `validate(persist(draft))` supplies only Validated unless
validate explicitly declares both return labels. With `@PreservesTags`, it retains
the labels known at that call site and adds Validated. The method can serve values
with arbitrary label combinations without needing one overload per combination.

Preservation selects one input, using a one-based parameter index (default 1).
It deliberately does not union unrelated inputs: preserving a validation fact
from one object onto another object would need a stronger domain-specific
contract. Void returns, invalid indices, and selecting a varargs parameter are
rejected on source declarations.

This is a trusted contract, just like a declared Persisted return. The checker
does not prove the implementation performs persistence, validation, or identity
preservation. For mutable objects, preservation means the output still satisfies
every input fact, a much stronger claim than simply returning the same reference.

Ordinary unannotated methods supply no labels, even if they return their input.
This makes invalidating transformations easy to express: do not promise to
preserve a fact that the transformation may invalidate.

## Generated-file ownership and builds

Generation validates the complete configuration before modifying output. Files
are deterministic, unchanged files keep their timestamps, and obsolete generated
sources are removed using an ownership header. The generator refuses to overwrite
non-generated sources and never recursively deletes an output directory.

Use a dedicated output directory (the default is under target). Commit the config
and Java application sources; generated Java/class files are build artifacts.
After removing or renaming labels, use `mvn clean compile`: javac may otherwise
leave obsolete class files from previous builds even though their sources are gone.

## Analysis boundaries

This remains a lightweight analysis, not a complete Java qualifier type system.
It intentionally provides a useful small set of local value-flow rules:

- Loops, try/catch/finally, and switch statements conservatively forget facts for
  assigned variables and do not establish new post-statement facts. Switch cases
  and exception branches are checked independently. This can reject safe code.
- Early returns, break/continue, thrown exceptions, and reachability do not prune
  branch joins. Switch expressions establish no facts and clear tracked state.
- Fields and array elements supply no known labels. Generic type arguments,
  array component flow, qualifier subtyping, override compatibility, and receiver
  contracts are not modeled. Passing an annotated array as the single varargs
  argument is not modeled; expanded individual values are supported.
- Lambda bodies start a separate state with parameter contracts; captured values
  are unknown. Lambdas/callbacks do not transfer facts to their caller.
- The analysis does not track mutation through aliases, method side effects,
  concurrency, streams, or object identity. In particular, an unannotated mutator
  call does not invalidate a previously tagged binding automatically.
- Annotated returns and preservation are trusted summaries, not obligations
  proved by examining return statements. Fields/initializers outside method
  bodies and method references are outside the current checks.

These limitations mean this is useful build-time checking for disciplined
workflows, not a security proof or a sound analyzer for arbitrary Java programs.

## Future extensions

The architecture leaves room for label implications, selective invalidation,
receiver contracts, and stronger control-flow analysis. These should be added
with precise semantics and tests when an application needs them. The current
independent-label model and single-input preservation avoid unnecessary
configuration while supporting practical combinations today.

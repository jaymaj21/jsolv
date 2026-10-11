# Using configurable static tags

## Build the tools once

Use a JDK 17 and Maven 3.9.11 or newer. From this directory:

```powershell
mvn install
```

This installs the shared annotations, checker, and generator Maven plugin into
your local Maven repository. Application programmers do not rebuild these tools
when adding a label. For a team, publish the three artifacts and parent POM to
your usual Maven repository once.

## Configure the application's vocabulary

Create `jsolv.properties` beside the application POM:

```properties
# Use an application-owned package.
package=com.mycompany.publishing.tags
labels=Persisted, Validated, Authenticated
```

The file is UTF-8. Labels are comma-separated, case-sensitive Java annotation
names. Properties comments and backslash line continuation are supported:

```properties
labels=Persisted, Validated, \
       Authenticated, Approved
```

Adding `Approved` and rebuilding generates `com.mycompany.publishing.tags.Approved`.
No checker registry or library edit is necessary. Use a dedicated package so
generated labels do not collide with existing application types.

## Wire Maven once

Add the annotations dependency:

```xml
<dependency>
  <groupId>org.jsolv</groupId>
  <artifactId>jsolv-annotations</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

Add these plugins under `build/plugins`:

```xml
<plugin>
  <groupId>org.jsolv</groupId>
  <artifactId>jsolv-maven-plugin</artifactId>
  <version>1.0-SNAPSHOT</version>
  <executions>
    <execution><goals><goal>generate</goal></goals></execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.13.0</version>
  <configuration>
    <release>17</release>
    <proc>full</proc>
    <annotationProcessorPaths>
      <path>
        <groupId>org.jsolv</groupId>
        <artifactId>jsolv-checker</artifactId>
        <version>1.0-SNAPSHOT</version>
      </path>
    </annotationProcessorPaths>
    <compilerArgs>
      <arg>-Ajsolv.report=${project.build.directory}/jsolv-report.json</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

The generator goal binds to `generate-sources`. Run the lifecycle (`mvn compile`),
which generates annotations before javac runs. Calling `mvn compiler:compile`
directly skips generation. Reimport the Maven project in your IDE after running
`mvn generate-sources` if generated imports are initially unresolved.

Explicit `proc=full` keeps processor activation clear; the example app uses it too.
See the [Maven Compiler Plugin configuration](https://maven.apache.org/plugins-archives/maven-compiler-plugin-3.13.0/compile-mojo.html).

Optional generator configuration:

```xml
<configuration>
  <configFile>${project.basedir}/config/workflow-tags.properties</configFile>
  <outputDirectory>${project.build.directory}/generated-sources/jsolv</outputDirectory>
</configuration>
```

The config path can also be set with `-Djsolv.config=...`. Use an output
directory dedicated to generated static tags. Generation fails if the config
is missing or invalid; a typo does not silently disable checking.

## Establish, require, and compose facts

Import your generated annotations, plus the shared preservation contract:

```java
import com.mycompany.publishing.tags.*;
import org.jsolv.PreservesTags;

public @Persisted Article persist(Article article) {
    // Perform persistence, then return the saved value.
    return article;
}

@PreservesTags
public @Validated Article validate(Article article) {
    // Check the article or throw; every existing fact must remain valid.
    return article;
}

@PreservesTags
public @Authenticated Article authenticate(Article article) {
    // Establish the domain's authentication guarantee.
    return article;
}

public void publish(@Persisted @Validated @Authenticated Article article) {
    // All three facts are required at each checked call site.
}
```

These are illustrative contracts: implement the actual domain behavior before
promising the tags. The checker trusts annotated returns and preservation.

```java
Article draft = new Article("example");
Article ready = authenticate(validate(persist(draft)));
publish(ready);                         // accepted
publish(validate(persist(draft)));      // error: missing Authenticated
publish(draft);                         // error: missing all three
```

An ordinary method loses facts unless its return declares them:

```java
Article edited = modify(ready); // unannotated return supplies no labels
publish(edited);                // rejected
```

To preserve the second input's tags, write `@PreservesTags(2)`. The index is
one-based. Return labels are added to that input's known label set. Preservation
does not require that input to already have any particular label.

For a method that both requires and preserves a particular fact:

```java
@PreservesTags
public @Validated Article validateSaved(@Persisted Article article) {
    return article;
}
```

`validateSaved(draft)` is rejected. Passing a persisted value yields Persisted
and Validated, plus any other preserved input facts.

Parameter contracts are available inside a method, so forwarding works naturally:

```java
void forward(@Persisted @Validated @Authenticated Article ready) {
    publish(ready);
}
```

Local annotations require actual evidence; they are not an escape hatch:

```java
@Persisted Article saved = persist(draft); // accepted
@Persisted Article forged = draft;        // rejected
publish((@Persisted Article) draft);      // rejected; casts establish no facts
```

For branches, only common facts survive. A missing else branch counts as keeping
the incoming facts. Use ordinary simple return types with annotations before
them (`public @Validated Article ...`) for contracts shared through compiled
JARs; old javac versions may lose purely type-use metadata.

## Run the complete example

From the repository root:

```powershell
mvn -f pom.xml install
mvn -f example-app/pom.xml clean compile
```

The app contains `ConfiguredLabelsWorkflow` demonstrating all three generated
labels and preservation, and `PublishingWorkflow` retaining the original demo.
Uncomment a marked bad call to see compilation fail with missing labels.

Generated sources live under `target/generated-sources/jsolv` and the
report is `target/jsolv-report.json`. The report uses schema 2:

```json
{
  "source": "file:///.../ConfiguredLabelsWorkflow.java",
  "line": 25,
  "column": 17,
  "method": "publish",
  "argumentIndex": 1,
  "required": ["com.mycompany.publishing.tags.Persisted"],
  "known": [],
  "missing": ["com.mycompany.publishing.tags.Persisted"],
  "accepted": false
}
```

Entries appear inside the top-level `checks` array, alongside `schema` and
`processor`. The report describes required-argument checks, including rejected
calls, and is written at the end of annotation processing. It is not an inventory
of every expression or diagnostic. Invalid Java source can prevent processing
from reaching the report stage. Omitting `-Ajsolv.report` disables reporting.
An up-to-date Maven compile may skip javac and retain the previous report; use
`clean compile` when a fresh report is required.

## Share a vocabulary across modules

Put the config and generator execution in an ordinary application-owned labels
JAR module. Depend on `jsolv-annotations` there, and make other modules depend
on that JAR. Consumers use the checker on their processor path; they do not need
their own config for those labels. CLASS retention preserves the marker and
contracts in compiled artifacts. Do not generate the same fully qualified labels
independently in multiple modules.

Commit configuration and handwritten sources. Do not commit generated target
output. When deleting or renaming labels, use `mvn clean compile` to remove stale
class files. See [DESIGN.md](DESIGN.md) for the analysis limits and trust model.

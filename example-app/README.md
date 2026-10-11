# JSOLV example application

This independent application owns its labels in [jsolv.properties](jsolv.properties).
Add a name there and rebuild; annotation classes are generated automatically.
The checker and library need no changes.

From this directory, install the tools once, then compile:

```powershell
mvn -f ../pom.xml install
mvn clean compile
java -cp target/classes org.jsolv.example.Main
```

Maven runs `jsolv-maven-plugin:generate` in `generate-sources`, compiles the
application with the generic checker, and writes `target/jsolv-report.json`.
Generated annotations are under `target/generated-sources/jsolv`.

`ConfiguredLabelsWorkflow` combines Persisted, Validated, and Authenticated using
`@PreservesTags`. `PublishingWorkflow` retains the original persistence example.
Uncomment a marked bad call to see a compiler error with missing labels.

- [Full usage and Maven setup](../USAGE.md)
- [Design, philosophy, and analysis limitations](../DESIGN.md)

Commit config and application code, not target output. Use `clean compile` after
removing labels or when requesting a fresh report. If multiple modules share a
vocabulary, generate the annotations once in a shared application-owned JAR.

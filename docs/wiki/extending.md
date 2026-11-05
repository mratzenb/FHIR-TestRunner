# Extending the runner

[Home](README.md) › Extending the runner

Common changes and where they go. Each recipe lists the files to touch. When you change a documented behaviour,
also update the reference document, the ADR and the matching skill in `.claude/skills/`.

## Add a configuration property

1. Add a component to the right record in `core.config` (e.g. `ExecutionConfig`).
2. Set its default in the compact constructor.
3. If a value can be invalid, add a check to `RunConfiguration.problems()`.
4. Add it to the reference in [configuration.md](../configuration.md#reference).

Spring binds it automatically (`testrunner.execution.<name>`). No Spring code changes.

## Add an assertion type

The FHIR assert types are fixed by the specification, so this is rare. More likely you make an unsupported one work.

1. `core.model.Check` – add a record (or use the existing one).
2. `fhir.hapi.TestScriptMapper.assertion` – read the element.
3. `core.engine.ActionRules` – its default and allowed operators and its name. These are checked before anything
   is sent.
4. `core.engine.AssertionEvaluator` – evaluate it. Throw `AssertionFailure` for `fail`, `TestScriptException`
   for `error`.
5. The compiler now shows every exhaustive `switch` over `Check` that needs a case (`Assertion.withValues`,
   `ActionRules`, `Describe`).
6. Tests in `AssertionsTest`; a wrong operator in `ChecksBeforeSendingTest`.

## Add a report format

No change in the runner needed. Implement `ReportWriter`. A complete one, a CSV line per TestScript:

```java
public final class CsvReportWriter implements ReportWriter {

    @Override
    public String format() {
        return "csv";                                   // the name in testrunner.reporting.formats
    }

    @Override
    public Path write(RunResult result, Path runDirectory) throws IOException {
        StringBuilder csv = new StringBuilder("index;suite;name;outcome;durationMs\n");
        for (TestScriptResult testScript : result.testScripts()) {   // in discovery order
            csv.append(testScript.index()).append(';').append(testScript.suite()).append(';')
                    .append(testScript.name()).append(';').append(testScript.outcome()).append(';')
                    .append(testScript.duration().toMillis()).append('\n');
        }
        return Files.writeString(runDirectory.resolve("result.csv"), csv);
    }
}
```

* **With Spring:** declare it as a bean. Select it with `testrunner.reporting.formats=junit,csv`.
* **Without Spring:** pass it in the `TestRunner` constructor, next to `TestRunners.defaultReportWriters(…)`.

A writer that throws does not stop the run: the error is logged and the other formats are written. Anything taken
from a response, such as a message, is untrusted text – escape it for HTML or XML.

## Replace the HTTP transport

Two common cases need no code:

* A fixed token: `testrunner.fhir-server.headers`.
* An OAuth2 server: `testrunner.fhir-server.oauth2`, see
  [configuration.md › Servers protected by OAuth2](../configuration.md#servers-protected-by-oauth2).

For anything else, e.g. a proxy or mutual TLS, declare an `HttpTransport` bean. It is a functional interface:
one method, `FhirResponse send(FhirRequest)`. Wrapping the default one adds a header to every request:

```java
@Bean
HttpTransport httpTransport(RunConfiguration configuration) {
    JdkHttpTransport delegate = new JdkHttpTransport(configuration.http());
    return request -> delegate.send(new FhirRequest(request.method(), request.uri(),
            request.headers().with("X-Correlation-Id", UUID.randomUUID().toString()), request.body()));
}
```

`FhirHttpClient` writes the communication log from the request it hands to the transport, so a header added inside
the transport is sent but **not** in `communication/`. For a header that should be logged, use
`fhir-server.headers` instead.

## React to run events

Implement `RunListener` (all methods have defaults) and declare it as a bean. `testScriptFinished` may be called from
several threads at once. A listener that prints each result as soon as it is known:

```java
@Bean
RunListener progress() {
    return new RunListener() {
        @Override
        public void testScriptFinished(RunContext run, TestScriptResult result) {
            System.out.printf("%s %s (%d ms)%n", result.outcome(), result.name(), result.duration().toMillis());
        }
    };
}
```

## Replace the validator

Implement `ProfileValidator` and declare it as a bean. Rules:

* Thread-safe.
* Throw `ValidatorUnavailableException` when you could not validate. **Never** return a valid result then.
* If you hold processes or connections, implement `AutoCloseable`.

An in-process validator (`org.hl7.fhir.validation`) would fit here. Why it was not chosen so far:
[ADR-0006](../adr/0006-validator-cli.md).

## Add a FHIR version (R6)

Needs a HAPI FHIR release with `hapi-fhir-structures-r6`. HAPI 8.12.1, the version used now, does not have it.

```mermaid
flowchart LR
    a["raise hapi-fhir.version<br/>in the root pom"] --> b["copy testrunner-fhir-r5<br/>→ testrunner-fhir-r6"]
    b --> c["R6Support: FhirVersion.R6,<br/>FhirContext.forR6(), R6Dialect"]
    c --> d["register in META-INF/services"]
    d --> e["read the R6 TestScript diff;<br/>semantic changes → TestScriptDialect"]
    e --> f["add module to root pom<br/>and testrunner-app"]
    f --> g["testdata/r6 + R6SupportTest"]
```

Full recipe: [fhir-versions.md](../fhir-versions.md#adding-r6).

## Execute a new TestScript element

1. Add it to the neutral model in `core.model`.
2. Read it in `TestScriptMapper` – once, for all versions.
3. If its meaning differs between versions, add a method to `TestScriptDialect` with a default, and override it in
   the version that differs.
4. Use it in the engine.

## Add a test double

See [test-support](modules/testrunner-test-support.md#adding-a-double).

## Checklist before you push

* `mvn verify` is green. A compiler warning already fails it.
* `ArchitectureTest` still passes – no Spring or HAPI in the core.
* The reference doc, the ADR (if a decision changed) and the skill are updated.

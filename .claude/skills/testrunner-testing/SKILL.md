---
name: testrunner-testing
description: How tests are written in the FHIR TestScript Runner (TestRunnerOnJava) – test levels, which test class covers what, the shared test doubles of testrunner-test-support (InMemoryFhirServer, FakeValidatorJar/FakeValidator, SampleData) and the core Harness/Scripts builders, the test data folders, and conventions. Use when writing, fixing or locating tests, adding a test double, or changing testdata/.
---

# Testing (ADR-0009)

No FHIR server, no network, no real validator needed: `mvn verify` runs all tests in under a minute
(build commands: `testrunner-build-and-run`). With Docker running, `HapiServerIT` also runs (+~40 s); without, it
is skipped.

## Levels and where things are tested

| Level | Class | Covers |
|---|---|---|
| Unit, engine (`testrunner-core`, `core/engine/`, all extend `EngineTestBase`) | `FixturesTest` (autocreate/autodelete), `OperationsTest`, `AssertionsTest` (checks, fail vs. error, unsupported checks), `CapabilitiesTest`, `ReferenceRewritingTest`, `KeptRequestsTest` (`requestId`, `direction = request`), `ChecksBeforeSendingTest` (Load errors and warnings before anything is sent), `CheckModeTest` (`TestRunner.check`: Load phase only, nothing sent, no TestReports, validator only when validating), `SelectionTest` (`select.test-scripts`/`tests`, globs, left-out hint, `TestRunner.list`), `PhasesTest`, `PlaceholdersTest` (variables, placeholders in fixtures, `${testrunner.…}`), `TestRunnerTest` (serial suites, parallel runs, package suites, validator configuration problems), `RunControlTest` (timeouts, fail-fast, cancel, reports after an error or `reportNow`), `RunFolderTest` (communication log, masking, reports), `OAuth2Test` | the engine through the real `TestRunner` with `Harness` |
| Unit, core (`testrunner-core`) | `RunConfigurationTest`, `AccessTokensTest` (OAuth2 token fetch/renewal with a `MutableClock`), `PathEvaluatorTest`, `ResourceContentTest`, `FhirElementTest`, `UrlEncodingTest`, `CommunicationLogTest`, `ExampleInstanceResolverTest`, `PackageSuitesTest` (cache, download of a hand-built `.tgz` incl. pax long names, concurrent download, path traversal), `TestReportWriterTest`, `HtmlReportWriterTest` (escaping of hostile text, links into `communication/`) | config defaults/problems, content helpers (`FhirElement`: JSON/XML alike, exact decimals, `minimumId` containment rules), log format |
| Version modules (`testrunner-fhir-r4`, `-r5`) | `R4SupportTest`, `R5SupportTest` | every sample TestScript of the version maps exactly; detection cases |
| Validator (`testrunner-validation-cli`) | `ValidatorCliProfileValidatorTest`, `ValidatorServerProfileValidatorTest` | process handling, arguments, server lifecycle, fallback, parallel use |
| Spring (`testrunner-spring-boot-autoconfigure`) | `TestRunnerAutoConfigurationTest` (`ApplicationContextRunner`) | binding, defaults, replacing/adding beans |
| App (`testrunner-app`) | `TestRunnerApplicationTest`, `RunCommandTest`; `EndToEndIT`, `ShutdownIT` (failsafe) | CLI arguments, exit codes per `exit-on-skip`, `RunCommand.stop`; `ShutdownIT`: the app in a JVM of its own (`java -cp <test class path> TestRunnerApplication`) against a slow server, SIGTERM ⇒ teardown + `junit.xml`, grace time over ⇒ reports with "Did not end"; whole app on the PoC samples with in-memory server + fake validator, real exit code 3, contained fixtures in R4/R5; the wiki samples (`wikiSampleSuitePasses` with `docs/wiki/samples/first-suite.yml`, `wikiMistakesAreReportedBeforeAnythingIsSent` – the Load messages quoted on `docs/wiki/samples.md`, `wikiSamplesAreCheckedWithoutAServer` – its `--check` commands, `wikiSampleTestsAreSelectedByName` – its `--test` example); `--check` on the samples without a server (`checkModeNeedsNoServerAndExitsWithOneForBrokenTestScripts`: exit 1, `check_…` folder, no `testreports/`); selection (`selectedTestScriptsAndTestsRunTheOthersAreLeftOut`) and `--list` on stdout (`listPrintsWhatWouldRunAndSendsNothing`) |
| Architecture (`testrunner-app`) | `ArchitectureTest` (ArchUnit) | module boundaries |
| Real validator (`testrunner-app`) | `RealValidatorIT` (failsafe, only with env `VALIDATOR_JAR`) | AT Core R4 incl. negative control, both modes |
| Real FHIR server (`testrunner-app`) | `HapiServerIT` (failsafe, Testcontainers, `@Testcontainers(disabledWithoutDocker = true)`) | AT Core R4 + R5 and every sample against `hapiproject/hapi:v8.12.0-1` (two static `@Container`s, R4 and R5, started once per class); a conditional update creates, then updates the same resource. Validation off |

**`HapiServerIT` details.** Containers: `hapi.fhir.enforce_referential_integrity_on_write=false`,
`…_on_delete=false`, `allow_multiple_delete=true` (as the PoC's compose file; with HAPI defaults the samples' deletes
get `409`). Wait strategy `GET /fhir/metadata` 200, 3 min. The image tag is pinned to the HAPI release matching
`hapi-fhir.version`; raise both together. Expected sample outcomes are the same as in `EndToEndIT`
(`stopTestOnFail` sample FAIL in `Test02`, `validateProfile` and `delete` samples ERROR). Docker socket on macOS with
Rancher Desktop: `DOCKER_HOST=unix://$HOME/.rd/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
HAPI ignores the body id of a conditional update, so D1-like request bugs need the in-memory server's stricter
answers (`InMemoryFhirServer` returns 400 for a differing body id).

Naming: unit tests `*Test` (surefire), integration tests `*IT` (failsafe). Test methods describe behaviour
(`crashedServerFallsBackToOneProcessPerValidationAndIsNotRestarted`). JUnit 5 + AssertJ; `@TempDir` for files.

## Shared test doubles – `testrunner-test-support`

Test code in `src/test/java` (`at.hl7.testing.runner.testsupport`), shared as test-jar; dependency snippet in
`testrunner-build-and-run`. Its own dependencies (Jackson, Jetty) are in **compile** scope: Maven passes a
test-jar's compile dependencies on to its users (in their test scope), but not its test-scoped ones.
Only Java SE + Jetty: no `com.sun.net.httpserver` (the compiler rejects it).

* **`InMemoryFhirServer`** – Jetty on `127.0.0.1`, free port, base `…/fhir`. `start("4.0.1")` (version for the
  CapabilityStatement, which announces read/vread/update/delete/history/create/search-type for Patient and
  Organization and transaction/batch), `baseUrl()`, `close()` (AutoCloseable). Supports create (201 + Location/ETag), read, vread,
  update, delete, history, crude search (all values contained in the JSON, a token `system|value` split into both;
  `_`-params ignored), conditional delete,
  conditional update (`PUT [type]?query`: 201 no match, 200 one match, 400 other body id, 412 several matches), `delay(Duration)` (every answer after a pause, for timeouts),
  `metadata`, batch/transaction (empty response Bundle), XML create (id rewritten, not stored). Hooks:
  `on(method, path, Answer.json(status, body))` fixes an answer; `requests()` (method, path, query, headers, body;
  `header(name)`), `requestLines()` (`"POST /Patient"`), `resource("Patient/1")`.
* **`FakeValidatorJar`** – `create(dir)` writes `dir/validator_cli.jar`: a manifest-only jar, `Main-Class`
  `FakeValidator`, `Class-Path` = absolute URLs of the test classes, Jetty and SLF4J (`FakeValidator` needs no
  Jackson; valid on this machine, per test run).
  The adapters start it with `java -jar` like the real one. `FakeValidator` behaviour:
  * Markers are constants of `FakeValidatorJar` (`INVALID_MARKER` = `"FAKE-VALIDATOR-INVALID"`, …); put them
    anywhere in the resource text, e.g. `"{\"resourceType\":\"Patient\",\"x\":\"" + FakeValidatorJar.INVALID_MARKER + "\"}"`.
  * process mode: writes an OperationOutcome to `-output` – one error per `INVALID_MARKER` in the file, else
    "All OK"; `SILENT_MARKER` ⇒ writes nothing, exit 1.
  * `server <port>`: `POST /validateResource[?profile=]` same outcomes; `CRASH_MARKER` ⇒ process halts;
    `HANG_MARKER` ⇒ never answers (hanging server);
    profile `UNRESOLVABLE_PROFILE` ⇒ HTTP 500; `POST /stop` ⇒ exits.
  * Settings (system property via `validation.jvm-arguments` `-DNAME=…`, or env): `FAKE_VALIDATOR_LOG=<file>`
    (one line per `process <profile>`, `start <args>`, `validate <profile> <content-type>`, `stop` – count calls; a
    start that fails via `FAKE_VALIDATOR_SERVER_FAILS` writes no `start` line), `FAKE_VALIDATOR_SERVER_FAILS` (exit 3
    at start), `FAKE_VALIDATOR_STARTUP_MILLIS` (slow start). In tests the JVM arguments go in through the
    `ValidationConfig` constructor (`jvmArguments`); see `ValidatorServerProfileValidatorTest.config(...)`.
* **`SampleData`** – finds `testdata/` by walking up from the working directory: `r4()`, `r5()`, `samples()`,
  `testScript(suite, fileName)`; `wikiSamples()` = `docs/wiki/samples/` (the tested samples of the wiki page
  `docs/wiki/samples.md`: `first-suite/`, `mistakes/`, `first-suite.yml`). Changing one means changing the page.

New shared double: put it into `testrunner-test-support/src/test/java/at/hl7/testing/runner/testsupport/`, its
dependencies into that POM in compile scope. A double used by one module only stays in that module's `src/test`.

## Core engine tests – `Harness` and `Scripts` (`testrunner-core/src/test/java/at/hl7/testing/runner/core/engine/`)

`StubVersionSupport` hands prepared models to the real `TestRunner` (no HAPI in core tests). An engine test class
extends `EngineTestBase`: a fresh `InMemoryFhirServer` (`server`) and `Harness` (`harness`) per test in a `@TempDir`
(`dir`), the resources `PATIENT` and `ORGANIZATION`, and `phase(result, name)`. Add a test to the class of its
concern; a new concern gets a class of its own. Pattern inside such a class:

```java
class FixturesTest extends EngineTestBase {

    @Test
    void autocreatesDependenciesFirstAndRewritesReferences() {
        harness.resource("patient", PATIENT)                   // Example_Instances file of fixture "patient"
                .add("ts", script().fixture("patient", true, false)
                        .test(test("t", op("read", "Patient", null, "patient", null, null), responseCode("200")))
                        .build());

        TestScriptResult result = harness.runOne();            // or run() for the RunResult

        assertThat(phase(result, "t").outcome()).isEqualTo(Outcome.PASS);
        assertThat(server.requestLines()).startsWith("POST /Patient");
    }
}
```

`Scripts`: `script()` builder (`destination`, `requires(canonical)`, `fixture`, `profile`, `variable`, `setup`,
`test`, `teardown`), `op(...)`, `check(Check[, Operator, value])`, `responseCode`, `warningOnly`, `stopTestOnFail`,
`on(assertion, sourceId)`, `onRequest(assertion)` (`direction = request`), `withHeaders`, `withRequestId`,
`toDestination`, `test(name, actions...)`. `Harness`: `resource(fixtureId, json)`, `exampleInstance(fileName, json)`
(e.g. a required CapabilityStatement), `validator(ProfileValidator)`, `parallelism(n)`, `oauth2(OAuth2Config)`,
`rewriteReferences(ReferenceRewriting)`, `limits(timeout, testScriptTimeout, failFast)`, `control(RunControl)`, `failingOn(name, Error)` (loading that TestScript throws), `listener(RunListener)`, `noServer()`, `validateTestScripts()` (the `validator` checks each TestScript), `formats(...)` (default junit, summary, json), `select(testScripts, tests)`; `run()`/`runOne()`, `check()` or `list()`. Each call gets a new `RunControl` unless `control(…)` was set for it (used once). `Scripts.script().named(name, url)` sets `TestScript.name`/`url` (default `TS`, `…/TestScript/ts`). `StubVersionSupport` throws `TestScriptFormatException` for content that is no prepared model (`harness.add(name, null)` = an unreadable file). A TestScript must pass `TestScriptChecks` to reach the server: an assertion needs an operation before
it, ids must name something, operators must fit their check, an operation must have what it needs to be built
(`ActionRules`) – otherwise the result is a Load error (that is what `ChecksBeforeSendingTest` tests). `Scripts.noEncoding(op)` sets
`encodeRequestUrl = false`.

## Test data (`testdata/`, from the PoC)

`r4/` AT Core R4 create, update, **negative control** (must fail); `r5/` AT Core R5 create, update; `samples/` 9
TestScripts covering every operation type and many assertions (incl. `minimumId` on JSON and XML and
`navigationLinks`), mostly R4, `TestScript-testscript-delete.json` is R5
(and lacks its Example Instance – an expected error); `TestScript-testscript-validateProfile.json` uses an undeclared
profile id (`test1234`) – a Load error since `TestScriptChecks` checks profiles. Each has `Test_Scripts/`, `Example_Instances/`, `Profiles/`.
`EndToEndIT.sampleScriptsRunWithTheExpectedOutcomes` pins the expected outcome of each sample – update it when
changing samples.

# Next steps

[Home](README.md) › Next steps

An architecture review of the runner as of 2026-11-05 (commit `58ee953`), the features worth building next, and a
plan for both. The review covered every module. `mvn verify` was green with 192 tests.

Every item has an id, so that tickets, commits and ADRs can refer to it: **D** = defect, **A** = architecture
finding, **F** = feature.

## Summary

The architecture is sound and needs no structural change. The core is framework-free and the build enforces its
boundaries. Every piece of state has an owner, results are immutable, `fail` and `error` are kept apart, and every
path to a silent pass is closed. The weak spots are smaller:

1. **Two defects.** A conditional update sends a wrong resource id (D1). An assertion whose operator does not fit
   its check is reported only after requests have changed server data (D2).
2. **Validation rules live in three places.** The evaluator, the request factory and the pre-run checks each know
   part of what makes a TestScript valid. That is the cause of D2 (A1).
3. **A run cannot be bounded or cancelled.** If a run is killed or crashes, it writes no reports. This hurts most in
   CI (A2).
4. **No CI pipeline and no test against a real FHIR server.** The in-memory server knows only a little FHIR: it
   ignores search semantics and did not answer conditional updates at all (A3, A4).

The feature with the best value for its effort is a **check mode** (F1). It loads and checks TestScripts without a
server. Every check it needs already exists. TestScript authors, and the TinkerTool that writes TestScripts, would
get feedback in seconds.

## What should stay

Keep these properties. Any change below has to preserve them.

| Property | Where it is enforced |
|---|---|
| Framework-free core, adapters as modules | `ArchitectureTest`, `--limit-modules java.se`, enforcer |
| No global state; run state in `RunContext`, TestScript state in `ExecutionState` | [ADR-0004](../adr/0004-no-global-state.md), code review |
| One generic mapper plus a tiny dialect per FHIR version | [ADR-0005](../adr/0005-fhir-version-mapping.md) |
| Typed checks: sealed `Check` with exhaustive switches | the compiler |
| Never a silent pass; `fail` ≠ `error` | [Design insights §7, §8](design-insights.md#7-never-a-silent-pass) |
| Everything that fails in every run is reported before anything is sent | `TestScriptChecks` (but see D2) |
| Every exchange traceable by request id | `FhirHttpClient`, `CommunicationLog` |

## Defects

Both were reproduced with a probe test against `InMemoryFhirServer`. The probe test was deleted afterwards.

### D1 – Conditional update sends the resource type as id

**Status:** fixed. `RequestFactory` sets the body id only for an instance URL; test
`TestScriptExecutionTest.Operations.conditionalUpdateSendsTheFixtureAsWritten`.

**What happened.** An `update` with `params: "?identifier=…"` is a conditional update (`PUT [base]/[type]?[search]`).
The runner sends it with the body's `id` replaced by `Patient`.

**Why.** `RequestFactory.create` sets the body id to the last path segment of the URL for every update. It does this
so that `PUT [type]/[id]` and the body agree. In a conditional update the last segment is the resource type.

**Effect.** The specification is ambiguous here. One rule says an id in the body that differs from the matched
resource is a `400 Bad Request`; another says the server shall ignore the id. A server that follows the first rule
rejects the request, so the TestScript fails for a reason that is not the server's fault. HAPI FHIR 8.12 follows the
second: it ignores the id, answers `201` or `200`, and the defect is invisible there (checked with
`hapiproject/hapi:v8.12.0-1`, also with the old code). Either way the runner sent something the TestScript did not
say.

**Fix.** Only set the id when the URL is an instance URL: the path after `[base]` is exactly `[type]/[id]`, and
`[type]` is the resource type of the body. Otherwise send the body unchanged.

### D2 – Operator and operation mistakes are found after requests were sent

**Status:** fixed together with A1. `ActionRules` holds the rules; `TestScriptChecks` applies them before anything
is sent. Tests: `TestScriptExecutionTest.Checks` (`operatorTheCheckDoesNotAllowIsFoundBeforeTheSetup`,
`operatorWithoutTheValueItNeeds`, `operationsThatCannotBeBuilt`, `operationsWhoseValuesAreOnlyKnownWhenTheyRunAreAccepted`).

**What happened.** An assertion with an operator its check does not allow, such as `contentType` with `greaterThan`,
becomes an `error` only when the assertion runs. By then the setup has created fixtures and earlier operations have
changed server data. The probe showed Setup `pass`, one request sent, Test `error`.

The same applies to operations that can never be built:

* `create` or `search` with `targetId`
* `create`, `transaction` or `batch` with `params`
* an unknown operation type without `method`
* no `url`, `params`, type or resource
* an operator that needs a value (`equals`, `in`, …) without `value` or `compareToSourceId`

**Why it matters.** The docs promise that everything that fails in every run is reported before anything is sent
([How a run works › Checks before sending](run-lifecycle.md#6-checks-before-sending)). These cases break that
promise.

**Fix.** See A1.

## Architecture findings

| Id | Finding | Impact | Effort |
|---|---|---|---|
| A1 | Validation rules are spread over `AssertionEvaluator`, `RequestFactory` and `TestScriptChecks` | high – cause of D2 | M |
| A2 | A run cannot be bounded, cancelled or partially reported (done, M2.3/M2.4) | high – CI | M |
| A3 | No CI pipeline; build gates only run locally | medium | S |
| A4 | No test against a real FHIR server | medium | M |
| A5 | `TestRunner` does too much; `TestScriptExecution` takes 10 constructor parameters (done, M2.1) | medium | M |
| A6 | Wide positional records: `Operation` has 17 components (done, M5.1) | medium | M |
| A7 | Assertion values are compared as text | medium | M |
| A8 | Request and response bodies are `String`s | medium | M |
| A9 | The public API of the core is undefined; `RunContext` hands mutable run state to listeners (done, M2.5) | medium | S |
| A10 | Package download: new `HttpClient` per download, no proxy, no integrity check | low | S |
| A11 | `TestRunner` filters configuration problems by property-name prefix (done, M2.2) | low | S |
| A12 | A body is parsed again for every assertion and variable | low | S |

Effort: S ≤ 1 day, M 2–5 days, L more than a week.

### A1 – One place for the rules

**Status:** done, as `core.engine.ActionRules`. The run-time checks stay in `AssertionEvaluator` and
`RequestFactory` for values that are only known when an action runs.

The allowed operators of each check exist only as arguments of `AssertionEvaluator.operator(...)`. The shape rules
of operations exist only in `RequestFactory.check`, `method` and `url`. `TestScriptChecks` cannot reuse them, so it
does not check them.

**Recommendation.** Add one rule class in `core.engine`, for example `ActionRules`:

* `Operator defaultOperator(Check)` and `Set<Operator> allowedOperators(Check)` – a sealed switch, so a new check
  does not compile without its rules.
* `List<String> problems(Operation)` – the parts of `RequestFactory`'s checks that do not depend on values at run
  time. A field containing `${` is skipped, as `TestScriptChecks` already does for ids.

`AssertionEvaluator`, `RequestFactory` and `TestScriptChecks` all use it. The run-time checks stay as a safety net
that should never fire.

### A2 – Run control

**Status:** done – limits in M2.3, reports after an exception or Ctrl-C in M2.4 ([ADR-0013](../adr/0013-run-control.md)).

* No run timeout, no per-TestScript timeout, no fail-fast. A server that answers slowly holds the run for
  `request-timeout` × requests.
* `TestRunner.runAll` waits on its futures without a deadline.
* If anything unexpected is thrown, `TestRunner.run` reports `runFinished` with an empty result and writes **no
  reports**. A CI job that is killed on a timeout leaves no `junit.xml`. The results of the TestScripts that did
  finish are lost.

### A3 – CI and build gates

The remote is GitHub, but there is no workflow. ArchUnit, the enforcer and "no compiler warnings" only protect the
code when someone runs `mvn verify`. Warnings are banned by convention only: `-Xlint:all` is set, `-Werror` is not.
There is no coverage report, no static analysis and no dependency or SBOM scan. Spring Boot plus HAPI is a large
dependency tree.

### A4 – Tests against a real server

[ADR-0009](../adr/0009-testing.md) accepts that the in-memory server is not a FHIR server. That was right for fast,
offline unit tests. But nothing runs the sample suites against a real server automatically, so the samples' claims
about server behaviour (referential integrity, conditional delete with several matches, content types) are never
checked. Two smaller points:

* 71 of the 192 tests sit in one 1,180-line class, `TestScriptExecutionTest`. (Split in M1.5.)
* ADR-0009 still says "~115 tests".

### A5 – Orchestration

**Status:** done in M2.1.

`TestRunner` (376 lines) does seven jobs: configuration problems, package resolution, the run folder, discovery,
serial and parallel scheduling, the wiring of each TestScript (communication log, headers, OAuth2, built-in values),
TestScript validation, and the reports. A2 and F3 add more to exactly this class. Split it first:

* `RunScheduler` – serial suites first, then the pool; deadlines; cancellation.
* `ExecutionFactory` – builds the `FhirHttpClient`, `CommunicationLog` and built-in values of one TestScript.
* `ReportPublisher` – writes the reports and logs the summary.

`TestScriptExecution` should take one `ExecutionEnvironment` record instead of 10 parameters.

### A6 – Wide records

**Status:** done in M5.1, with builders.

`Operation` has 17 positional components, `Assertion` 12, `TestScript` 13 and `SuiteConfig` 11. `new Operation(`
appears at 7 places, and the test builder `Scripts` copies all 17 arguments for every `with…` helper. Every new
element – R6 will bring some – touches all of them. Builders or `with…` methods on the records keep the records
immutable but make the call sites independent of the component order.

### A7 – Text comparison

`Operators.apply` compares strings. `greaterThan` and `lessThan` try numbers and otherwise compare text
lexicographically. So:

* `2026-01-01T10:00:00+01:00` and `2026-01-01T09:00:00Z` are the same instant, but `equals` fails and `greaterThan`
  gives the wrong answer.
* `equals` on `1.0` and `1.00` fails, although they are equal numbers. (For a FHIR decimal this is sometimes wanted,
  because the precision is part of the value.)

FHIRPath already returns typed values. Today `Values.text` throws the types away.

### A8 – Bodies as text

`JdkHttpTransport` decodes every response as UTF-8. `FhirRequest`, `FhirResponse` and `Body` hold `String`s. A
`Binary` read with its native content type – a PDF or an image, as in IHE MHD *Retrieve Document* – is corrupted in
memory and in the communication log.

### A9 – Public API

**Status:** done in M2.5.

The core has no `module-info` and no list of supported types. Everything public is API by accident. In particular,
`RunListener` receives `RunContext`. That is a public record with the run's `AtomicInteger` request counter and the
`ConcurrentMap` of OAuth2 token sources, so a listener can change run state. That contradicts ADR-0004. Hand
listeners a read-only `RunInfo` (run id, start, run folder, configuration) instead. Name the supported packages in an
ArchUnit rule or a `module-info`.

### A10 – Package download

`PackageSuites.httpDownload` creates a `HttpClient` per download and never closes it. It uses neither the proxy nor
the TLS settings that apply to the FHIR server, and it does not verify the tarball. npm-style registries list a
`dist.shasum` per version; check that packages.fhir.org provides it, then verify downloads against it. A package's
TestScripts are sent to servers, so the integrity of the package matters.

### A11 and A12 – Small things

**Status:** A11 done in M2.2.

* `TestRunner.run` removes problems whose text starts with `testrunner.validation.validator-jar` when validation is
  off. The engine knows an adapter's property name and matches on message text. Let the validator adapter report its
  own configuration problems, or make problems records with a property name.
* `HapiFhirPathEvaluator` parses the body and creates a FHIRPath engine for every evaluation. `PathEvaluator` and
  `FhirElement` parse again. Ten assertions on a large search Bundle parse it ten times. Measure first; if it
  matters, cache the parsed forms per response within an `ExecutionState` (not globally).

## Features

| Id | Feature | Value | Effort | Depends on |
|---|---|---|---|---|
| F1 | **Check mode**: load and check TestScripts without a server (done, M3.1) | high | S | A1 |
| F2 | **Select what runs**: by TestScript and test name; list mode (done, M3.2) | high | S | – |
| F3 | **Run control**: run and TestScript timeouts, fail-fast, reports after Ctrl-C (done, M2.3/M2.4) | high | M | A2, A5 |
| F4 | **Container image** and a compose file with HAPI FHIR (done, M4.1) | medium | S | – |
| F5 | **HTML report** linking into `communication/` (done, M4.2) | medium | M | – |
| F6 | **Typed comparisons** for dates, numbers and booleans | medium | M | A7 |
| F7 | **Binary content** in requests and responses | medium | M | A8 |
| F8 | **Several servers**: map `destination` to configured servers | medium | L | A5, ADR |
| F9 | **Packages**: `#current`, transitive dependencies | low | M | A10 |
| F10 | **FHIR R6** | later | M | a HAPI release with `hapi-fhir-structures-r6` |

**F1 – Check mode.** `--check` runs discovery, loading, `TestScriptChecks` and, if enabled, TestScript validation.
It sends nothing and needs no `fhir-server`. It writes the usual summary and JSON report, so other tools can read the
result, and exits with 0 (all valid), 1 (problems) or 2 (configuration). Use cases: TestScript authors, IG CI before
publishing, and the TinkerTool.

**F2 – Select what runs.** `--testscript=<glob>` matches file name, `TestScript.name` or `url`. `--test=<name>`
selects tests; setup and teardown always run. `--list` prints what would run. Today, the only way to select is to
edit `suites[n].include`.

**F3 – Run control.** `execution.timeout`, `execution.test-script-timeout` and `execution.fail-fast`. Deadlines are
checked between actions; a thread is never killed. TestScripts that did not run are `skip` with the reason. Reports
are always written, also after an unexpected exception or Ctrl-C.

**F4 – Container image.** The Python PoC has a Dockerfile and a compose file with HAPI FHIR; the Java runner has
neither. Use `spring-boot:build-image` or a Dockerfile on a Java 25 JRE. The validator jar and the package cache are
volumes, not part of the image.

**F5 – HTML report.** One self-contained page per run: TestScripts, phases, actions, messages, and every request id
as a link to its folder in `communication/`. For readers who do not open `junit.xml`. Opt-in as format `html`.

**F8 – Several servers.** TestScripts with several `destination`s are rejected today
([architecture.md › Open points](../architecture.md#open-points)). Mapping each destination index (or its profile
code) to a configured server would run them. It changes the "one client, one server" model, so it needs an ADR
first.

## Plan

```mermaid
flowchart LR
    m0["M0 Safety net<br/>A3 · A4"] --> m1["M1 Correctness<br/>D1 · D2 · A1"]
    m1 --> m3["M3 Author workflow<br/>F1 · F2"]
    m0 --> m2["M2 Run control<br/>A5 · A2/F3 · A9 · A11"]
    m2 --> m4["M4 Distribution<br/>F4 · F5"]
    m1 --> m5["M5 Engine depth<br/>A6 · A7/F6 · A8/F7 · A12"]
    m2 --> m6["M6 Scope<br/>F8 · F9/A10 · F10"]
    m5 --> m6
```

M0 and M1 come first: they protect everything after them. M2 and M3 are independent and can run in parallel. Each
step below is one pull request, with tests and documentation.

### M0 – Safety net (S)

1. ~~**CI workflow**~~ Done: `.github/workflows/build.yml`, job `verify` – Temurin 25, `mvn -B verify`, Maven cache,
   test reports kept on failure.
2. ~~**Nightly validator job**~~ Done: job `real-validator` – pinned and cached `validator_cli.jar` 7.0.2,
   `RealValidatorIT` in both modes, so the negative control is checked every night.
3. ~~**`-Werror`**~~ Done: `failOnWarning` in the compiler plugin of the root `pom.xml`, so the "no warnings" rule
   of [ADR-0010](../adr/0010-build.md) is enforced.
4. ~~**Reports, not gates**~~ Done: JaCoCo coverage report (aggregated in `testrunner-app`), CycloneDX SBOM of the
   executable jar, both kept by CI; Dependabot for Maven and GitHub Actions. No thresholds.
5. ~~Correct the test count in ADR-0009.~~ Done.

*Done when:* a pull request shows a green build, and a compiler warning turns it red.

### M1 – Correctness (S–M)

1. ~~**D1.**~~ Done: in `RequestFactory.create`, the body id is set only for an instance URL
   (`ResourceContent.parseLocation`, the type equals the body's resource type). `InMemoryFhirServer` answers
   conditional updates.
2. ~~**A1 + D2.**~~ Done: `ActionRules`, used by `AssertionEvaluator`, `RequestFactory` and
   `TestScriptChecks.References.check`. Every case listed under D2 is a `Load` error naming test and action, with
   nothing sent.
3. ~~**Decide** how unsupported checks (`requestMethod`, `requestURL`, `manualEval`) are reported.~~ Done as
   proposed: `error` at run time, as documented, and a warning on the Setup before anything is sent
   (`ActionRules.unsupported`, `TestScriptChecks.unsupportedWarnings`). With `Load` problems, the warnings follow
   them in the `Load` phase. Tests: `AssertionsTest.unsupportedChecksAreAnnouncedOnTheSetupBeforeAnythingIsSent`,
   `ChecksBeforeSendingTest.unsupportedChecksAreWarningsNextToTheProblems`.
4. ~~**A4.**~~ Done: `HapiServerIT` in `testrunner-app`, Testcontainers from the Spring Boot BOM,
   `hapiproject/hapi:v8.12.0-1` for R4 and R5. It runs the AT Core suites, every sample and a conditional update,
   and is skipped without Docker; CI runs it on every push.
5. ~~Split `TestScriptExecutionTest` along its nested classes into one class per concern.~~ Done: twelve classes
   on a common `EngineTestBase` (server, `Harness`, `PATIENT`, `ORGANIZATION`, `phase`), e.g. `OperationsTest`,
   `ChecksBeforeSendingTest`, `TestRunnerTest`; the largest has 243 lines.

*Docs:* [architecture.md › Execution semantics](../architecture.md#execution-semantics),
[How a run works](run-lifecycle.md), skill `testrunner-execution-engine`.
*Done when:* the D2 probe reports a `Load` error and sends nothing, and `HapiServerIT` is green in CI.

### M2 – Run control (M)

1. ~~**A5.**~~ Done: `RunScheduler` (serial suites, thread pool), `ExecutionFactory` (client, communication log,
   headers, OAuth2, `${testrunner.…}` values) and `ReportPublisher` (reports, summary) came out of `TestRunner`;
   `TestScriptExecution` takes an `ExecutionEnvironment` record. No behaviour change, no test changed.
2. ~~**A11.**~~ Done: `ProfileValidator.configurationProblems()` (default none). The validator-cli adapters report
   a missing `validator-jar`; `RunConfiguration.problems()` no longer knows the property and `TestRunner` no longer
   filters messages. Test: `TestRunnerTest.configurationProblemOfTheValidatorStopsTheRunBeforeAnythingIsSent`.
3. ~~**A2 + F3.**~~ Done: `execution.timeout`, `test-script-timeout` and `fail-fast` (defaults none, none, false),
   [ADR-0013](../adr/0013-run-control.md). The cancellation lives in a `RunControl` per run, passed to
   `TestRunner.run(configuration, control)` rather than kept in `RunContext`, so that a caller can hold it before the
   run starts. A `Deadline` is checked before every setup and test action; the teardown always runs. Stopped:
   `error`; not started: `skip`, both with the reason. Tests: `RunControlTest`, with `InMemoryFhirServer.delay`.
4. ~~**Reports always.**~~ Done: `TestRunner.run` writes the reports once whatever ends the run (`RunProgress`); a
   TestScript without a result is `error` "Did not end: …" or `skip` "Not started: …". In the app, `RunCommand`
   stops the run when the context closes on Ctrl-C or SIGTERM (before the transport is destroyed) and waits
   `testrunner.shutdown-grace` (30 s), else `RunControl.reportNow()`. A throwing listener no longer stops the run.
   Tests: `RunControlTest`, `RunCommandTest`, `ShutdownIT` (the app in its own JVM, killed with SIGTERM).
5. ~~**A9.**~~ Done: `RunListener` gets a read-only `RunInfo` (run id, start, configuration, run folder);
   `RunContext` is package-private. `ArchitectureTest.otherModulesUseOnlyThePublicApiOfTheCore` names the public API:
   the packages `model`, `config`, `spi`, `report` and a list of types from `engine` and `http`; `content` and
   `source` are internal. This breaks the listener API (`RunContext` → `RunInfo` in the method signatures).

*Tests:* ~~a delay option for `InMemoryFhirServer`; tests for each timeout, fail-fast, and a `ReportWriter` or
listener that throws.~~ Done: `InMemoryFhirServer.delay`, `RunControlTest`, `ShutdownIT`; a throwing `ReportWriter`
was already covered.
*Docs:* ~~[configuration.md](../configuration.md), an ADR "Run control", skills `testrunner-execution-engine` and
`testrunner-configuration`.~~ Done: [ADR-0013](../adr/0013-run-control.md), configuration.md › Run control, the wiki
pages and skills.
*Done when:* a run with a slow server ends within its timeouts with every TestScript reported, a killed run leaves
`junit.xml`, and no module outside the core uses more than its public API. All three are tested.

### M3 – Author workflow (S–M)

1. ~~**F1.**~~ Done as proposed: `TestRunner.check(RunConfiguration[, RunControl])` runs `runOne` up to the checks
   before sending and returns a `RunResult` with the `Load` phase only (`pass` with the warnings, or `error`), so all
   report writers work unchanged. `--check` in `TestRunnerApplication.translate` maps to `testrunner.mode=check`;
   `RunCommand` picks the method. Beyond the proposal ([ADR-0014](../adr/0014-author-workflow.md)): no FHIR
   TestReports for a check, a run folder `check_<timestamp>`, the validator only needed when it validates the
   TestScripts, fail-fast and the TestScript timeout do not apply. Tests: `CheckModeTest`,
   `EndToEndIT.checkModeNeedsNoServerAndExitsWithOneForBrokenTestScripts`, `wikiSamplesAreCheckedWithoutAServer`.
2. ~~**F2.**~~ Done as proposed: `testrunner.select.test-scripts` and `testrunner.select.tests` (globs, `SelectConfig`
   in `RunConfiguration`; `core.engine.Selection`), with the CLI short forms `--testscript` and `--test`, plus
   `--list` (`TestRunner.list`, printed by `RunCommand`). Deselected tests are not run and not reported, and the log
   says how many were left out. Setup and teardown always run; a selected test needing a response of a test left
   out is a `Load` error naming that test. [ADR-0014](../adr/0014-author-workflow.md). Tests: `SelectionTest`,
   `RunCommandTest.listShowsTheTestScriptsWithTheirSelectedTestsAndTheCounts`, `EndToEndIT`
   (`selectedTestScriptsAndTestsRunTheOthersAreLeftOut`, `listPrintsWhatWouldRunAndSendsNothing`,
   `wikiSampleTestsAreSelectedByName`).

*Tests:* ~~`EndToEndIT` – `--check` on `testdata` exits 1 for the broken script and sends nothing (no server is
started); selection by file name, by TestScript name and by test name.~~ Done, see above.
*Docs:* ~~README quick start, [configuration.md](../configuration.md), [Troubleshooting](troubleshooting.md).~~ Done,
also [ADR-0014](../adr/0014-author-workflow.md), [How a run works](run-lifecycle.md), [Samples](samples.md), the module
pages and the skills.

### M4 – Distribution and reporting (M)

1. ~~**F4.**~~ Done: `testrunner-app/Dockerfile` on `eclipse-temurin:25.0.5_8-jre-noble` (a Dockerfile, not
   `spring-boot:build-image`: no builder images from the internet at build time), the exec jar split into layers,
   user `runner`, working directory `/workspace`. Mounted: the project folder (configuration, suites, results), the
   validator jar, `~/.fhir/packages`. `docker-compose.yml` with HAPI FHIR `v8.12.0-1` and the settings of
   `HapiServerIT`; a `curl` service waits for HAPI (its image has no shell for a health check). CI builds the image
   and checks the AT Core suite with it; it is not published. Dependabot watches the base image.
   [ADR-0015](../adr/0015-container-image.md), [Build and test › Container image](build-and-test.md#container-image).
2. ~~**F5.**~~ Done as proposed: `HtmlReportWriter` in `core.report`, format `html`, `report.html` in the run folder,
   plain Java. Every text is HTML-escaped, the page's Content-Security-Policy allows no script, a TestScript url is
   text, not a link. Request ids link to their folder and files in `communication/` (relative links; looked up by
   `ExchangeFolders`, shared with `TestReportWriter`). A TestScript that did not pass is open, a checkbox hides what
   passed (CSS only). On in the app's defaults and `dev-all.yml`, like `testreport`; also in check mode.

*Tests:* ~~`HtmlReportWriterTest` – escaping of hostile response text, links to existing communication folders.~~
Done, plus `RunFolderTest.htmlReportLinksTheRequestsToTheirCommunicationFolders` and
`EndToEndIT.communicationLogsAndReportsAreWrittenPerRun`.
*Docs:* ~~[logging.md](../logging.md), README.~~ Done, also configuration.md, the wiki pages and the skills.

### M5 – Engine depth (M)

1. ~~**A6**~~ Done: `TestScript.builder(version)`, `Operation.builder()` and `Assertion.builder(check)`, each with
   `toBuilder()` for copies; `withValues` and `withTests` use them, and so do `TestScriptMapper` and the test helpers
   `Scripts`. No `new Operation(`, `new Assertion(` or `new TestScript(` is left outside the builders. The records
   keep their components, so binary users of the accessors see no change. `SuiteConfig` stays positional: Spring's
   binder fills it through its constructor, and it has factories (`of`, `withPackage`).
2. **A7 + F6.** `Values` keeps the type: `BigDecimal`, `Boolean`, date/dateTime with precision and offset, `String`.
   `Operators` compares by type. Instants are compared after normalising the offset; values of different precision
   are not comparable, which is a `fail` with a clear message, as in FHIRPath. This changes results of existing
   TestScripts: decide between a switch and a documented change.
3. **A8 + F7.** `Body` holds bytes plus a charset and decodes text on demand. `JdkHttpTransport` reads bytes. The
   communication log writes a non-text body as a file of its own next to `response.http`.
4. **A12**, if a measurement with a large Bundle shows it is needed.

*Docs:* [Variables](variables.md), [architecture.md](../architecture.md), skill `testrunner-execution-engine`.

### M6 – Scope (L)

1. **F8.** ADR first: how `destination` indexes or profile codes map to `suites[n].fhir-servers`, and what happens
   to `origin`. Then `TestScriptChecks` accepts several destinations if each is mapped, and `ExecutionFactory`
   builds one `FhirHttpClient` per destination.
2. **F9 + A10.** Resolve `#current` and transitive dependencies in `PackageSuites`. Use one shared, closed
   `HttpClient` with the configured proxy and TLS settings. Verify downloads against the registry checksum.
3. **F10.** When HAPI ships `hapi-fhir-structures-r6`, follow
   [fhir-versions.md › Adding R6](../fhir-versions.md#adding-r6). A6 makes the model part easier.

## Decisions to make

| Question | Proposal |
|---|---|
| Unsupported checks: `Load` error, or run-time error plus an early warning? (M1.3) | **Decided:** run-time error plus warning – keeps the documented outcome rules |
| Deselected tests: reported as `skip`, or left out? (M3.2) | **Decided:** left out, with a count in the log ([ADR-0014](../adr/0014-author-workflow.md)) |
| Typed comparisons: default, or behind a switch? (M5.2) | default, with a release note |
| Several servers: in scope at all? (M6.1) | only when a real suite needs it |
| Container image: published, and where? (M4.1) | **Decided:** built and checked in CI, not published ([ADR-0015](../adr/0015-container-image.md)); publishing is open |

---
name: testrunner-logging-and-reports
description: Logging and reporting in the FHIR TestScript Runner (TestRunnerOnJava) – the run folder layout, run-wide request ids, the MDC testScript key, the per-run log file, the communication log of every HTTP exchange, and the JUnit/summary/JSON/FHIR TestReport report writers. Use when working on log output, FhirHttpClient/CommunicationLog, RunLogFileListener, a ReportWriter or a new report format, or when tracing a failed assertion to the exchanged bytes.
---

# Logging and reports (ADR-0007, `docs/logging.md`)

## Run folder (one per run, `reporting.output-dir/<run id>/`, run id `run_yyyy-MM-dd_HH-mm-ss-SSS`)

```
testrunner.log                 run log (RunLogFileListener), level reporting.log-file-level (DEBUG)
junit.xml  summary.txt  result.json  report.html   one file per selected format
testreports/TestReport-<NN>_<TestScript base name>.json   format testreport: a FHIR TestReport per TestScript
communication/<NN>_<TestScript base name>/<REQ-id>_<METHOD>_<path>/   (characters outside [A-Za-z0-9._-] ⇒ _)
    request.http               request line, headers, body – as sent
    response.http              status line, headers, body – as received
    error.txt                  instead of response.http if no response came
```

## Request ids

`FhirHttpClient` assigns **run-wide** ids `REQ-%04d` (`REQ-0001`, `REQ-0002`, …; more digits past 9999) from the
`AtomicInteger` in `RunContext`
(unique with parallel TestScripts). The id is in the request's log line (with its folder), in every assertion log
line, in failure messages of `junit.xml`/`summary.txt`, in `result.json` (`ActionResult.requestId`) and as the
folder prefix. From a failed assertion, `REQ-nnnn` leads to the exact bytes exchanged.

## Logging rules

* SLF4J only, a logger per class under `at.hl7.testing.runner.*`; the core never configures logging. Logback is
  configured by Spring Boot (`testrunner-app/src/main/resources/application.yml`: console INFO, pattern
  `%d %-5level [%X{testScript:--}] %msg`).
* MDC `testScript` = `<NN>_<file>` (`TestRunner.MDC_TEST_SCRIPT`), set and cleared per TestScript by its thread.
  Every pattern prints it, which keeps parallel output apart.
* `RunLogFileListener` (autoconfigure, only if Logback is present) attaches a file appender per run at its own level
  and detaches it afterwards. DEBUG adds version detection, fixtures, operation/assert descriptions, variable values,
  validator command lines/timings, validator server output (`[validator server R4] …`).
* The run log starts with configuration and environment (server, versions, parallelism, validation mode, suites,
  TestScripts) and ends with the summary and report files – keep both when changing `TestRunner`.
* Levels: INFO per request/assert passed, ERROR for failed/erroring asserts, WARN for warnings (e.g. validation
  disabled, `warningOnly`), DEBUG for details.

## Communication log (`core.http.CommunicationLog`, one per TestScript execution)

Writes every exchange as exchanged (all headers, repeated headers on separate lines, body as text), except the
values of `reporting.redact-headers` (`ReportingConfig.DEFAULT_REDACT_HEADERS`: Authorization, Proxy-Authorization,
Cookie, Set-Cookie; case-insensitive; `[]` = none): `CommunicationLog.redacting(names)` masks them as `***`, keeping an
auth scheme (`Bearer ***`, `CommunicationLog.mask`), in files and wire logger alike; `ExecutionFactory.create` applies it.
Bodies/URLs are not masked. OAuth2 token requests (`http.AccessTokens`) bypass `FhirHttpClient`, so they are neither
in `communication/` nor counted as `REQ-` ids (they carry the client secret). Write problems are logged as warnings and never fail a test. Also logged at DEBUG to
`at.hl7.testing.runner.communication` (route anywhere via logging config). `reporting.communication-log=false`
switches the files off; ids stay.

## Reports (`core.report`)

* Result model, immutable: `RunResult` (runId, runDirectory, started, duration, testScripts, reportFiles;
  `outcome()`, `countByOutcome()`) → `TestScriptResult` (index, suite, name, url, source, fhirVersion, phases;
  `outcome()`) → `PhaseResult` (kind, name, outcome, duration, assertionCount, actions, messages) → `ActionResult`
  (kind `operation`/`assert`, description, outcome, message, requestId, warning flag).
* Writers: `JUnitXmlReportWriter` `junit` (a testsuite per TestScript, property `fhirVersion`, warnings in
  `system-out`), `SummaryReportWriter` `summary` (counts and reasons), `JsonReportWriter` `json` (every action with
  its request id), `TestReportWriter` `testreport` (a FHIR TestReport per TestScript in `testreports/`, in the
  TestScript's version; returns the folder), `HtmlReportWriter` `html` (`report.html`, see below). Defaults:
  `TestRunners.defaultReportWriters(FhirVersionRegistry)`; the app's `application.yml` selects all five.
* **HTML report** (`HtmlReportWriter`, plain Java, `ReportingConfig.HTML`): run header + counts, overview table,
  one `<section>` per TestScript (`<details open>` unless PASS; CSS-only checkbox "only what did not pass"), each phase
  with its own messages (those of an action are shown next to the action) and an action table. Request ids link to
  `communication/<NN>_…/<REQ-id>_…/` and its `request.http`/`response.http`/`error.txt` (`ExchangeFolders.of(run
  folder)`, also used by `TestReportWriter`; relative, `href()` percent-encodes); no folder ⇒ plain text. Security:
  `escape()` on every text (also `'`, control chars ⇒ U+FFFD), CSP `default-src 'none'; style-src 'unsafe-inline'`,
  `TestScript.url` never a link. Tests: `HtmlReportWriterTest` (hostile text, links exist), `RunFolderTest`.
* **TestReport**: the core maps a `TestScriptResult` to `spi.TestReportContent` (result pass/pending/fail, score = %
  tests passed, action `warning` for passed-with-warning, `detail` = communication folder of the request, phase
  messages outside actions become actions); `FhirVersionSupport.testReport(content)` encodes it (default throws
  `UnsupportedOperationException` ⇒ logged, no file). `fhir.hapi.TestReportEncoder` does it for every version by
  element name (`FhirTerser`): `testScript` is Reference (R4) or canonical (R5). Teardown: operations only.
  `TestScriptResult.fhirServer` (null if nothing sent) → `participant`.
* **New format**: implement `ReportWriter` (`format()` name, `write(RunResult, Path runDirectory)` returns the
  file); register it (Spring: a `@Bean`; library: pass it to the `TestRunner` constructor); select it in
  `reporting.formats`. Unknown formats are a configuration problem.
* Listeners: implement `RunListener` – each method gets a `RunInfo` (values only) – (`runStarted`, `testScriptFinished` – may be called concurrently, also for a
  TestScript that was not started –, `runFinished` – exactly once per started run, also after an exception or on
  Ctrl-C). A listener that throws is logged and ignored.
* Reports are written once per started run, whatever ends it (`TestRunner.publish`, ADR-0013); unfinished TestScripts
  appear as `error` "Did not end: …" or `skip` "Not started: …".

Tests: `CommunicationLogTest`, `RunFolderTest` (`communicationLogHasOneFolderPerRequest`,
`communicationLogMasksCredentialsByDefault`, `htmlReportLinksTheRequestsToTheirCommunicationFolders`),
`HtmlReportWriterTest`, `TestReportWriterTest`
(content mapping), `R4SupportTest`/`R5SupportTest.writesAValid…TestReport…` (HAPI parses it back),
`EndToEndIT.communicationLogsAndReportsAreWrittenPerRun`, `EndToEndIT.writesAFhirTestReportPerTestScriptInItsVersion`.

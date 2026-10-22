---
name: testrunner-logging-and-reports
description: Logging and reporting in the FHIR TestScript Runner (TestRunnerOnJava) – the run folder layout, run-wide request ids, the MDC testScript key, the per-run log file, the communication log of every HTTP exchange, and the JUnit/summary/JSON report writers. Use when working on log output, FhirHttpClient/CommunicationLog, RunLogFileListener, a ReportWriter or a new report format, or when tracing a failed assertion to the exchanged bytes.
---

# Logging and reports (ADR-0007, `docs/logging.md`)

## Run folder (one per run, `reporting.output-dir/<run id>/`, run id `run_yyyy-MM-dd_HH-mm-ss-SSS`)

```
testrunner.log                 run log (RunLogFileListener), level reporting.log-file-level (DEBUG)
junit.xml  summary.txt  result.json   one file per selected format
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

Writes every exchange **unfiltered** (all headers, repeated headers on separate lines, body as text). Write
problems are logged as warnings and never fail a test. Also logged at DEBUG to `at.hl7.testing.runner.communication`
(route anywhere via logging config). `reporting.communication-log=false` switches the files off; ids stay.
It contains `Authorization` headers from `fhir-server.headers` and server data – treat the folder as sensitive; a
redacting `CommunicationLog` would be the extension point.

## Reports (`core.report`)

* Result model, immutable: `RunResult` (runId, runDirectory, started, duration, testScripts, reportFiles;
  `outcome()`, `countByOutcome()`) → `TestScriptResult` (index, suite, name, url, source, fhirVersion, phases;
  `outcome()`) → `PhaseResult` (kind, name, outcome, duration, assertionCount, actions, messages) → `ActionResult`
  (kind `operation`/`assert`, description, outcome, message, requestId, warning flag).
* Writers: `JUnitXmlReportWriter` `junit` (a testsuite per TestScript, property `fhirVersion`, warnings in
  `system-out`), `SummaryReportWriter` `summary` (counts and reasons), `JsonReportWriter` `json` (every action with
  its request id). Defaults: `TestRunners.defaultReportWriters()`.
* **New format**: implement `ReportWriter` (`format()` name, `write(RunResult, Path runDirectory)` returns the
  file); register it (Spring: a `@Bean`; library: pass it to the `TestRunner` constructor); select it in
  `reporting.formats`. Unknown formats are a configuration problem.
* Listeners: implement `RunListener` (`runStarted`, `testScriptFinished` – may be called concurrently –,
  `runFinished`).

Tests: `CommunicationLogTest`, `TestScriptExecutionTest.communicationLogHasOneFolderPerRequest`,
`EndToEndIT.communicationLogsAndReportsAreWrittenPerRun`.

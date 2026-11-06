# Logging and communication logging

A test run produces three kinds of records, all in one folder per run:

```
testrunner-results/run_2026-10-21_20-58-01-838/
├─ testrunner.log            the run log, DEBUG by default
├─ junit.xml                 results for CI
├─ summary.txt               counts and reasons of failures
├─ result.json               every action, with its request id (format json)
├─ report.html               one page for people: every action, request ids linked into communication/ (format html)
├─ testreports/              a FHIR TestReport per TestScript, in its FHIR version (format testreport)
│  └─ TestReport-01_TestScript-testscript-patient-create-at-core.json
└─ communication/
   ├─ 01_TestScript-testscript-patient-create-at-core/
   │  └─ REQ-0003_POST_Patient/
   │     ├─ request.http     request line, headers, body – as sent
   │     └─ response.http    status line, headers, body – as received
   └─ 02_TestScript-testscript-patient-update-at-core/
      ├─ REQ-0001_PUT_Patient_HL7ATCorePatientUpdateTestExample/
      └─ REQ-0002_PUT_Patient_HL7ATCorePatientUpdateTestExample/
         └─ error.txt        instead of response.http if no response came back
```

A check (`--check`) writes `check_<timestamp>/` with `testrunner.log`, `junit.xml`, `summary.txt`, `result.json` and
`report.html` only: it sends nothing, so there is no `communication/`, and no `testreports/`, since a TestReport would
say the TestScripts were executed.

## Request ids tie everything together

Every request gets a **run-wide** id, `REQ-0001`, `REQ-0002`, … (an `AtomicInteger` in the `RunContext`, so ids
stay unique with parallel TestScripts). The id appears

* in the log line of the request, together with the folder of the exchange,
* in every assertion log line, naming the response it checked,
* in failure messages in `junit.xml` / `summary.txt` and in `result.json`,
* in `report.html`, as a link to the exchange folder and its files,
* as the prefix of the exchange folder.

```
20:58:11.111 INFO  [02_TestScript-…-negative-at-core] FhirHttpClient - REQ-0003 POST http://…/fhir/Patient -> 201 Created (12 ms) [02_TestScript-…-negative-at-core/REQ-0003_POST_Patient]
20:58:22.692 ERROR [02_TestScript-…-negative-at-core] TestScriptExecution - Assert failed: validateProfileId 'at-core-patient-profile' [REQ-0003] - Not conformant to https://fhir.hl7.at/core/r4/StructureDefinition/at-core-patient:
error @ Patient: Patient.identifier:socialSecurityNumber: max allowed = 1, but found 2 (from …at-core-patient|2.1.0)
```

From a failed assertion in the report, `REQ-0003` leads to the exact bytes that were exchanged.

## The run log

* All runner code logs through SLF4J under `at.hl7.testing.runner.*`; there is no logging state in the core.
* While a TestScript runs, the MDC key `testScript` holds `<NN>_<file name>` (`TestRunner.MDC_TEST_SCRIPT`).
  Both the console pattern and the run log print it as `[%X{testScript}]`, which keeps interleaved lines of parallel
  TestScripts apart.
* `RunLogFileListener` (Spring auto-configuration, Logback only) attaches a file appender for the duration of each
  run at `reporting.log-file-level` (default `DEBUG`), independent of the console level, and detaches it afterwards.
  DEBUG adds: version detection, fixture details, operation/assert descriptions, variable values, validator command
  lines and timings. In validator server mode it also contains the console output of the validator servers, prefixed
  `[validator server R4]`, and one line per validation with the server's response time; starting and stopping a
  server is logged at INFO.
* The start of the log records the configuration and environment (server, versions available, parallelism,
  validation, suites, TestScripts); the end records the summary and the report files.

Console level and format are ordinary Spring Boot logging settings (`logging.level.*`, `logging.pattern.console`,
`logging.threshold.console`), defaults in `testrunner-app/src/main/resources/application.yml`.

## The communication log

`CommunicationLog` (one per TestScript execution) writes each exchange as it was: all headers, repeated headers
on separate lines, the body exactly as text – except the values of credential headers, which are masked (see
[Sensitive data](#sensitive-data)). Writing problems are logged as warnings and never fail a test.

The full exchange is additionally logged at DEBUG to the logger `at.hl7.testing.runner.communication`, so it can be
routed anywhere through logging configuration alone:

```yaml
logging.level.at.hl7.testing.runner.communication: DEBUG   # requests/responses also in console and run log
```

`testrunner.reporting.communication-log=false` switches the files off; the request ids are logged regardless.

### Sensitive data

The values of credential headers are masked, in the files and in the `at.hl7.testing.runner.communication` logger.
An authentication scheme stays visible, so a wrong scheme can still be seen:

```
GET http://localhost:8080/fhir/Patient HTTP/1.1
Authorization: Bearer ***
Accept: application/fhir+json
```

`testrunner.reporting.redact-headers` lists the headers to mask, compared case-insensitively. The default is
`Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie`. Setting the list replaces the default, so name them
all:

```yaml
testrunner.reporting.redact-headers: [Authorization, Proxy-Authorization, Cookie, Set-Cookie, X-Api-Key]
```

An empty list (`redact-headers: []`) writes every header unmasked. The request still carries the real values;
only the log is masked.

Only headers are masked. Bodies, URLs and query parameters are written as exchanged. A token in a URL or in the server
data stays visible, so treat the result folder like the test data it contains.

## FHIR TestReports

With the format `testreport` (on in the application's defaults) the run folder gets `testreports/` with one FHIR
`TestReport` per TestScript, in the FHIR version of the TestScript, for FHIR servers and conformance tools. What goes
in is decided in the core (`TestReportWriter`); the version module only encodes it
(`FhirVersionSupport.testReport`, one generic `TestReportEncoder` on HAPI for all versions – `testScript` is a
Reference in R4 and a canonical in R5):

| TestReport | From |
|---|---|
| `identifier` | `urn:testrunner:<run id>/<NN>` |
| `testScript` | `TestScript.url`, else the file URI |
| `result` | `pass`; `pending` if the TestScript was skipped (e.g. a missing capability); `fail` for fail and error (FHIR has no result "error", the actions have it) |
| `score` | percentage of the tests that passed |
| `participant` | the server (`type = server`, its base URL), if anything was sent |
| `setup` | the Load and Setup phases |
| `test` | one per test, named like the test |
| `teardown` | the teardown, operations only |
| action `result` | `pass`, `skip`, `fail`, `error`; `warning` for an action that passed with a warning (`warningOnly`, validation disabled) |
| action `message` | the action's description and, if it did not pass, why |
| action `detail` | the folder of the request in `communication/`, relative to the run folder |

What happened outside of the actions – an unmet capability, a fixture that could not be created, a skipped test,
content the version ignored – becomes an action of its own, so a TestReport carries the same reasons as `junit.xml`.
A TestScript whose FHIR version could not be determined gets no TestReport.

## The HTML report

With the format `html` (on in the application's defaults) the run folder gets `report.html`: one self-contained page
for readers who do not open `junit.xml` (`HtmlReportWriter`, plain Java, no template engine, no script).

* At the top the run: outcome, start, duration, counts, and a table of every TestScript with its tests passed.
* Then one section per TestScript: suite, file, url, FHIR version, server, and each phase with its messages and its
  actions. A TestScript that did not pass is open, the others are folded; "only what did not pass" hides the rest.
* Every request id links to its folder in `communication/`, and to `request.http`, `response.http` or `error.txt` in
  it. The links are relative: the run folder can be zipped, archived or attached to a CI job as a whole. Without
  communication log (`communication-log: false`) the ids are shown without link.
* Everything from a TestScript or a server – names, descriptions, messages with response content – is HTML-escaped.
  The page's Content-Security-Policy allows no script at all, and a TestScript's url is shown as text, never linked.


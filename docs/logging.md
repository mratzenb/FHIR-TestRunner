# Logging and communication logging

A test run produces three kinds of records, all in one folder per run:

```
testrunner-results/run_2026-10-21_20-58-01-838/
├─ testrunner.log            the run log, DEBUG by default
├─ junit.xml                 results for CI
├─ summary.txt               counts and reasons of failures
├─ result.json               every action, with its request id (format json)
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

## Request ids tie everything together

Every request gets a **run-wide** id, `REQ-0001`, `REQ-0002`, … (an `AtomicInteger` in the `RunContext`, so ids
stay unique with parallel TestScripts). The id appears

* in the log line of the request, together with the folder of the exchange,
* in every assertion log line, naming the response it checked,
* in failure messages in `junit.xml` / `summary.txt` and in `result.json`,
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
  lines and timings.
* The start of the log records the configuration and environment (server, versions available, parallelism,
  validation, suites, TestScripts); the end records the summary and the report files.

Console level and format are ordinary Spring Boot logging settings (`logging.level.*`, `logging.pattern.console`,
`logging.threshold.console`), defaults in `testrunner-app/src/main/resources/application.yml`.

## The communication log

`CommunicationLog` (one per TestScript execution) writes each exchange **unfiltered**: all headers, repeated headers
on separate lines, the body exactly as text. Writing problems are logged as warnings and never fail a test.

The full exchange is additionally logged at DEBUG to the logger `at.hl7.testing.runner.communication`, so it can be
routed anywhere through logging configuration alone:

```yaml
logging.level.at.hl7.testing.runner.communication: DEBUG   # requests/responses also in console and run log
```

`testrunner.reporting.communication-log=false` switches the files off; the request ids are logged regardless.

### Sensitive data

The communication log contains what the server returned and any `Authorization` header configured in
`fhir-server.headers`. Treat the result folder like the test data it contains. A redacting `CommunicationLog` is a
small extension if the folder must be shared.

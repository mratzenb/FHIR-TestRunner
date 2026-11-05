# ADR-0014: Author workflow – a check mode that sends nothing, and selecting what runs

## Context
A TestScript author learns about a typo only by running the TestScript against a FHIR server. The runner already
finds every mistake that fails in every run before it sends anything (`TestScriptChecks`, `ActionRules`, the
validation of the TestScript itself), but only inside a run: the author needs a server, waits for the setup of the
other TestScripts, and a CI job that checks an IG before it is published has no server at all. The TinkerTool, which
writes TestScripts, wants the same feedback in seconds (feature F1 of [next steps](../wiki/next-steps.md#features)).

Working on one TestScript, or on one test of it, the author had to edit `suites[n].include` to run only that, and
could not run a single test at all (F2).

## Decision
* **`TestRunner.check(configuration[, control])`** next to `run`. It does what a run does up to the point where it
  would send the first request – configuration check, package resolution, discovery, loading, `TestScriptChecks`,
  and the validation of each TestScript if `validation.validate-test-scripts` is on – and stops there. One code path:
  both go through the same `start` and `runOne`; a private `Mode` decides whether `execute` hands the TestScript to a
  `TestScriptExecution`.
* **Result: the phase `Load` only.** `pass` with every warning a run would give before sending (content the version
  ignores, unsupported assertions, fixtures created in the wrong order), or `error` with every problem. The tests are
  not reported as `skip`: nothing would have run them. The `RunResult` has the usual shape, so the JUnit, summary and
  JSON writers and any `ReportWriter` of an application work unchanged.
* **No FHIR TestReports in check mode.** A TestReport states that a TestScript was executed against a server; a
  `pass` there would read as a passed test. `ReportPublisher` leaves out the format `testreport`.
* **A folder of its own:** `check_<timestamp>` next to the `run_<timestamp>` folders, so a script that picks the
  newest run never takes a check.
* **Needs no server, and the validator only for what it does.** Without `fhir-server.base-url` a check works. The
  validator's configuration problems stop a check only when it validates the TestScripts.
* **Run control.** The run timeout and a cancel apply; the TestScript timeout does not (a check sends nothing that
  could hang) and fail-fast does not (the point of a check is every problem at once).
* **App: `--check` = `testrunner.mode=check`** (`run` by default). `RunCommand` calls `check` instead of `run`. Exit
  codes as for a run: 0 no problems, 1 problems, 2 configuration.

### Selecting what runs (F2)
* **`testrunner.select.test-scripts` and `testrunner.select.tests`**, lists of globs (`*`, `?`, case-insensitive,
  matching the whole text), in `RunConfiguration.select` (`SelectConfig`, default: everything). Unlike `include`,
  which says what a suite *is*, the selection says what *this* run takes from the suites, so it belongs to the run and
  works across suites. App short forms: `--testscript=<glob>`, `--test=<glob>`, repeatable or comma-separated.
* **A TestScript** is selected when a TestScript pattern matches its file name (with or without extension),
  `TestScript.name` or `TestScript.url` – an author knows one of them – and, with test patterns, when at least one
  of its tests is selected. A file that cannot be read is run only when its file name is selected, so its error is
  reported.
* **A test** is selected by its name. **Setup and teardown always run**, they belong to every test.
* **What is not selected is left out**, not reported as `skip`: the numbering of the run counts only selected
  TestScripts, a TestScript has only its selected tests in the reports, and the log says how many were left out
  ("Selected 2 of 9 TestScript(s) (…); 7 left out", "1 of 4 test(s) selected: [read]"). A `skip` would say that
  something could not run; here the user chose not to run it, and exit code 3 ("everything skipped") would follow.
* **The checks before sending see only what runs.** A selected test that uses a response kept by a test left out is a
  `Load` error that says so: `targetId 'created' is kept by Test 'create', action 1, which is not selected
  (testrunner.select.tests); select that test too`.
* **`--list`** (`testrunner.mode=list`, `TestRunner.list(configuration)`) prints what a run with the same arguments
  would run – each TestScript with suite, file, FHIR version, name and its selected tests – and sends and writes
  nothing. Exit code 0, or 1 if a TestScript cannot be read.

## Alternatives
* *A separate `TestScriptChecker` class* – would duplicate the orchestration of `TestRunner` (configuration problems,
  packages, discovery, listeners, reports written whatever happens) or pull it apart for a feature of a few lines.
* *A `RunConfiguration.mode`* – the configuration describes what to run and where; whether to send is a decision of
  the caller. `RunCommand` reads `testrunner.mode` itself, like `exit-on-skip`.
* *Report the tests as `skip` in check mode* – exit code 3 for a clean suite (everything skipped), and a JUnit report
  full of skipped tests that nobody skipped.
* *Write the TestReports with result `pending`* – still a document about an execution that did not happen.
* *Report deselected tests as `skip`* – the run would look incomplete although it did what was asked, and a run of
  one test in a suite of skipped TestScripts would exit with 3. The proposal of next-steps, left out with a count in
  the log, was taken.
* *Select by `suites[n].include` only* – per suite and by file name only; a test cannot be selected that way.
* *Regular expressions instead of globs* – `.` in a url or file name would need escaping; globs are what `include`
  already uses.

## Consequences
* An author checks a suite in seconds: `java -jar …-exec.jar --config=my.yml --check --novalidator`.
* What a check finds is exactly what a run would report in its `Load` phase before sending; there is one place for
  those rules (`TestScriptChecks`, `ActionRules`). Problems that depend on the server's answers are not found.
* `RunListener`s see a check like a run (`runStarted`, one `testScriptFinished` per TestScript, `runFinished`); the
  run id starts with `check_`.
* With a selection by name, url or test, each TestScript is read once more before the run to decide. Selecting by
  file name only reads nothing extra.
* `RunConfiguration` has one more component, `select`; code that calls its constructor needs one more argument
  (`null` for all).

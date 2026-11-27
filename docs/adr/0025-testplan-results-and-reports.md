# ADR-0025: Results and reports – a plan node next to the flat TestScript list

**Status: proposed** (2026-11-18). Plan and tasks: [TestPlan plan](../wiki/testplan-plan.md). Open decision D5 there.

## Context
Every output of a run is built from `RunResult.testScripts`: JUnit (a suite per TestScript), summary, JSON, HTML (a
section per TestScript), the FHIR `TestReport` (one per TestScript), the run log, the listeners, the exit code. No
writer knows a level above the TestScript. With plans, a reader needs to see which plan and case a TestScript ran
for, what a plan's outcome is, and which cases ran nothing – and the `Load` problems of a plan, which belong to no
TestScript at all.

`TestReport` ("the results of a TestScript execution") has no element that refers to a TestPlan, in R5 and in every R6
build looked at; there is no FHIR resource for the result of a plan.

`RunResult`, `TestScriptResult` and the report writers are public API (`report` is a core API package; ADR-0017), built
with positional constructors in main and test code.

## Decision
* **Keep the flat list, add a tree beside it.** `RunResult.testScripts` is unchanged and keeps its meaning: every
  TestScript execution, plan-driven or stand-alone, in run order. New `RunResult.testPlans`:
  `TestPlanResult(index, suite, name, url, source, fhirVersion, outcome(), exitCriteria, started, duration, messages,
  cases)` and `TestCaseResult(sequence, key, description, outcome, testScripts (indexes into the flat list), narrative,
  messages)`. A case refers to TestScripts **by index**; no result is held twice, and `TestScriptResult` does not
  change. `RunResult` gets a compatibility constructor; the CHANGELOG lists the API change.
* **The outcome of the run** combines the TestScripts and the plans' own problems; the empty-run rule (no TestScripts ⇒
  `ERROR`) counts plans, so that a run of only manual plans is `SKIP`, not an error. Exit codes do not change.
* **Writers (all five), additive only:**
  * *junit:* TestScript suites as today with properties `testPlan`, `testPlanUrl`, `testCase`; an extra `testsuite`
    per plan holds one `testcase` for every case that ran no TestScript (`skipped` or `error`, with the reason) and one
    for a `Load` problem of the plan, so a CI view shows them.
  * *summary:* the TestScript lines as today; with plans, a count and a block per plan with its cases.
  * *json:* `testScripts[]` as today plus `testPlan`/`testCase` on members; `testPlans[]` (always present, empty
    without plans) with `cases[]`.
  * *html:* a section per plan before its TestScripts, with a table of cases linking to the TestScript sections, and a
    "Plan / case" column in the overview. All plan text (names, narrative, exit criteria) is **escaped**; narrative is
    markdown from a package and is never rendered as markup (the CSP and `escape()` rules of the HTML writer stay).
  * *testreport:* **one `TestReport` per TestScript, unchanged in file name and content**, plus an extension on each
    report that belongs to a plan, carrying the plan's canonical URL (or its file URI) and the case sequence (decision
    D5: the extension's URL is the project's own). No FHIR resource is invented for the plan's result.
* **Run log:** `===== TestPlan n/m: <file>`, `Case k/K (sequence s): TestScript <file>`, `TestPlan '<name>' finished: <outcome>`.
  The MDC key and the communication folder names keep the TestScript's index and file name.
* **Listeners:** `RunListener.testPlanFinished(RunInfo, TestPlanResult)` as a `default` no-op method: existing
  listeners compile and behave as before. `RunLogFileListener` needs nothing.
* **Check mode:** plans appear with their `Load` problems and warnings; no TestReports, as in ADR-0014.
* **Reports-always (ADR-0013):** a plan not finished when reports are forced is `error` "Did not end: …", one not
  started `skip` "Not started: …".

## Alternatives
* *A `TestPlanResult` that contains `TestScriptResult`s* – the natural tree, but every writer and every consumer of
  `RunResult.testScripts` would change, and a TestScript referenced twice would exist twice.
* *`planIndex`/`caseSequence` on `TestScriptResult`* – a link in one direction only, and a plan's `Load` problem or a
  manual case would still have no place; also changes a record built in several files.
* *Synthetic `TestScriptResult`s for cases that run nothing* – everything keeps working, but a manual case would be a
  "TestScript" in JUnit and in the TestReports, which it is not.
* *A FHIR `Bundle` of the plan's TestReports, or a made-up resource for the plan result* – no standard resource;
  consumers of TestReports would meet a profile they do not know.
* *No link from TestReport to plan* – simplest; the information would exist only in the runner's own formats, and a
  TestReport stored on a FHIR server would lose it.

## Consequences
* A consumer that knows only TestScripts (the JUnit view of a CI system, a script reading `result.json`) is unaffected;
  with plans it also shows the extra suite and the new properties.
* `RunResult` has a seventh component; callers of the old constructor keep working.
* Every writer has a plan test; the HTML writer has a hostile-text test for plans (`HtmlReportWriterTest`).
* Two shapes of the same fact exist (flat list and tree); they cannot disagree because the tree holds indexes only.

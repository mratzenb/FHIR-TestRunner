# ADR-0023: TestPlans in a folder of their own, scripts resolved inside the suite, reference-once, selection

**Status: proposed** (2026-11-18). Plan and tasks: [TestPlan plan](../wiki/testplan-plan.md). Open decisions D1 and D2
there.

## Context
Today a suite is a folder with `Test_Scripts/`, `Example_Instances/` and `Profiles/`; every file of `Test_Scripts/` that
matches `include` (`*.json`, `*.xml`) is a TestScript. A TestPlan placed there fails the `Load` phase ("the content is a
TestPlan, not a TestScript"). Packages (ADR-0018) keep all conformance resources in one folder; `SuiteConfig.withPackage`
includes `TestScript-*.json` there.

A plan refers to its TestScripts by `source[x]`: a Reference (`TestScript/<id>`, an absolute canonical URL, maybe
`url|version`) or a string (a file name, a path, a URL). The same suite can hold stand-alone TestScripts, plans, and
TestScripts that plans reference – so a run needs a rule for what happens when a TestScript is both.

A TestScript found by a plan is sent to servers (ADR-0019). The package it comes from is not trusted to choose where.

## Decision
* **A folder of its own: `suites[n].test-plans-dir`, default `Test_Plans`**, with `test-plan-include` (default `*.json`,
  `*.xml`; for a package `TestPlan-*.json`, `TestPlan-*.xml`). A missing folder means "no plans" (unlike `Test_Scripts`).
  `include` keeps saying what a TestScript *is*. A TestPlan in `Test_Scripts/` stays an error, and its message names
  `test-plans-dir`.
* **Script references are resolved inside the suite, and only there.** A run builds one `TestScriptIndex` per run from the
  TestScripts the suite discovered. In this order: (1) a canonical URL equals `TestScript.url` (`|version`, if given,
  must equal `TestScript.version`; otherwise ignored), (2) `TestScript/<id>` or a bare id equals `TestScript.id`,
  (3) a relative path or file name equals a discovered file (relative to `test-scripts-dir`, separators normalised,
  never leaving it). No match, or more than one, is a `Load` problem of the plan naming case, run and source.
  **A URL is never fetched and a path never leaves the suite.** (`contained` TestScripts of the plan are not resolved
  in the first version: a problem that says so.)
* **Reference-once.** A TestScript that at least one plan *of the same suite* references runs in the plan's context only,
  not additionally stand-alone. TestScripts no plan references run stand-alone, as today. A TestScript that two cases
  reference runs twice, each its own execution (its own index, fixtures and communication folder), because the cases
  may differ in what surrounds them. A suite without plans behaves exactly as before.
* **Selection.** `testrunner.select.test-plans` (globs, case-insensitive, whole text, on file name with or without
  extension, `name`, `url`, `id`; CLI `--testplan=<glob>`, repeatable or comma-separated). `select.test-scripts` and
  `select.tests` apply to **every TestScript execution**, stand-alone or from a plan (the selection says what *this* run
  takes from the suites, ADR-0014). If `test-plans` is set and `test-scripts` is empty, no stand-alone TestScript runs:
  asking for a plan means that plan. A selected plan that has no selected TestScript is left out like a TestScript
  with no selected test (not reported as `skip`). `--list` shows plans with their cases and the resolved TestScripts.

## Alternatives
* *One folder, sort by `resourceType`* – natural for packages, but `Test_Scripts/` then holds two kinds of things, a
  type sniff per file is needed everywhere `include` is used, and an R4 suite would see TestPlans it cannot read.
  Packages get the same effect from the include pattern. (Open decision D1.)
* *Explicit list of plans in the configuration* – precise, but every new plan needs a configuration change; the
  folder convention is what suites already do.
* *Run stand-alone and in plans* ("both") – a TestScript of a plan would hit the server twice for no reason, and its
  result would appear in two places without telling which one counts.
* *Plans replace stand-alone TestScripts as soon as a suite has a plan* – hides TestScripts a plan author forgot to
  list; the run would shrink after adding a plan file. (Open decision D2.)
* *Resolve references by fetching canonical URLs* – a package could make the runner request arbitrary URLs.
  Same reasoning as `destination.url` in ADR-0019.
* *Add a new selection concept (`select.cases`)* – the use for it is unclear; plans are selected as a whole, and
  `select.tests` already narrows the tests inside the TestScripts. Can be added later.

## Consequences
* `SuiteConfig` gets two components (`testPlansDir`, `testPlanInclude`) and a `resolvedTestPlansDir()`; `SelectConfig`
  gets `testPlans`. Constructors with all components change; the factories (`SuiteConfig.of`, …) do not.
* Reading every TestScript once more to build the index costs a parse per file; `Selection` already does the same for
  name/url selection (`TestScriptLoader.map`).
* The numbering of a run counts executions: with plans, a TestScript can have two numbers.
* The reference-once rule needs the index even for suites whose plans are not selected (to know what is referenced)
  – only when the suite has plans.

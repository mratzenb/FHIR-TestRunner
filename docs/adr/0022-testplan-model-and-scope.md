# ADR-0022: TestPlan – a thin neutral model; the plan says what, the TestScript says how

**Status: proposed** (2026-11-18). Plan and tasks: [TestPlan plan](../wiki/testplan-plan.md). Open decision D6 there.

## Context
The runner executes `TestScript`s. A FHIR `TestPlan` (R5, maturity level 0, draft) is "a plan for executing testing on an
artifact or specifications": the business and planning side around the TestScript. It names test cases, their order
(`testCase.sequence`), what they cover (`scope`), what they depend on (`dependency.predecessor`), narrative test data and
expectations, exit criteria, and – the only executable part – `testCase.testRun.script`, a `language` and a `source[x]`
(string or Reference) that "references TestScripts or externally defined content". The specification says the TestPlan
is "intended to contain the planning and non-technical part"; the TestScript "describes how to test".

The runner must run plans, without turning into a general test-management tool, and without losing its rule that
nothing is silently ignored ([design insights §7](../wiki/design-insights.md#7-never-a-silent-pass)).

## Decision
* **The plan decides what runs and in which order; the engine runs TestScripts.** A plan is executed by executing the
  TestScripts its cases point to. No part of the engine learns about plans (see [ADR-0024](0024-testplan-execution.md)).
* **A thin version-neutral model in `core.model`**, in the style of the TestScript model (ADR-0005, ADR-0017):
  `TestPlan(fhirVersion, id, url, name, title, description, exitCriteria, scope, dependencies, cases)`,
  `TestCase(key, sequence, description, scope, dependencies, runs, testData, assertions)`,
  `TestRun(narrative, script)`, `Script(language, source)` and a sealed `ScriptRef` (`Reference(reference)`,
  `Text(value)`). Text that the runner only shows (`description`, `exitCriteria`, `scope`, `testData`, `assertions`) is
  kept as plain strings. `key` is empty in R5 (it appears in R6 ballot 3); nothing is derived from it yet.
* **Three classes of content, each handled explicitly:**
  * *Executed:* a `testRun.script` with language `testscript` (or none, when it resolves to a TestScript) whose source
    resolves to a TestScript of the suite.
  * *Shown, not evaluated:* `exitCriteria`, `scope`, `testData`, `assertion`, descriptions. They go into the reports
    as text. A plan with `testData` gets a warning, "testData is not used".
  * *Refused with a reason:* any other language, narrative-only runs, sources outside the suite. The case is
    reported (`skip` or `error`, see ADR-0024), never dropped.
* **Reading is generic.** One `TestPlanMapper` in `fhir.hapi` reads the HAPI model by element name through
  `ElementView`, like `TestScriptMapper`; the `source[x]` choice is read through `source[x]` and `typeName()`.
  Strict parsing with the `CollectingErrorHandler`; what a version ignores becomes a warning on the plan.
* **Plan files are validated like TestScripts.** With `validation.validate-test-scripts` on, the validator checks each
  TestPlan file against the base specification of its version, before anything is sent, also in check mode. The
  setting keeps its name and now covers plans; its documentation says so. (Open decision D6.)
* **Non-goals:** evaluating `exitCriteria`; manual tests; gherkin and postman; creating test data from `testData`;
  state shared between the TestScripts of a plan (every TestScript keeps its own fixtures and variables, ADR-0004);
  the TestPlan as a *result* (a plan is a definition, see [ADR-0025](0025-testplan-results-and-reports.md)).

## Alternatives
* *Model the whole resource* – every element of `TestPlan` in records. Mostly text the runner cannot use, and R6
  replaces the structure ([ADR-0026](0026-testplan-and-fhir-versions.md)); the cost would be paid again.
* *Keep the HAPI `TestPlan` in the core* – breaks the framework-free core (ADR-0001, `ArchitectureTest`).
* *Convert a TestPlan into a synthetic TestScript* – one TestScript with the cases' operations merged. It would reuse
  the engine, but the TestScripts of a plan are independent resources with their own fixtures, setup and teardown;
  merging them changes what they do.
* *Execute `narrative` with a human in the loop* – out of scope for an automated runner.
* *Rename the setting to `validate-resources`, with the old name as an alias* – accurate, but a rename of a
  documented property for one feature; the name can change later without changing this decision. (Open decision D6.)

## Consequences
* New model types and one new SPI pair (`FhirVersionSupport.readTestPlan`), all additive.
* The model is small enough that an R6 mapper can fill the same records where the concepts overlap; where they do not,
  that is a new decision, not a retrofit.
* Every unsupported part has a defined, visible result; the table is in the [plan](../wiki/testplan-plan.md#what-a-plan-can-and-cannot-say-and-how-the-runner-reports-each-case).

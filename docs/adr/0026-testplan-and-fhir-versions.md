# ADR-0026: TestPlan and FHIR versions – R5 and later only; R6 is a separate decision

**Status: proposed** (2026-11-18). Plan and tasks: [TestPlan plan](../wiki/testplan-plan.md).

## Context
`TestPlan` was introduced in FHIR **R5**. R4 and R4B have no such resource; HAPI's R4 does not know it (parsing fails
with `HAPI-1684 Unknown resource name "TestPlan"`, and `getResourceTypes()` does not contain it), HAPI's R5 does. The
runner reads TestScripts of R4 and R5 side by side (ADR-0005) and detects the version of each file.

R6 does not carry the R5 shape forward. `6.0.0-ballot3` still has a core `TestPlan`, but changed (`scope` a backbone,
`testCase.key`, `testCase.requirement`, canonical `dependency.reference`, nested `testCase`, `source[x]` with
`Attachment`). In `6.0.0-ballot5` and `6.0.0-snapshot1` it has left the core for the FHIR Testing IG
(`hl7.fhir.uv.testing`), whose current build defines a different resource: `runner` (a URL), `mode`, `suite` → `test`
(`operation`, `input`, `expected`, `assertion`), no `testCase` and no TestScript reference. An R6-shaped plan read by
the R5 parser parses with *unknown element* warnings only.

A plan refers to TestScripts, and the TestScripts of a suite may be R4 or R5.

## Decision
* **TestPlan support needs a version whose `FhirContext` knows the resource.** `FhirVersionSupport` gets default methods
  `supportsTestPlans()` (false), `checkTestPlan(Body)` and `readTestPlan(Body)` (throws `UnsupportedOperationException`
  by default, like `testReport`). `HapiFhirVersionSupport` implements them from
  `context.getResourceTypes().contains("TestPlan")`: R4 says no without any R4-specific code, R5 says yes, and a
  later HAPI R6 module gets the answer from its own context.
* **R4 says so.** A suite with `fhir-version: R4` that has plans is a configuration problem: "TestPlan exists from FHIR
  R5; suite 'x' is pinned to R4 (test-plans-dir …)". With `AUTO`, a plan file is read by the versions that support
  TestPlans, with the detection rule of TestScripts (no errors, fewest warnings, newest on a tie); if none is
  available the message names `testrunner-fhir-r5`. A file that is no TestPlan in any of them is a `Load` error of the
  plan, as for a TestScript.
* **A plan and its TestScripts may differ in version.** The plan (R5) refers to TestScripts; each TestScript is loaded
  with its own version detection or the suite's pinned version, as always. A pinned suite applies the pin to plans and
  TestScripts alike (hence the R4 rule above). This is lenient on purpose: a reference names a resource, and the runner
  does not convert resources between versions (the TestScript is run as the version it is).
* **One generic mapper for the R5 shape.** `TestPlanMapper` in `fhir.hapi` (ADR-0005). It does **not** get a dialect for
  R6: the R6 shapes differ in structure, so a dialect method per difference would hide a second model behind switches.
  A `TestPlanDialect` is added only for a difference of meaning that the R5 shape itself has.
* **R6 is out of scope now**, and the neutral model keeps `key` (empty in R5) and a text `scope` so that an R6 ballot-3
  mapper could fill the same records. Whether the Testing IG's `TestPlan` (a document for *a* runner that "knows the
  tests") is mapped to the neutral model or to one of its own is decided when the IG is published and HAPI has R6
  structures (task T14, with feature F10 of the [next steps](../wiki/next-steps.md)). Until then an R6-shaped plan
  read as R5 shows its ignored elements as warnings on the plan, not silently.
* **Reports are version-independent here:** the plan node of a `RunResult` has a FHIR version for display; the TestReport
  of each TestScript is written in the TestScript's version as today (ADR-0025).

## Alternatives
* *Support TestPlan for R4 through an extension or a logical model* – there is no standard form; a runner-specific
  shape would be an invention that real plans do not follow.
* *Detect the version of a plan by guessing from the content* – only R5 can read it today; the registry rule already
  chooses among the versions that can.
* *Convert R4 TestScripts into R5 so that a plan only sees one version* – cross-version conversion is not in scope
  ([fhir-versions](../fhir-versions.md#data-mapping-beyond-testscripts)).
* *Require plan and scripts of the same version* – safe, but an R5 plan over R4 TestScripts (the AT Core scripts exist
  for both) is a normal use, and nothing is converted.
* *A per-version flag in each module (`R5Support.supportsTestPlans = true`)* – a fact HAPI already knows; a flag can
  disagree with the context after an upgrade.
* *Map the Testing IG's `TestPlan` now* – the IG is a CI build ("not an authorized publication"), and it would couple
  the runner to a moving target.

## Consequences
* The R4 module and `R4Support` need no change; the R5 module needs none either (the generic support does the work).
* `docs/fhir-versions.md` gets a section "TestPlan" (R5+, no R4, mixed versions, R6 status) and its "Adding R6"
  recipe a line: check whether the version has a TestPlan of the R5 shape.
* A TestPlan is a weak fit for a pinned R4 suite on purpose; the message says what to do (a suite of its own with
  `fhir-version: R5` or `AUTO`).
* If the Testing IG's TestPlan becomes the R6 form, a second mapper and a second ADR follow; this one does not block
  that.

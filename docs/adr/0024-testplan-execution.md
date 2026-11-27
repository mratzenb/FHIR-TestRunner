# ADR-0024: A plan expands into TestScript executions; the plan is the unit of scheduling

**Status: proposed** (2026-11-18). Plan and tasks: [TestPlan plan](../wiki/testplan-plan.md). Open decisions D3 and D4
there.

## Context
The engine's unit of work is a TestScript: `TestScriptSource` → `runOne` → `TestScriptResult`. The index of that
execution is the log label (MDC `testScript`), the communication folder, the TestReport file and the JUnit suite.
`RunScheduler` runs a flat list, in parallel across TestScripts, never within one (ADR-0008); `RunControl`,
fail-fast and the reports-always rule (ADR-0013) are defined over that list. A5 of the [next steps](../wiki/next-steps.md)
split `TestRunner` because it did too much, and A5 warned that new features land in exactly that class.

A plan adds three things the flat list does not have: an order (`testCase.sequence`), cases that do not run a
TestScript (narrative, another language, an unresolved source), and dependencies on other plans.

## Decision
* **Expansion.** After loading, a plan becomes an ordered list of *members*: for each case (by `sequence`, ties and
  missing values in declaration order), for each run of it, the resolved `TestScriptSource`, or the reason there is none.
  `runOne` is **not changed**: it receives a member's `TestScriptSource` and does what it does today – load, checks,
  execute – so every engine rule (phases, outcomes, fixtures, capabilities, destinations, run control) applies to the
  TestScripts of a plan without a line of plan-specific code.
* **A run unit is a TestScript or a plan.** `RunScheduler` schedules units: stand-alone TestScripts and plans run in
  parallel with each other up to `execution.parallelism`; **the members of one plan run one after the other, in order**,
  on one thread. `suites[n].parallel: false` still means "alone, before the parallel ones", for plans too. The index of
  every TestScript execution is a run-wide counter in the order of the run: units in discovery order (suites, then
  files), the members of a plan in plan order. (Parallelism inside a plan would be faster, but cases may depend on
  earlier ones – `testCase.dependency` says so – and ADR-0008 keeps order wherever order can matter. Open decision D4.)
* **Outcome of a case that runs nothing** (the table is in the plan):
  * a source that does not resolve, is ambiguous, or lies outside the suite → `error` of the plan's `Load` phase, the
    case `error`, nothing of that plan is sent (checked before anything is sent, also by `--check`);
  * another script language, or narrative only → the case is `skip` with the reason, and a warning on the plan. A skip
    never turns an exit code red; the existing rule (exit 3 only if *every* TestScript is skipped, `exit-on-skip`) is
    unchanged. (Open decision D3: `error` would make a plan with one gherkin case permanently red.)
* **Outcome of a plan** is the combination of its cases, `error > fail > skip > pass`. A case's outcome is the combination
  of its TestScripts'. `exitCriteria` is text and does not change an outcome.
* **Run control.** Run timeout, cancel and fail-fast end a plan like they end a run: the TestScript running finishes
  (nothing is interrupted), the remaining members are `skip` "Not started: …" (`TestRunner.notRun`), the plan says so.
  A plan that did not finish when reports are forced (Ctrl-C) is `error` "Did not end", one not started `skip`.
* **Dependencies in a second step.** `dependency.predecessor` of plan and case is resolved among the suite's plans (by
  `TestPlan/<id>` or canonical); the predecessor runs first; a dependent whose predecessor did not `pass` is `skip`
  "Not started: predecessor 'x' ended with FAIL"; a cycle or an unresolved predecessor is a `Load` problem. In the
  first step the predecessor is *not evaluated* and says so in a warning on the plan (never silent).
* **Isolation.** Each TestScript execution of a plan has its own `ExecutionState`: no fixture, variable or response
  crosses from one case to the next. What one case leaves on the server stays there; a TestScript that needs data from
  another creates it itself (`${testrunner.testScriptId}` keeps parallel plans apart).
* **Code shape.** Plan assembly lives in classes of its own (`TestPlanExecutions`, `TestPlanChecks`) in `core.engine`;
  `TestRunner` calls them and keeps its shape (A5).

## Alternatives
* *A second runner for plans* (`TestPlanRunner` wrapping `TestRunner`) – duplicates configuration problems, packages,
  listeners, reports-always. ADR-0014 rejected the same for the checker.
* *Parallel TestScripts across plans' cases* – simpler scheduler, wrong when a case needs its predecessor's data.
* *Treat a plan as one very large TestScript* – see ADR-0022.
* *Make every case `fail` when it cannot run* – a plan is also documentation; a manual case is not a failed test.
* *A new `Outcome` (`MANUAL`)* – honest, but every `switch` over `Outcome`, every writer, the exit codes and the
  TestReport mapping change for one feature.
* *Share fixtures across a plan* – a second state model beside `ExecutionState`; ADR-0004 puts state with its owner.

## Consequences
* `RunScheduler` gets a unit abstraction; `RunProgress` records plans; both are internal.
* The cost of a plan is the cost of its TestScripts; the same server data can be touched in a known order.
* A plan with a failing case does not stop later cases (unless fail-fast); each case is a test on its own.
* A TestScript in two cases is two executions, with two indexes.

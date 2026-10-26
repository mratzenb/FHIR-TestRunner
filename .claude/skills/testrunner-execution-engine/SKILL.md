---
name: testrunner-execution-engine
description: How the FHIR TestScript Runner (TestRunnerOnJava) executes a TestScript – run flow, TestScriptExecution phase rules, outcomes (pass/fail/error/skip), operations and URL building, assertions (the sealed Check type, operators, unsupported checks), fixtures with autocreate/autodelete, variables, request ids and parallel execution. Use when changing or debugging engine behaviour in testrunner-core, adding an assertion type or operation, or explaining why a TestScript passed, failed or errored.
---

# Execution engine (`testrunner-core`, package `at.hl7.testing.runner.core.engine`)

## Run flow

1. `TestRunner.run(RunConfiguration)` – checks `RunConfiguration.problems()`, unknown report formats and
   unsupported pinned versions (any problem ⇒ `ConfigurationException`, exit 2); creates a `RunContext` (run id
   `run_yyyy-MM-dd_HH-mm-ss-SSS` = run folder name under `output-dir`, run-wide `AtomicInteger` request counter);
   notifies `RunListener`s.
2. `source.TestScriptDiscovery` lists files per suite (glob `include` relative to `Test_Scripts/`, stable order).
3. Per file (in parallel if `execution.parallelism > 1`): `source.TestScriptLoader` detects or takes the pinned FHIR
   version, maps the file via that version's `FhirVersionSupport`, loads fixture resources from `Example_Instances/`
   (`ExampleInstanceResolver`).
4. Before anything is sent: `TestScriptChecks.problems` (unique fixture/responseId/requestId ids; a variable has at
   most one of expression/path/headerField, and at least one of them or a `defaultValue`; at most one server
   addressed via `TestScript.destination`/`operation.destination`) and, if
   `validation.validate-test-scripts`, the
   validator checks the TestScript against its base spec. Problems ⇒ phase `Load` = `error`, nothing sent.
5. `TestScriptExecution` checks `metadata.capability` (`CapabilityCheck`, see below), then runs setup → tests →
   teardown with its own `ExecutionState` (fixtures, responses by `responseId`, last response). Helpers:
   `VariableResolver`, `RequestFactory`, `AssertionEvaluator`, `FixtureManager`, `PhaseRecorder`.
6. Every request goes through `http.FhirHttpClient` (request id, log line, communication folder).
7. `TestScriptResult`s → `RunResult` (in discovery order) → `ReportWriter`s → `RunListener.runFinished`.

The MDC key `testScript` (`TestRunner.MDC_TEST_SCRIPT`) = `<NN>_<file base name>` while a TestScript runs.

## Phase and outcome rules (`TestScriptExecution`)

| Situation | Recorded | Effect |
|---|---|---|
| Assertion does not hold (`AssertionFailure`) | `fail` | phase continues |
| … with `stopTestOnFail` | `fail` | current phase stops; next test still runs |
| … with `warningOnly` | `pass` + warning | wins over `stopTestOnFail` |
| Action cannot be executed (`TestScriptException`, `ValidatorUnavailableException`, `FhirHttpClient.ExchangeFailedException`) | `error` | current phase stops |
| `validateProfileId` while validation disabled (`ValidationSkipped`) | `pass` + warning | never a silent pass |
| Setup not `pass` | tests `skip` | teardown and autodelete still run |
| TestScript unreadable / invalid, or addresses > 1 server (`destination`) | phase `Load` `error` | other TestScripts continue |
| Server lacks a required `metadata.capability` | Setup + tests `skip`, differences listed | only `GET [base]/metadata` sent; no fixtures, no teardown |
| Required capability not checkable (statement not in Example Instances, no CapabilityStatement from server) | warning on Setup | TestScript runs |
| Content the version ignores (detection warnings, e.g. R5-only element in R4) | warning on the `Setup` phase (always present) | outcome unchanged |
| No FHIR server configured | `skip` | – |

Precedence: `error > fail > skip > pass` (`report.Outcome.and`, enum order `PASS, SKIP, FAIL, ERROR`).
Phases: `PhaseKind.LOAD | SETUP | TEST | TEARDOWN`. Exception choice is the API: throw `AssertionFailure` for "checked
and wrong", `TestScriptException` for "cannot be evaluated".

## Operations (`model.Operation`, `RequestFactory`)

* `OperationType.fromCode` maps both R4 (`testscript-operation-codes`) and R5 (`restful-interaction`) codes;
  unknown ⇒ `OTHER`, which needs explicit `method` and `url`/`params`. `defaultMethod()`: read/vread/history/search/
  capabilities GET, update PUT, patch PATCH, delete DELETE, create/transaction/batch/operation POST.
* URL precedence: `url` as is → `[base]/[resource][params]` → derived from type: `[base]` for transaction/batch,
  `[base]/metadata` for capabilities, `[base]/[type]/[id](/_history(/[vid]))` from `targetId`, `[base]/[type]`.
* Body from `sourceId` (fixture or kept response). `responseId` keeps the response for later `sourceId`/variables.
* Every text value of an operation or assertion may contain `${variable}` (`withValues`), as in the PoC.

**Adding an operation type** – only needed for a default method or URL; an unknown code already runs as `OTHER`
with explicit `method` and `url`/`params`. A constant in `OperationType` → its R4 and R5 codes in `fromCode` → a
case in `defaultMethod()` (exhaustive switch, will not compile without it) → `RequestFactory`, only where the type
differs: `check` (forbidden `targetId`/`params`), `body` (may send without `sourceId`, like search/operation/
capabilities), `url` (fixed URL like transaction/batch/capabilities, suffix like history), `targetUrl` (suffix after
`[type]/[id]`, like vread/history), `needsResource` (needs a resource type with `params`) → `TestScriptMapper`
needs nothing (it calls `fromCode`) → tests in `TestScriptExecutionTest.Operations` asserting
`server.requestLines()`, extending `InMemoryFhirServer` if it must answer the new request; if a sample uses it,
`R4SupportTest.mapsEveryOperationTypeOfTheSampleScript` and `EndToEndIT`.

## Assertions (`model.Assertion`, sealed `model.Check`, `AssertionEvaluator`)

One `Check` per assertion (FHIR invariants tst-5/tst-6, enforced when mapping): `ContentType`, `Expression`
(FHIRPath, version-correct), `HeaderField`, `Path` (JSONPath/XPath via `content.PathEvaluator`), `Resource`,
`Response` (code + HTTP status from the version's table), `ResponseCode`, `ValidateProfile`.
**Not supported, reported as `error`**: `MinimumId`, `NavigationLinks`, `RequestMethod`, `RequestUrl`,
`direction=request`, operator `manualEval`.

* Source: `sourceId` (fixture or response) else the last response; none ⇒ error.
* `operator(assertion, default, allowed...)` fixes default and allowed operators per check; `Operators.apply` does
  the comparison (`equals`, `notEquals`, `in`, `notIn`, `greaterThan`, `lessThan`, `empty`, `notEmpty`, `contains`,
  `notContains`, `eval`).
* Expected value: `value`, or `compareToSourceId` + `compareToSourceExpression`/`compareToSourcePath`.
* `validateProfileId` must name an id in `TestScript.profile` (else error listing declared ids); see
  `testrunner-validation`.

**Adding a check**: a nested record in the sealed interface `core.model.Check` (the unsupported checks are records
there too) → read it in `TestScriptMapper.assertion` (`testrunner-fhir-versions`) → a
`case` in the `switch` of `AssertionEvaluator.evaluate` (the sealed switch will not compile without it) →
`core.engine.Describe.assertion` if it needs a readable description → tests in `TestScriptExecutionTest` (via `Harness`/`Scripts`)
and, if a sample uses it, `EndToEndIT`.

## Fixtures (`FixtureManager`) and variables (`VariableResolver`)

* `autocreate` fixtures are created in dependency order (Kahn's algorithm, `creationOrder`); references between them
  are rewritten to server-assigned ids; a cycle ⇒ setup `error`, tests skipped. Rejected create ⇒ error naming the
  request id and status. `autodelete` after teardown, reverse order.
* Variables are evaluated **at each use** (latest response under that id), from `expression` (FHIRPath),
  `path`, or `headerField`, on `sourceId`; `defaultValue` if empty. Undefined variable ⇒ `TestScriptException`.

## Required capabilities (`CapabilityCheck`) and destinations

* `metadata.capability` → `model.CapabilityRequirement`. Only `required = true` is checked. The CapabilityStatement
  named by `capabilities` is loaded with the TestScript from Example Instances (`ExampleInstanceResolver.find`, like a
  fixture: `CapabilityStatement-<id>.json|xml`; a `|version` is ignored) into `LoadedTestScript.capabilities()`.
* The server's statement: `GET [base]/metadata`, once per base URL per run (one `CapabilityCheck` per run, cache is
  a `ConcurrentHashMap`), sent with the client of the first TestScript needing it.
* Comparison: `content.FhirElement` (JSON and XML in one shape, `_x` primitive extensions merged) →
  `only(CapabilityCheck.COMPARED)` (RESTful interface: fhirVersion, formats, resource types, interactions, search
  params, operations, conditional/versioning flags) → `notContainedIn(server)`, the `minimumId` rules (order free,
  extra allowed, duplicates need own counterpart; bipartite matching). Every difference is a message.
* Destinations: `TestScript.destination` → `model.Destination`; `operation.destination` → `Operation.destination()`.
  The runner plays every origin and sends everything to the one configured server; > 1 server addressed ⇒ Load
  error (`TestScriptChecks`). One undeclared `destination: 1` is fine (common in IG TestScripts).

## Parallelism (ADR-0008)

`execution.parallelism` (default 1) = TestScripts at once on a fixed pool of platform threads `testscript-N`; the
actions of one TestScript always run in order on one thread. Shared: HTTP transport, request counter, validator,
report list. Results are reported in discovery order. TestScripts touching the same server data can interfere –
that is why the default is 1.

## Where to look

`TestScriptExecution` (rules), `AssertionEvaluator` (checks), `RequestFactory` (URLs/headers/body),
`FixtureManager`, `VariableResolver`, `TestScriptChecks`, `CapabilityCheck`, `TestRunner` (orchestration, logging of
config/summary).
Docs: `docs/architecture.md` › Runtime view, Execution semantics, Open points; ADR-0004, ADR-0008.

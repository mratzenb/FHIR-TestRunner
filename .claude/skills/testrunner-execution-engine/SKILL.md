---
name: testrunner-execution-engine
description: How the FHIR TestScript Runner (TestRunnerOnJava) executes a TestScript – run flow, TestScriptExecution phase rules, outcomes (pass/fail/error/skip), operations and URL building, assertions (the sealed Check type, operators, unsupported checks), fixtures with autocreate/autodelete, variables, request ids and parallel execution. Use when changing or debugging engine behaviour in testrunner-core, adding an assertion type or operation, or explaining why a TestScript passed, failed or errored.
---

# Execution engine (`testrunner-core`, package `at.hl7.testing.runner.core.engine`)

## Run flow

1. `TestRunner.run(RunConfiguration)` – checks `RunConfiguration.problems()`, resolves suites with `package-id`
   (`source.PackageSuites`: package cache, else download from the registry; `package.json` gives folder layout,
   FHIR version and `igs`; a problem ⇒ `ConfigurationException`), unknown report formats and
   unsupported pinned versions, `ProfileValidator.configurationProblems()` (any problem ⇒ `ConfigurationException`, exit 2); creates a `RunContext` (run id
   `run_yyyy-MM-dd_HH-mm-ss-SSS` = run folder name under `output-dir`, run-wide `AtomicInteger` request counter;
   package-private), notifies `RunListener`s with `RunContext.info()` = `RunInfo` (run id, start, configuration, run
   folder – no counter, no tokens).
2. `source.TestScriptDiscovery` lists files per suite (glob `include` relative to `Test_Scripts/`, stable order).
   Then `TestRunner.select` keeps what `RunConfiguration.select` selects (see Selection below); the index counts
   only selected TestScripts.
3. Per file (`RunScheduler`: in parallel if `execution.parallelism > 1`): `source.TestScriptLoader` detects or takes the pinned FHIR
   version, maps the file via that version's `FhirVersionSupport`, loads fixture resources: `#id` references from
   `TestScript.contained` (`ResourceContent.contained` on the file, JSON/XML, version-neutral; unknown id ⇒ Load
   error listing `containedIds`), others from `Example_Instances/` (`ExampleInstanceResolver`).
4. Before anything is sent: `TestScriptChecks.problems(testScript, fixtureResources, builtInNames)` (unique
   fixture/responseId/requestId ids; a variable has at most one of expression/path/headerField, and at least one of
   them or a `defaultValue`; at most one server addressed via `TestScript.destination`/`operation.destination`;
   `References` walks setup → tests → teardown in order: `ActionRules.problems(operation|assertion)` (see below),
   every `sourceId`/`targetId`/`compareToSourceId`/`minimumId`
   names a fixture with resource or a responseId kept **before** (else "is not there yet"), `targetId` fixture needs
   autocreate, every `${name}` (also in fixture resources, collected via `withValues`/`replaceText` +
   `VariableResolver.placeholders`) is defined, a used variable's source exists at that point, no last-response
   variable/assertion before the first operation, `validateProfileId` declared; ids containing `${` are skipped;
   only "fails in every run" problems, each placeholder reported once) and, if `validation.validate-test-scripts`,
   the validator checks the TestScript against its base spec. Problems ⇒ phase `Load` = `error`, all listed,
   nothing sent. Messages name the place: `Test 'read', action 2: …`.
5. `TestScriptExecution(loaded, ExecutionEnvironment)` (environment from `ExecutionFactory.create`: client, base,
   validator, profiles dir, IGs, `CapabilityCheck`, built-in values, rewriting, Setup warnings) checks
   `metadata.capability` (`CapabilityCheck`, see below), then runs setup → tests → teardown with its own
   `ExecutionState` (fixtures, responses by `responseId`, last response). Helpers:
   `VariableResolver`, `RequestFactory`, `AssertionEvaluator`, `FixtureManager`, `PhaseRecorder`.
6. Every request goes through `http.FhirHttpClient` (request id, log line, communication folder). Bodies are
   `model.Body` throughout (bytes + charset + format, `text()` lazily decoded, `isText()`; M5.3): `FhirRequest.body()`
   and `FhirResponse.body()` are `Body`s, `JdkHttpTransport` sends/reads bytes (`FhirResponse.of(…, byte[])`), so a
   binary response kept by `responseId` is re-sent unchanged by `sourceId` (`RequestFactory` passes the `Body`).
7. `TestScriptResult`s → `RunResult` (in discovery order) → `ReportPublisher` (`ReportWriter`s, summary log) →
   `RunListener.runFinished` – exactly once per started run, whatever ends it (see Run control).

**Check mode (F1, ADR-0014).** `TestRunner.check(configuration[, control])` = steps 1–4, then stop: same `start`/
`runOne`, private enum `TestRunner.Mode` (`RUN`, `CHECK`). Per TestScript one phase `Load`: problems ⇒ `error`
(tests **not** added as skip), else `pass` with `WARNING:` messages (read warnings, `unsupportedWarnings`,
`orderWarnings`; in a run these go on the Setup) via `TestRunner.checked`. Run id/folder `check_<timestamp>`
(`RunContext.start(…, kind)`); no server needed; `validator.configurationProblems()` only asked when
`validate-test-scripts` is on; fail-fast and `test-script-timeout` ignored, run timeout/cancel apply;
`ReportPublisher.publish(run, result, check)` skips format `testreport` and logs "===== Check …". App:
`--check` = `testrunner.mode=check` (`RunCommand.Mode`), exit codes as for a run.

**Selection (F2, ADR-0014).** `config.SelectConfig(testScripts, tests)` (globs; empty = all; blank entry ⇒
`problems()`), applied by package-private `engine.Selection` (`glob`: `*`/`?`, case-insensitive, whole text,
everything else literal). TestScript selected if a pattern matches file name ± extension, `name` or `url`, and – with
test patterns – one of its tests; unreadable file (`selects(source, null)`) only via a TestScript pattern on its file
name. Deciding by name/url/tests reads the file once (`TestScriptLoader.map` = read + map, no fixtures); a file-name
match (`selectsByFileName`, only without test patterns) reads nothing. In `runOne`, `selected(loaded, selection)`
→ `LoadedTestScript.withTestScript(testScript.withTests(selectedTests))`: deselected tests are neither run nor
reported (no `skip`), setup/teardown always run; log "Selected n of m TestScript(s) (…); k left out" and "x of y
test(s) selected: […]". `TestScriptChecks.problems(…, leftOut)` with `TestScriptChecks.keptBy(deselected tests)`:
an id kept only by a deselected test ⇒ Load error "…, which is kept by Test 'x', action 1, a test that is not
selected (testrunner.select.tests); select that test too" (reported once per id). `TestRunner.list(configuration)`
→ `List<TestRunner.Listed>` (index, suite, file, version, name, url, selected test names, testCount, problem): reads,
sends and writes nothing, no listeners. App: `--testscript`/`--test` (repeatable, comma-separated) →
`testrunner.select.*[i]`; `--list` = `testrunner.mode=list`, `RunCommand.list(listed, select)` prints to stdout, exit
0 (1 if a TestScript cannot be read). Tests: `SelectionTest`, `RunCommandTest`, `EndToEndIT`.

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
| Assertion the runner cannot evaluate (`requestMethod`, `requestURL`, `manualEval`) | warning on the Setup before the run, `error` when it runs | its phase stops |
| No FHIR server configured | `skip` | – |
| `execution.test-script-timeout` passed, run `timeout` passed or run cancelled (`Deadline.reason()`, asked before every setup/test action) | current phase `error` "Stopped before the next action: …", remaining tests `skip` | teardown + autodelete still run (never stopped); a running request is not interrupted |
| Run stopped (timeout, `RunControl.cancel`) or `fail-fast` after a `fail`/`error` – TestScript not started yet | `skip`, phase `Load` "Not started: …" (`TestRunner.notRun`) | `RunScheduler` asks `RunControl.startReason()` before each start; fail-fast lets running ones finish |

Precedence: `error > fail > skip > pass` (`report.Outcome.and`, enum order `PASS, SKIP, FAIL, ERROR`).
Phases: `PhaseKind.LOAD | SETUP | TEST | TEARDOWN`. Exception choice is the API: throw `AssertionFailure` for "checked
and wrong", `TestScriptException` for "cannot be evaluated".

## Operations (`model.Operation`, `RequestFactory`)

* `OperationType.fromCode` maps both R4 (`testscript-operation-codes`) and R5 (`restful-interaction`) codes;
  unknown ⇒ `OTHER`, which needs explicit `method` and `url`/`params`. `defaultMethod()`: read/vread/history/search/
  capabilities GET, update PUT, patch PATCH, delete DELETE, create/transaction/batch/operation POST.
* URL precedence: `url` as is → `[base]/[resource][params]` → derived from type: `[base]` for transaction/batch,
  `[base]/metadata` for capabilities, `[base]/[type]/[id](/_history(/[vid]))` from `targetId`, `[base]/[type]`.
* Body from `sourceId` (fixture, kept response or kept request). `responseId` keeps the response, `requestId` the
  request as sent (placeholders resolved), for later `sourceId`/variables.
* What an operation needs whatever its values are is in `ActionRules.problems(operation)`: no `targetId` on
  search/create, no `params` on create/transaction/batch, a method (explicit or the type's default), a `sourceId`
  for POST/PUT/PATCH except search/operation/capabilities, enough for a URL (`url`, or `params` + resource for
  read/vread/update/delete/patch, or type with fixed URL/`targetId`/`resource`/`sourceId`). Checked before the run
  (`TestScriptChecks` ⇒ Load error, nothing sent) and again in `RequestFactory.create`.
* `update` to an instance URL `[base]/[type]/[id]` (type = the body's resource type) sets the body's id to `[id]`;
  any other URL, e.g. a conditional update `[base]/[type]?[search]`, sends the body unchanged (`instanceId`).
* Every text value of an operation or assertion may contain `${variable}` (`withValues`), as in the PoC.

**Adding an operation type** – only needed for a default method or URL; an unknown code already runs as `OTHER`
with explicit `method` and `url`/`params`. A constant in `OperationType` → its R4 and R5 codes in `fromCode` → a
case in `defaultMethod()` (exhaustive switch, will not compile without it) → `ActionRules`, only where the type
differs: `problems` (forbidden `targetId`/`params`), `sendsWithoutSource` (may send without `sourceId`, like
search/operation/capabilities), `fixedUrl` (like transaction/batch/capabilities), `needsResource` (needs a resource
type with `params`) → `RequestFactory`: `url` (fixed URL, suffix like history), `targetUrl` (suffix after
`[type]/[id]`, like vread/history) → `TestScriptMapper`
needs nothing (it calls `fromCode`) → tests in `OperationsTest` asserting
`server.requestLines()`, extending `InMemoryFhirServer` if it must answer the new request; if a sample uses it,
`R4SupportTest.mapsEveryOperationTypeOfTheSampleScript` and `EndToEndIT`.

## Assertions (`model.Assertion`, sealed `model.Check`, `AssertionEvaluator`)

One `Check` per assertion (FHIR invariants tst-5/tst-6, enforced when mapping): `ContentType`, `Expression`
(FHIRPath, version-correct), `HeaderField`, `Path` (JSONPath/XPath via `content.PathEvaluator`), `Resource`,
`Response` (code + HTTP status from the version's table), `ResponseCode`, `ValidateProfile`, `MinimumId`
(source contains every element/value of the fixture, id ignored; `content.FhirElement.notContainedIn`, same rules as
the capability check, all differences listed), `NavigationLinks` (Bundle has `first`, `last` and `next` links;
`false` expects them incomplete; non-Bundle ⇒ fail). Both only allow operator `equals` (the default).
**Not supported, reported as `error` when they run**: `RequestMethod`, `RequestUrl` (would test a client; the runner
is the client), operator `manualEval`. `ActionRules.supported(check)` (sealed switch) and
`ActionRules.unsupported(assertion)` (the reason, or `null`) decide; `AssertionEvaluator.evaluate` throws that
reason first. Before anything is sent, `TestScriptChecks.unsupportedWarnings` (in `TestRunner.execute`, logged
WARN) turns each into a **warning on the Setup**, `Test 'x', action 2: requestURL is not supported – …; the
assertion will be an error when it runs` (decision M1.3: no `Load` error, the rest of the TestScript runs). With
`Load` problems the warnings follow them in the `Load` phase. `ActionRules.problems` skips the operator check of an
unsupported assertion.

* Source: `sourceId` (fixture, response or **request** – `Source.Request`, kept by `operation.requestId` via
  `ExecutionState.exchanged`) else the last response, or the last request with `direction = request`
  (`AssertionEvaluator.onRequest`); none ⇒ error. A request has headers and body but no status: `response`/
  `responseCode` on it ⇒ error; `targetId` naming a requestId ⇒ Load error (`TestScriptChecks`).
* `ActionRules.defaultOperator(check)`/`allowedOperators(check)` (sealed switches) fix default and allowed operators
  per check; `ActionRules.operator(assertion)` picks the operator or throws. An operator outside the list, or one
  that `Operators.needsValue` without `value`/`compareToSourceId` on headerField/path/expression, is a **Load** error
  (`ActionRules.problems(assertion)` from `TestScriptChecks`). `Operators.apply(op, Object actual, Object expected)`
  does the comparison (`equals`, `notEquals`, `in`, `notIn`, `greaterThan`, `lessThan`, `empty`, `notEmpty`,
  `contains`, `notContains`, `eval`). **Typed (M5.2, ADR-0016):** an `expression` value keeps its FHIRPath type
  (`Values.value`: `BigDecimal` – integers converted –, `Boolean`, `model.FhirTemporal` for date/dateTime/instant/time
  from `HapiFhirPathEvaluator`, else `String`), also `compareToSourceExpression`; `Values.equal`/`order` read the other
  side as that type. `FhirTemporal.compare`: FHIRPath rules (offsets normalised to UTC, seconds+fraction one precision,
  a precision only one side has ⇒ `IllegalArgumentException` ⇒ `Values.IncomparableException` ⇒ `AssertionFailure`
  "…, but 'a' and 'b' cannot be compared: only 'b' has a day" – a **fail**, not an error). String vs String: `equals`
  text, `greaterThan`/`lessThan` numbers, then dates, then lexicographic. `contains` on text. `in`: a match wins,
  otherwise an incomparable entry fails. Variables stay text (`Values.single`). Tests: `OperatorsTest`,
  `FhirTemporalTest`.
* Expected value: `value`, or `compareToSourceId` + `compareToSourceExpression`/`compareToSourcePath`.
* `validateProfileId` must name an id in `TestScript.profile` (else error listing declared ids); see
  `testrunner-validation`.

**Adding a check**: a nested record in the sealed interface `core.model.Check` (the unsupported checks are records
there too) → read it in `TestScriptMapper.assertion` (`testrunner-fhir-versions`) → its operators and name in
`ActionRules.defaultOperator`/`allowedOperators`/`checkName`/`supported` → a
`case` in the `switch` of `AssertionEvaluator.evaluate` (the sealed switches will not compile without them) →
`core.engine.Describe.assertion` if it needs a readable description → tests in `AssertionsTest` and, for the rules,
`ChecksBeforeSendingTest` (via `Harness`/`Scripts`) and, if a sample uses it, `EndToEndIT`. Making
`requestMethod`/`requestURL` work means `supported` → `true` and a real case in `evaluate`.

## Fixtures (`FixtureManager`) and variables (`VariableResolver`)

* `autocreate` fixtures are created in dependency order (Kahn's algorithm, `creationOrder`); references between them
  are rewritten to server-assigned ids; a cycle ⇒ setup `error`, tests skipped. Rejected create ⇒ error naming the
  request id and status. `autodelete` after teardown, reverse order.
* Variables are evaluated **at each use** (latest response under that id), from `expression` (FHIRPath),
  `path`, or `headerField`, on `sourceId`; `defaultValue` if empty. Undefined variable ⇒ `TestScriptException`.
* Built-in values (`VariableResolver.BUILT_IN` = `testrunner.`, built by `ExecutionFactory.builtInValues`, fixed per
  execution): `${testrunner.runId}`, `${testrunner.testScriptId}` (`run-…-NN`, a valid FHIR id), `${testrunner.uuid}`,
  `${testrunner.timestamp}`, `${testrunner.date}`. Unknown `testrunner.x` ⇒ error listing the known ones.
* **Fixtures are resolved at each use** (`docs/wiki/fixtures.md`): `ExecutionState` keeps them as
  written; `find`/`require` hand them out through `FixtureManager.resolve` (set via `state.resolveFixturesWith`):
  placeholders replaced by `ResourceContent.replaceText` (JSON string values, XML attribute values/text; never
  names) with the values at that moment, and references to fixtures created on the server rewritten to the server
  ids (`ExecutionState.serverReference`, keyed by `localReference`). A failing placeholder ⇒ `error` of the action using
  the fixture, naming it; a variable reading its own fixture ⇒ error (cycle guard `resolving`).
  `state.fixtureAsWritten` bypasses the view (autocreate). Autocreated fixtures are resolved once in `autocreate`
  (no response yet ⇒ a response variable fails there ⇒ Setup `error`); afterwards `find` returns them as sent.
* **Reference rewriting is runner-specific, not spec** (ADR-0012): `execution.rewrite-references`
  (`config.ReferenceRewriting`): `NONE` = sent as written, autocreate in declaration order (nothing recorded in
  `serverReferences`); `AUTOCREATE` (default) = autocreate in dependency order (`dependencyOrder` → `creationOrder`,
  Kahn), references to autocreated fixtures rewritten; `ALL` = also `FixtureManager.sentByOperation` after a
  successful create/update (`createsResource`) with a fixture `sourceId`: server id from `Location`, else returned
  resource, else update URL → `state.serverAssigned(Type/writtenId, Type/serverId)`. Actions are never reordered.
* **Order warnings**: `TestScriptChecks.orderWarnings` (in `TestRunner.execute`, logged WARN, passed with the
  unsupported-assertion warnings as `warnings` and added to the Setup by `TestScriptExecution.setupRecorder`): a create/update sending a non-autocreate fixture whose references name a
  fixture first created by a later create/update ⇒ warning naming both actions; never an error (server may have it).

## Required capabilities (`CapabilityCheck`) and destinations

* `metadata.capability` → `model.CapabilityRequirement`. Only `required = true` is checked. The CapabilityStatement
  named by `capabilities` is loaded with the TestScript from Example Instances (`ExampleInstanceResolver.find`, like a
  fixture: `CapabilityStatement-<id>.json|xml`; a `|version` is ignored) into `LoadedTestScript.capabilities()`.
* The server's statement: `GET [base]/metadata`, once per base URL per run (one `CapabilityCheck` per run, cache is
  a `ConcurrentHashMap`), sent with the client of the first TestScript needing it.
* Comparison: `content.FhirElement` (JSON and XML in one shape, `_x` primitive extensions merged, decimals exact
  via `content.Json`) → `only(CapabilityCheck.COMPARED)` (RESTful interface: fhirVersion, formats, resource types,
  interactions, search params, operations, conditional/versioning flags) → `notContainedIn(server, path,
  CapabilityCheck.AT_LEAST)`: the `minimumId` rules (order free, extra allowed, duplicates need own counterpart;
  bipartite matching), but a value is met by more support (ordered codes, flags, `full-support`, format mime types).
  `minimumId` itself uses `FhirElement.EQUAL`. Every difference is a message.
* The required CapabilityStatement is looked up in Example Instances, then next to the TestScripts (package root).
* Destinations: `TestScript.destination` → `model.Destination`; `operation.destination` → `Operation.destination()`.
  The runner plays every origin and sends everything to the one configured server; > 1 server addressed ⇒ Load
  error (`TestScriptChecks`). One undeclared `destination: 1` is fine (common in IG TestScripts).

## Run control (ADR-0013)

`ExecutionConfig.timeout`, `testScriptTimeout` (both `Duration`, null = none, must be > 0) and `failFast` (default
false). `RunControl` (public, one per run, `TestRunner.run(configuration, control)`; `run(configuration)` makes one;
reuse ⇒ `IllegalStateException`): `cancel(reason)` (public, first reason wins), `isCancelled()`; package-private
`begin` (run deadline = start + timeout), `stopStarting` (fail-fast), `abortReason()` (cancel or run timeout),
`startReason()` (abort or fail-fast), `applied`/`stopReason()` (logged "The run ended early: …"). `Deadline` (per
execution, in `ExecutionEnvironment`) = `RunControl.abortReason()` or the TestScript's own deadline. Checked
**between actions only**, nothing is interrupted; `TestScriptExecution.runActions(…, stoppable)` – teardown is
`stoppable = false`. Messages: "Stopped before the next action: the TestScript timeout of 2m has passed",
"Skipped: …", "Not started: fail-fast: TestScript 02_b ended with FAIL".

**Reports always (M2.4).** `TestRunner.run(configuration, control)` → `start` in a `try/finally` that calls
`control.finish()` (also on a `ConfigurationException`, so `awaitFinished` returns). `RunProgress` records found /
started / finished TestScripts; `publish(run, progress, reason)` writes reports + `runFinished` **once**
(`publishOnce`): at the end, after any `RuntimeException`/`Error` (logged, published, rethrown), or from
`RunControl.reportNow()` (shutdown that cannot wait). A TestScript without a result: started ⇒ `error` "Did not end:
<reason>", else `skip` "Not started: <reason>" (`unfinished`). Listener calls go through `notify`: a throwing listener
is logged, never stops the run. App: `RunCommand` (`ContextClosedEvent`) → `stop(control, testrunner.shutdown-grace)`.
Tests: `RunControlTest` (uses `InMemoryFhirServer.delay`, `Harness.limits`/`control`/`failingOn`/`listener`),
`RunCommandTest.stop…`, `ShutdownIT` (app in its own JVM, SIGTERM via `Process.destroy`).

## Parallelism (ADR-0008)

`execution.parallelism` (default 1) = TestScripts at once on a fixed pool of platform threads `testscript-N`; the
actions of one TestScript always run in order on one thread. Shared: HTTP transport, request counter, validator,
report list, `CapabilityCheck`. Results are reported in discovery order. TestScripts touching the same server data can
interfere – that is why the default is 1. Remedies: `${testrunner.testScriptId}`/`uuid` in fixtures (data of their
own), or `suites[n].parallel: false` (`SuiteConfig.parallel`): with parallelism > 1, `RunScheduler.runAll` runs those
TestScripts one after the other first, then the rest in the pool.

## Where to look

`TestScriptExecution` (rules), `ActionRules` (what an action needs, before and during the run),
`AssertionEvaluator` (checks), `RequestFactory` (URLs/headers/body),
`FixtureManager`, `VariableResolver`, `TestScriptChecks`, `CapabilityCheck`, `TestRunner` (orchestration: configuration
problems, run folder, listeners; per TestScript load → checks → execution), `RunScheduler` (serial suites, thread pool),
`ExecutionFactory` (`ExecutionEnvironment` of one execution: `FhirHttpClient`, communication log, headers, OAuth2,
`builtInValues`), `ReportPublisher` (report writers, summary log).
Docs: `docs/architecture.md` › Runtime view, Execution semantics, Open points; ADR-0004, ADR-0008, ADR-0013, ADR-0014,
ADR-0016; `CHANGELOG.md` for changes of results.

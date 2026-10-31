# Architecture

The FHIR TestScript Runner reads FHIR `TestScript` resources, executes them against a FHIR server and reports
the results. This document describes how the Java implementation is structured and why. The individual decisions
are recorded as ADRs in [`adr/`](adr/); [`fhir-versions.md`](fhir-versions.md), [`configuration.md`](configuration.md),
[`logging.md`](logging.md) and [`validation.md`](validation.md) go into detail on the four topics that shaped the
design most.

## Goals and constraints

| Requirement (from the brief) | Where it is addressed |
|---|---|
| Clean, simple architecture with clean modules | Hexagonal core plus adapters, one Maven module per adapter ([ADR-0001](adr/0001-hexagonal-modules.md)) |
| Java 25, Maven, versions in properties | Root `pom.xml`: BOM imports, `*.version` properties, enforcer ([ADR-0010](adr/0010-build.md)) |
| Spring Boot 4 where it makes sense | Only at the edge: auto-configuration + CLI app; the core is framework-free ([ADR-0002](adr/0002-spring-boot-at-the-edge.md)) |
| Externalizable configuration, dev config in the project | Immutable `RunConfiguration`, bound from `testrunner.*` by Spring Boot; dev files in `config/` ([ADR-0003](adr/0003-configuration.md)) |
| No global variables | All state is owned by a run (`RunContext`) or a TestScript execution (`ExecutionState`) ([ADR-0004](adr/0004-no-global-state.md)) |
| Optional parallel execution of TestScripts | `testrunner.execution.parallelism`, a thread pool over TestScripts ([ADR-0008](adr/0008-parallel-execution.md)) |
| Logging and communication logging | SLF4J with MDC per TestScript, run log file, one folder per HTTP exchange ([ADR-0007](adr/0007-logging.md)) |
| IG/profile validation like the PoC; jar path configurable | `ProfileValidator` port, HL7 Validator CLI adapter, `testrunner.validation.validator-jar` ([ADR-0006](adr/0006-validator-cli.md)); server mode (default) with fallback to a process per validation ([ADR-0011](adr/0011-validator-server-mode.md)) |
| R4 and R5 now, R6 later; mapping understandable, extensible, no duplication | Version-neutral model, one generic HAPI-based mapper, a tiny module per version ([ADR-0005](adr/0005-fhir-version-mapping.md)) |
| Tests without a FHIR server, using the PoC TestScripts | In-memory FHIR server and fake validator jar in `testrunner-test-support`; PoC data in `testdata/` ([ADR-0009](adr/0009-testing.md)) |
| A JAR taking a configuration object and a modifiable Spring configuration | `testrunner-app-*-exec.jar`; `TestRunner.run(RunConfiguration)`; every auto-configured bean is `@ConditionalOnMissingBean` |

## Modules

<figure>
<svg viewBox="0 0 860 430" role="img" aria-label="Maven modules and their dependencies: testrunner-core in the centre depends on nothing of the project; the FHIR modules, the validation adapter and the Spring auto-configuration depend on the core; the app depends on all of them." xmlns="http://www.w3.org/2000/svg" style="max-width:100%;height:auto;font-family:sans-serif;font-size:12px">
  <defs>
    <marker id="dep" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
      <path d="M0,0 L10,5 L0,10 z" fill="currentColor"/>
    </marker>
  </defs>
  <g fill="none" stroke="currentColor" stroke-width="1.3">
    <!-- app -->
    <rect x="330" y="10" width="200" height="52" rx="6"/>
    <!-- autoconfigure -->
    <rect x="30" y="120" width="230" height="52" rx="6"/>
    <!-- validation-cli -->
    <rect x="600" y="120" width="230" height="52" rx="6"/>
    <!-- fhir-r4 / r5 / (r6) -->
    <rect x="300" y="120" width="120" height="52" rx="6"/>
    <rect x="440" y="120" width="120" height="52" rx="6"/>
    <rect x="370" y="200" width="120" height="44" rx="6" stroke-dasharray="5 4"/>
    <!-- fhir-hapi -->
    <rect x="330" y="270" width="200" height="52" rx="6"/>
    <!-- core -->
    <rect x="230" y="360" width="400" height="58" rx="6" stroke="#2f7d6d" stroke-width="2.2"/>
  </g>
  <g fill="currentColor" text-anchor="middle">
    <text x="430" y="33" font-weight="bold">testrunner-app</text>
    <text x="430" y="50">CLI, exec jar, end-to-end ITs</text>
    <text x="145" y="143" font-weight="bold">spring-boot-autoconfigure</text>
    <text x="145" y="160">binds testrunner.*, wires beans</text>
    <text x="715" y="143" font-weight="bold">validation-cli</text>
    <text x="715" y="160">validator_cli.jar: process | server</text>
    <text x="360" y="143" font-weight="bold">fhir-r4</text>
    <text x="360" y="160">context + dialect</text>
    <text x="500" y="143" font-weight="bold">fhir-r5</text>
    <text x="500" y="160">context + dialect</text>
    <text x="430" y="226">fhir-r6 (future)</text>
    <text x="430" y="293" font-weight="bold">fhir-hapi</text>
    <text x="430" y="310">generic mapper, FHIRPath</text>
    <text x="430" y="384" font-weight="bold">testrunner-core</text>
    <text x="430" y="402">model · engine · HTTP · reports · ports (SPI)</text>
  </g>
  <g stroke="currentColor" stroke-width="1.2" fill="none" marker-end="url(#dep)">
    <line x1="380" y1="62" x2="180" y2="120"/>
    <line x1="410" y1="62" x2="370" y2="120"/>
    <line x1="450" y1="62" x2="490" y2="120"/>
    <line x1="480" y1="62" x2="680" y2="120"/>
    <line x1="360" y1="172" x2="400" y2="270"/>
    <line x1="500" y1="172" x2="460" y2="270"/>
    <line x1="430" y1="244" x2="430" y2="270" stroke-dasharray="5 4"/>
    <line x1="430" y1="322" x2="430" y2="360"/>
    <line x1="145" y1="172" x2="300" y2="360"/>
    <line x1="715" y1="172" x2="560" y2="360"/>
  </g>
  <text x="20" y="420" fill="currentColor" font-size="11">arrow = Maven dependency · testrunner-test-support (test scope only) omitted</text>
</svg>
<figcaption>Every arrow points towards the core; the core depends on nothing of the project, nor on Spring or HAPI.
A FHIR version is a leaf module next to its siblings; adding R6 adds one box and one arrow.</figcaption>
</figure>

| Module | Responsibility | Depends on |
|---|---|---|
| `testrunner-core` | Version-neutral TestScript model, execution engine, HTTP client with communication log, report writers, configuration records and the ports (`FhirVersionSupport`, `ProfileValidator`, `HttpTransport`, `ReportWriter`, `RunListener`) | SLF4J, Jackson, Jayway JsonPath |
| `testrunner-fhir-hapi` | Everything HAPI-based that is the same for every FHIR version: reading a TestScript with a strict parser, the generic `TestScriptMapper`, FHIRPath | core, `hapi-fhir-base` |
| `testrunner-fhir-r4`, `-r5` | One FHIR version each: its `FhirContext` and its `TestScriptDialect` (30–45 lines). Registered via `ServiceLoader` | fhir-hapi, `hapi-fhir-structures-rX` |
| `testrunner-validation-cli` | `ProfileValidator`s running the HL7 Validator CLI: one process per validation, or long-running validator servers (`validation.mode`) | core |
| `testrunner-spring-boot-autoconfigure` | The pre-defined Spring configuration: binds `testrunner.*` to `RunConfiguration` and wires a `TestRunner` | core, Spring Boot (validation-cli optional) |
| `testrunner-app` | Spring Boot CLI application, the executable jar, end-to-end ITs and architecture rules | all of the above |
| `testrunner-test-support` | Test doubles in `src/test/java`, published as test-jar (classifier `tests`): in-memory FHIR server, fake validator jar, access to `testdata/`. Used in test scope only (enforced) | Jackson, Jetty – not core, so every module, core included, can use it in tests |

The boundaries are enforced by `ArchitectureTest` (ArchUnit): the core must not use Spring, HAPI or any adapter;
the version modules must not know each other; `fhir-hapi` must not use version-specific HAPI packages. The compiler
sees only Java SE (`--limit-modules java.se`), so no code uses `com.sun.*` or other JDK-specific APIs; the enforcer
plugin keeps `testrunner-test-support` in test scope.

## Runtime view

<figure>
<svg viewBox="0 0 880 300" role="img" aria-label="A run: the TestRunner discovers TestScript files, the loader detects each file's FHIR version and maps it to the neutral model, then a TestScriptExecution with its own state runs setup, tests and teardown, sending requests through the FhirHttpClient which logs every exchange; results go to the report writers." xmlns="http://www.w3.org/2000/svg" style="max-width:100%;height:auto;font-family:sans-serif;font-size:12px">
  <defs>
    <marker id="flow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
      <path d="M0,0 L10,5 L0,10 z" fill="currentColor"/>
    </marker>
  </defs>
  <g fill="none" stroke="currentColor" stroke-width="1.3">
    <rect x="10" y="40" width="130" height="50" rx="6"/>
    <rect x="180" y="40" width="150" height="50" rx="6"/>
    <rect x="370" y="20" width="250" height="140" rx="6" stroke="#2f7d6d" stroke-width="2"/>
    <rect x="390" y="90" width="210" height="54" rx="4" stroke-dasharray="4 3"/>
    <rect x="670" y="20" width="190" height="50" rx="6"/>
    <rect x="670" y="110" width="190" height="50" rx="6"/>
    <rect x="370" y="220" width="250" height="50" rx="6"/>
    <rect x="180" y="220" width="150" height="50" rx="6"/>
  </g>
  <g fill="currentColor" text-anchor="middle">
    <text x="75" y="62" font-weight="bold">Discovery</text>
    <text x="75" y="78">suites → files</text>
    <text x="255" y="62" font-weight="bold">Loader</text>
    <text x="255" y="78">detect version, map</text>
    <text x="495" y="42" font-weight="bold">TestScriptExecution</text>
    <text x="495" y="60">setup → tests → teardown</text>
    <text x="495" y="78">one per TestScript, one thread</text>
    <text x="495" y="112">ExecutionState</text>
    <text x="495" y="130">fixtures · responses · last response</text>
    <text x="765" y="42" font-weight="bold">FhirHttpClient</text>
    <text x="765" y="58">REQ-0001, REQ-0002 …</text>
    <text x="765" y="132" font-weight="bold">ProfileValidator</text>
    <text x="765" y="148">process · server mode</text>
    <text x="495" y="242" font-weight="bold">TestScriptResult</text>
    <text x="495" y="258">phases · actions · outcomes</text>
    <text x="255" y="242" font-weight="bold">ReportWriters</text>
    <text x="255" y="258">junit · summary · json</text>
  </g>
  <g stroke="currentColor" stroke-width="1.2" fill="none" marker-end="url(#flow)">
    <line x1="140" y1="65" x2="180" y2="65"/>
    <line x1="330" y1="65" x2="370" y2="65"/>
    <line x1="620" y1="45" x2="670" y2="45"/>
    <line x1="620" y1="135" x2="670" y2="135"/>
    <line x1="495" y1="160" x2="495" y2="220"/>
    <line x1="370" y1="245" x2="330" y2="245"/>
  </g>
  <g fill="currentColor" font-size="11" text-anchor="middle">
    <text x="160" y="58">source</text>
    <text x="350" y="58">model</text>
    <text x="645" y="38">request</text>
    <text x="645" y="128">validate</text>
    <text x="530" y="195">results</text>
    <text x="350" y="238">run result</text>
  </g>
  <text x="765" y="90" fill="currentColor" font-size="11" text-anchor="middle">→ communication/ folder + log</text>
</svg>
<figcaption>Per TestScript, the execution and its state are confined to one thread and one execution object.
Shared across a run: the request counter and the report list. Shared across runs: the HTTP transport and, in
validator server mode, the validator servers.</figcaption>
</figure>

1. `TestRunner.run(RunConfiguration)` checks the configuration, creates a `RunContext` (run id, run folder,
   request counter) and notifies `RunListener`s (e.g. the per-run log file).
2. `TestScriptDiscovery` lists the TestScript files of every suite (glob `include`, stable order).
3. Per file, possibly in parallel: `TestScriptLoader` determines the FHIR version (pinned per suite or detected),
   lets that version's `FhirVersionSupport` map the file to the neutral `TestScript` model, and loads the fixture
   resources from `Example_Instances/`.
4. Structural checks and, if enabled, validation of the TestScript itself against its FHIR version run before
   anything is sent. `TestScriptChecks` reports everything that would fail in every run, all at once: duplicate ids,
   malformed variables, more than one server, and every reference an action makes, walked in execution order – a
   `sourceId`/`targetId`/`compareToSourceId`/`minimumId`/`variable.sourceId` naming nothing or a response kept only
   later, a `${name}` that is no variable and no `testrunner.*` value (also in fixture resources), a variable or
   assertion on the last response before the first operation, `validateProfileId` not in `TestScript.profile`.
5. `TestScriptExecution` runs setup, tests and teardown. It owns an `ExecutionState` (fixtures, responses kept
   by `responseId`, last response) and the helpers that work on it: `VariableResolver`, `RequestFactory`,
   `AssertionEvaluator`, `FixtureManager`.
6. Every request goes through `FhirHttpClient`: request id, one log line, one folder in `communication/`.
7. The `TestScriptResult`s are combined into a `RunResult`, which the configured `ReportWriter`s write into
   the run folder.

## Execution semantics

Taken over from the PoC (and its README/VALIDATION-NOTES), now encoded in one place, `TestScriptExecution`:

| Situation | Recorded as | Effect |
|---|---|---|
| Assertion fails | `fail` | Phase continues |
| … with `stopTestOnFail = true` | `fail` | Current phase stops; next test still runs |
| … with `warningOnly = true` | `pass` + warning | Wins over `stopTestOnFail` |
| Action cannot be executed (invalid TestScript, unknown id, no response, validator unavailable) | `error` | Current phase stops |
| Setup does not pass | — | All tests are `skip`, teardown still runs |
| TestScript cannot be loaded or is invalid | `error` (phase `Load`) | Nothing is sent; other TestScripts continue |
| TestScript addresses more than one server (`destination`) | `error` (phase `Load`) | Nothing is sent |
| TestScript refers to something that cannot exist when it is used (unknown id or variable, response kept later, undeclared profile) | `error` (phase `Load`), every problem listed | Nothing is sent |
| Server lacks a required capability (`metadata.capability`) | `skip` (Setup and tests) | Only `GET [base]/metadata` is sent |
| Required capability cannot be checked (CapabilityStatement not among the Example Instances) | warning on the Setup | TestScript runs |
| No FHIR server configured | `skip` | — |
| `validateProfileId` with validation disabled | `pass` + warning | Never silently `pass` |

Outcome precedence for a TestScript and the run: `error > fail > skip > pass`. Exit code of the CLI: 0 pass/skip,
1 fail/error, 2 invalid configuration.

Two assertions compare whole resources: `minimumId` passes if the checked response or fixture contains every element
and value of the named fixture (the resource id excepted, the server assigns its own), by the rules of the FHIR
testing specification – element and repetition order do not matter, more is allowed, but a repeated value needs a
counterpart of its own; every difference is listed. Values compare as written in either encoding, so a JSON `100.0`
matches an XML `value="100.0"`, but not `100` (FHIR decimals keep their precision). The narrative (`text.div`) is
compared as text only within one encoding; a JSON fixture with narrative does not match an XML response. `navigationLinks = true` passes if the Bundle has a `first`,
`last` and `next` link (the specification's definition), `false` if it lacks one of them.

Fixtures with `autocreate` are created in dependency order (references between fixtures are rewritten to the
server-assigned ids); a cycle is an `error`. Fixtures with `autodelete` are deleted after the teardown, in reverse
order. Variables are evaluated each time a `${name}` placeholder is used, so they always reflect the latest response.

Placeholders also work in the text values of fixture resources (JSON string values, XML attribute values and text –
never names, so a value cannot break the document). A fixture is kept as written and resolved each time it is used
(sent, checked, read by a variable): its placeholders get their values at that moment, and its references to
autocreated fixtures are rewritten to the server-assigned ids, like those between autocreated fixtures. So a fixture
sent in a test can reference a resource created before (`"reference": "Patient/${pid}"`, or `Patient/<id of an
autocreated fixture>`). An autocreated fixture is resolved once, when it is created before the setup actions; a
variable on a response cannot be used in it. A placeholder that fails is an `error` of the action using the fixture
(of the Setup for an autocreated one). Next to the TestScript's variables, the runner provides values of its own,
fixed per TestScript execution:

| Placeholder | Value |
|---|---|
| `${testrunner.runId}` | the run id, e.g. `run_2026-10-26_10-00-00-000` |
| `${testrunner.testScriptId}` | unique per TestScript execution, a valid FHIR id: `run-2026-10-26-10-00-00-000-03` |
| `${testrunner.uuid}` | a random UUID |
| `${testrunner.timestamp}`, `${testrunner.date}` | start of the TestScript (UTC), `2026-10-26T10:00:00.123Z`, `2026-10-26` |

Fixtures that get an identifier of their own this way can be found again by the TestScript (search by identifier)
without seeing the data of other TestScripts – in parallel runs and when the same server is tested again.

### Required capabilities and destinations

`metadata.capability` with `required = true` names a CapabilityStatement (`capabilities`) the server must support.
Before the setup, `CapabilityCheck` compares it with the server's own (`GET [base]/metadata`, asked once per server
and run, in the communication log of the first TestScript that needs it): every element of the required statement
that describes the RESTful interface – FHIR version, formats, resource types, interactions, search parameters,
operations, conditional and versioning flags (`CapabilityCheck.COMPARED`) – must have a counterpart in the server's,
compared like `assert.minimumId` (order free, more is allowed, see `content.FhirElement`), except that a value is met
by more support (`CapabilityCheck.AT_LEAST`): `updateCreate: true` meets a required `false`, `versioned-update` meets
`versioned`, `multiple` meets `single`, `full-support` meets any conditional read, and `json` and
`application/fhir+json` are the same format. Name, status, date and narrative are not compared: a server does not
share them with the author of a TestScript. If the server lacks
something, the TestScript is `skip`ped with every difference listed, nothing is created on the server. The required
CapabilityStatement is looked up like a fixture resource (by the last segments of its canonical URL, e.g.
`CapabilityStatement-example.json`), in the suite's Example Instances and then next to its TestScripts – where a FHIR
package keeps its conformance resources; if it is in neither, the requirement is reported as a warning and the
TestScript runs. Capabilities with `required = false` (only `validated`) are not checked.

The runner sends every request itself (it plays every `origin`) to the one server configured for the suite. A
TestScript that addresses more than one server – several `TestScript.destination`s or several
`operation.destination` values – cannot run as written and is invalid. A single destination, declared or not, is the
configured server.

## Deliberate differences to the Python PoC

* **Version detection instead of trial and error.** The PoC validated R4 TestScripts as R5 unless
  `validation.fhirVersion` was set, and read `profile` with ad-hoc `isinstance` checks. Here the version is
  determined first (per file), then a version-specific parser reads the file.
* **Validation result from `-output`.** The PoC parsed the validator's console output, where an empty output
  looked like success. The adapter reads the OperationOutcome file and treats a missing file as an error.
* **The validator stays running.** The PoC started the validator for every assertion. In the default
  `validation.mode=server` the packages are loaded once per FHIR version; a failing or hanging server falls back to
  the per-validation process ([ADR-0011](adr/0011-validator-server-mode.md)).
* **Assertions are typed.** An assert must carry exactly one check (FHIR invariants tst-5/tst-6); this is enforced
  when reading, not discovered during execution.
* **One run folder** (`testrunner-results/run_<timestamp>/`) holds all artefacts of a run, instead of four
  folders with matching timestamps.
* **Negative control repaired.** The AT Core R4 profile 2.1.0 no longer requires `name` and `gender`, so the PoC's
  negative control passed silently. The fixture now violates a cardinality the current profile enforces
  (two social security numbers); see [validation.md](validation.md).
* **IGs as packages instead of scraped pages.** The PoC downloaded an IG by scraping its `artifacts.html`
  (`load_ig_from_internet.py`). A suite now reads the IG's FHIR package (`suites[n].package-id`), from the package
  cache shared with the validator, downloading it from the registry if needed; FHIR version and IGs come from its
  `package.json`, see [configuration.md](configuration.md#suites-from-fhir-packages).

## Open points

* **Packages: transitive dependencies, `#current`.** A package suite passes the package and its direct
  dependencies to the validator, which resolves the rest itself. The runner downloads only the package of the suite,
  and only by exact version: `#current` (CI builds) and versions without number are not resolved.
* **Not supported assertions**: `requestMethod`/`requestURL` and `direction = request` (need a test engine acting
  as server), `manualEval`; reported as `error`, not silently ignored.
* **Several servers, client tests.** TestScripts addressing more than one `destination` need a test engine that
  manages several servers; the runner rejects them. It plays every `origin` itself, so TestScripts that expect another
  client to send a request (testing a client rather than a server) cannot be run.
* **Validator start-up.** The default server mode starts one validator per FHIR version and IGs (5–7 s, then
  0.1–0.5 s per validation, [ADR-0011](adr/0011-validator-server-mode.md)); process mode pays 5–15 s per validation.
  An in-process `org.hl7.fhir.validation` validator would remove the start-up too; it is a drop-in
  `ProfileValidator`, but ties the runner to the validator's Java and dependency versions (ADR-0006).
* **Finer scheduling.** Parallel TestScripts that share server data either use data of their own
  (`${testrunner.testScriptId}`) or sit in a suite with `parallel: false`. Named serial groups (serial within, in
  parallel across groups) were not needed yet, see [ADR-0008](adr/0008-parallel-execution.md).

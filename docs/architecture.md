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
| IG/profile validation like the PoC; jar path configurable | `ProfileValidator` port, HL7 Validator CLI adapter, `testrunner.validation.validator-jar` ([ADR-0006](adr/0006-validator-cli.md)); optional server mode with fallback ([ADR-0011](adr/0011-validator-server-mode.md)) |
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
| `testrunner-test-support` | In-memory FHIR server, fake validator jar, access to `testdata/` | Jackson only, so every module can use it in tests |

The boundaries are enforced by `ArchitectureTest` (ArchUnit): the core must not use Spring, HAPI or any adapter;
the version modules must not know each other; `fhir-hapi` must not use version-specific HAPI packages.

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
4. Structural checks (unique ids, variable definitions) and, if enabled, validation of the TestScript itself
   against its FHIR version run before anything is sent.
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
| No FHIR server configured | `skip` | — |
| `validateProfileId` with validation disabled | `pass` + warning | Never silently `pass` |

Outcome precedence for a TestScript and the run: `error > fail > skip > pass`. Exit code of the CLI: 0 pass/skip,
1 fail/error, 2 invalid configuration.

Fixtures with `autocreate` are created in dependency order (references between fixtures are rewritten to the
server-assigned ids); a cycle is an `error`. Fixtures with `autodelete` are deleted after the teardown, in reverse
order. Variables are evaluated each time a `${name}` placeholder is used, so they always reflect the latest response.

## Deliberate differences to the Python PoC

* **Version detection instead of trial and error.** The PoC validated R4 TestScripts as R5 unless
  `validation.fhirVersion` was set, and read `profile` with ad-hoc `isinstance` checks. Here the version is
  determined first (per file), then a version-specific parser reads the file.
* **Validation result from `-output`.** The PoC parsed the validator's console output, where an empty output
  looked like success. The adapter reads the OperationOutcome file and treats a missing file as an error.
* **The validator can stay running.** The PoC started the validator for every assertion. With
  `validation.mode=server` the packages are loaded once per FHIR version; a failing server falls back to the
  per-validation process ([ADR-0011](adr/0011-validator-server-mode.md)).
* **Assertions are typed.** An assert must carry exactly one check (FHIR invariants tst-5/tst-6); this is enforced
  when reading, not discovered during execution.
* **One run folder** (`testrunner-results/run_<timestamp>/`) holds all artefacts of a run, instead of four
  folders with matching timestamps.
* **Negative control repaired.** The AT Core R4 profile 2.1.0 no longer requires `name` and `gender`, so the PoC's
  negative control passed silently. The fixture now violates a cardinality the current profile enforces
  (two social security numbers); see [validation.md](validation.md).
* Not carried over: the IG downloader (`load_ig_from_internet.py`) — see [Open points](#open-points).

## Open points

* **IG download.** The PoC scraped `artifacts.html` of an IG. The cleaner replacement is to consume the IG's
  NPM package (`package.tgz`) as a `TestScriptSource`; the validator can already use packages via
  `testrunner.validation.igs`. Not implemented in this PoC.
* **Not supported assertions**: `minimumId`, `navigationLinks`, `requestMethod`/`requestURL` (need a test engine
  acting as server), `manualEval`; reported as `error`, not silently ignored. `metadata.capability` checks
  (skip if the server lacks a capability) are not implemented either, as in the PoC.
* **Validator start-up.** In the default process mode each validation starts the validator anew (5–15 s).
  `testrunner.validation.mode=server` keeps validator servers running (2–6× faster on the AT Core suite,
  [ADR-0011](adr/0011-validator-server-mode.md)); it is opt-in until it has proven itself. An in-process
  `org.hl7.fhir.validation` validator would remove the start-up too; it is a drop-in `ProfileValidator`.
* **TestScript-level dependencies.** Parallel TestScripts that share server data can interfere; scheduling hints
  (e.g. "serial" groups) would be the next step, see [ADR-0008](adr/0008-parallel-execution.md).

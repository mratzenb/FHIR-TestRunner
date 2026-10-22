# ADR-0001: Hexagonal core with adapters, split into Maven modules

## Context
The PoC's evaluator (`test_script_evaluator_log_to_file.py`, 1,300 lines) mixes TestScript parsing, URL building,
HTTP, assertion logic, validation and result tracking, reached through module-level globals. Every concern changes
for different reasons: FHIR versions (R6), the validator (CLI vs. library), the transport (auth, proxies), reporting.

## Decision
* `testrunner-core` holds the domain: a version-neutral TestScript model, the execution engine, the HTTP client and
  communication log, reports, configuration records – and **ports** for what varies: `FhirVersionSupport`,
  `ProfileValidator`, `HttpTransport`, `ReportWriter`, `RunListener`.
* Each adapter is a Maven module: `fhir-hapi` + `fhir-r4`/`fhir-r5`, `validation-cli`,
  `spring-boot-autoconfigure`, `app`. Test doubles live in `test-support`, which every module uses in test scope
  only ([ADR-0009](0009-testing.md)).
* Inside the engine, one class per responsibility: `RequestFactory` (URL/headers/body), `AssertionEvaluator`,
  `VariableResolver`, `FixtureManager`, `TestScriptExecution` (phase semantics), `TestRunner` (orchestration).
* Boundaries are enforced by ArchUnit (`ArchitectureTest`).

## Alternatives
* *Single module with packages* – simpler build, but nothing stops HAPI or Spring from leaking into the engine,
  and R6 could not be added/removed as a jar.
* *A module per engine concern* (http, assertions, …) – more modules without a reason to vary independently.

## Consequences
* The core compiles without HAPI and Spring; it is unit-tested with a stub FHIR version (`StubVersionSupport`).
* Eight modules is more than a PoC strictly needs; each has a single reason to change and they are small.

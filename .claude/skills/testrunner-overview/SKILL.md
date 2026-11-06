---
name: testrunner-overview
description: Map of the FHIR TestScript Runner (TestRunnerOnJava) – modules, packages, key classes, ports, module rules and which other testrunner-* skill covers what. Use first for any work in this repository, when you need to know where something lives, which module may depend on which, or which skill to load next.
---

# FHIR TestScript Runner – overview

Java 25 / Maven re-design of the Python PoC `../HL7-AT-TestFhiry-TestRunner`. Executes FHIR `TestScript` resources
(R4, R5; R6-ready) against a FHIR server; reports JUnit XML, text summary, JSON; writes a run log and a
communication log of every HTTP exchange. Group `at.hl7.testing`, version `0.1.0-SNAPSHOT`, base package
`at.hl7.testing.runner`.

## Modules (dependencies point towards core)

| Module | Package | Holds | Depends on |
|---|---|---|---|
| `testrunner-core` | `core.{model,engine,http,report,source,spi,config,content}` | version-neutral model, engine, HTTP client + communication log, reports, config records, **ports** | SLF4J, Jackson, Jayway JsonPath – **no Spring, no HAPI** |
| `testrunner-fhir-hapi` | `fhir.hapi` | generic `TestScriptMapper`, `ElementView`, `HapiFhirPathEvaluator`, `TestReportEncoder`, `TestScriptDialect`, `HapiFhirVersionSupport` | core, `hapi-fhir-base` |
| `testrunner-fhir-r4`, `-r5` | `fhir.r4`, `fhir.r5` | `R4Support`/`R5Support` + `R4Dialect`/`R5Dialect`, `META-INF/services` registration | fhir-hapi, `hapi-fhir-structures-rX` |
| `testrunner-validation-cli` | `validation.cli` | `ValidatorCliProfileValidator` (process), `ValidatorServerProfileValidator` + `ValidatorServer` (server), `ProfileValidators` factory | core |
| `testrunner-spring-boot-autoconfigure` | `autoconfigure` | `TestRunnerAutoConfiguration`, `ValidatorCliAutoConfiguration`, `RunLogFileListener` | core, Spring Boot |
| `testrunner-app` | `app` | `TestRunnerApplication` (main, `--config`, `--novalidator`, `--check`, `--list`, `--testscript`, `--test`), `RunCommand` (run, check or list; exit code), `ValidatorCheck`; `EndToEndIT`, `RealValidatorIT`, `HapiServerIT`, `ShutdownIT`, `ArchitectureTest` | everything |
| `testrunner-test-support` | `testsupport` | **test code only** (`src/test/java`, test-jar classifier `tests`): `InMemoryFhirServer`, `FakeValidator`/`FakeValidatorJar`, `FakePackageServer` (registry + CI builds), `SampleData` | Jackson, Jetty |

Other folders: `testrunner-app/Dockerfile` + root `docker-compose.yml` (container image with HAPI FHIR, ADR-0015),
`config/` dev configurations (`dev-r4.yml`, `dev-r5.yml`, `dev-all.yml`, `negative-control.yml`),
`testdata/{r4,r5,samples}/{Test_Scripts,Example_Instances,Profiles}` (PoC data), `docs/` (architecture, ADRs).

## Ports (in core) and their implementations

| Port | Purpose | Implementations |
|---|---|---|
| `spi.FhirVersionSupport` | read TestScripts of one version, FHIRPath for it, write its TestReports | `R4Support`, `R5Support` via `ServiceLoader` |
| `spi.ProfileValidator` | `validateProfileId` + check of each TestScript | `ValidatorCliProfileValidator`, `ValidatorServerProfileValidator`, `ProfileValidator.DISABLED` |
| `http.HttpTransport` | send one request (functional interface) | `JdkHttpTransport` |
| `report.ReportWriter` | one report format, selected by `format()` | `JUnitXmlReportWriter` (`junit`), `SummaryReportWriter` (`summary`), `JsonReportWriter` (`json`), `TestReportWriter` (`testreport`), `HtmlReportWriter` (`html`) |
| `engine.RunListener` | run / TestScript events, with a read-only `RunInfo` (not `RunContext`) | `RunLogFileListener` |

Entry points: `TestRunner.run(RunConfiguration)` or `run(RunConfiguration, RunControl)` (stoppable, ADR-0013);
`TestRunner.check(…)` loads and checks without sending, `TestRunner.list(…)` says what would run, `RunConfiguration.select`
narrows all three (ADR-0014); without Spring `TestRunners.create(HttpConfig, ProfileValidator)`
and `ProfileValidators.create(ValidationConfig)`.

## Rules that the build enforces

* `ArchitectureTest` (ArchUnit, app module): core uses no Spring/HAPI/Logback/adapter packages; `fhir.r4` and
  `fhir.r5` do not know each other; `fhir.hapi` uses no `org.hl7.fhir.r4..`/`r5..`; `fhir..` and `validation..` use
  no Spring; core packages are cycle-free; **other modules use only the core API** (`CORE_API_PACKAGES`: `model`,
  `config`, `spi`, `report`; `CORE_API_TYPES`: `TestRunner` (incl. nested `TestRunner.Listed`), `TestRunners`, `RunControl`, `RunListener`, `RunInfo`,
  `ConfigurationException`, `HttpTransport`, `JdkHttpTransport`, `FhirRequest`, `FhirResponse`, `HttpHeaders`) –
  using anything else from outside means adding it to that list on purpose; nobody outside `engine` uses `RunContext`. Spring is therefore only in `autoconfigure` and `app`.
* Nullness (ADR-0017): every reference-typed parameter, return value and record component of the public API (the
  core API above + every public type of the other modules) has `jakarta.annotation.Nonnull` or `Nullable`
  (`jakarta.annotation-api` in the core, version from the Spring Boot BOM); `ArchitectureTest.
  publicApiDeclaresWhereNullIsAllowed` fails otherwise. A value that may be absent is returned as `Optional`
  (`FhirServerConfig.base()`, `OperationType.defaultMethod()`, `ContentFormat.fromMimeType/fromFileName`,
  `TestScriptFormatException.fhirVersion()`, `HttpHeaders.first`, …), never as `Optional` component or parameter.
  Records that default `null` (config records, `FhirRequest`, `FhirResponse`) have an explicit canonical
  constructor with `@Nullable` parameters and `@Nonnull` components. Annotation on its own line above a method
  (`@Nonnull` / `public Foo bar()`), inline before a parameter; none on primitives. Internal classes: none.
* Compiler: `--limit-modules java.se` – no `com.sun.*`, `sun.*`, `jdk.*` APIs in main or test code.
  `-Xlint:all,-processing,-serial`; no warnings tolerated by convention.
* Enforcer: Java ≥ 25, Maven ≥ 3.9, `testrunner-test-support` only in test scope.
* No global state (ADR-0004): only constants and loggers are static; run state in `RunContext`, TestScript state in
  `ExecutionState`, results are immutable records.

## Which skill next

| Task | Skill |
|---|---|
| Build, run tests, run the jar, Maven/POM changes | `testrunner-build-and-run` |
| Engine behaviour: operations, assertions, fixtures, variables, outcomes, parallelism | `testrunner-execution-engine` |
| R4/R5 mapping, version detection, `TestScriptMapper`, adding R6 | `testrunner-fhir-versions` |
| Profile validation, validator process/server mode, the negative control | `testrunner-validation` |
| `testrunner.*` properties, `RunConfiguration`, Spring beans, library use | `testrunner-configuration` |
| Run log, request ids, communication log, reports | `testrunner-logging-and-reports` |
| Writing or fixing tests, test doubles, test data | `testrunner-testing` |

## Documentation (source of truth if a skill and the docs disagree)

`README.md`, `CHANGELOG.md` (changes of results/API); `docs/architecture.md`, `fhir-versions.md`, `configuration.md`,
`logging.md`, `validation.md`; `docs/adr/0001`–`0019` (index in `docs/adr/README.md`). When code changes a documented behaviour, update the
doc, the ADR and the matching skill together.

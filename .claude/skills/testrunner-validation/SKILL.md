---
name: testrunner-validation
description: Profile validation in the FHIR TestScript Runner (TestRunnerOnJava) – the ProfileValidator port, the HL7 Validator CLI in process mode and server mode, where the profiles come from (per-suite Profiles folder and igs), fallback and failure handling, why a green run must prove validation ran (silent-pass protection), and the negative control. Use when working in testrunner-validation-cli, on validateProfileId, on validator settings (testrunner.validation.*, suites[n].igs), or when validation results look wrong or suspiciously green.
---

# Profile validation (ADR-0006, ADR-0011, `docs/validation.md`)

`assert.validateProfileId` and (if `validation.validate-test-scripts`, default true) the check of every TestScript
against its FHIR version go through the port `core.spi.ProfileValidator` (`isEnabled`, `description`,
`configurationProblems` – settings that block every run, added to the run's problems ⇒ exit 2; the adapters report
`validator-jar is not set` via `ValidatorArguments.configurationProblems`, `DISABLED` reports none –,
`validate(ValidationRequest(body, fhirVersion, profile, profilesDir, igs))` → `ValidationResult`).
`ProfileValidators.create(ValidationConfig)` picks: disabled ⇒ `ProfileValidator.DISABLED`; `PROCESS` ⇒
`ValidatorCliProfileValidator`; `SERVER` ⇒ `ValidatorServerProfileValidator`. Implementations must be thread-safe.

## Rule zero: never a silent pass

`validate` throws `ValidatorUnavailableException` when nothing was validated; the engine records `error`. Disabled
validation turns `validateProfileId` into `pass` **with a warning** (`AssertionEvaluator.ValidationSkipped`, an
internal exception). An undeclared profile id is an `error` even then – it is checked before `isEnabled()`. Every path either reads an OperationOutcome
the validator really returned, or ends in an error. Keep it that way.

| PoC silent-pass path | Here |
|---|---|
| Validator did not start, empty stdout read as success | result read from the `-output` OperationOutcome file; no file ⇒ error with exit code and output tail |
| `--novalidator` passed silently | warning in log, JUnit `system-out`, summary |
| Server down / crashed | fallback to process (warning) or error – never pass |
| Undeclared profile id | `error` naming declared ids |

## Shared by both modes

* `ValidatorArguments.launcher`: `<java> <jvm-arguments> -jar <validator-jar>` (java = the runner's unless
  `java-executable`).
* `ValidatorArguments.engine`: `-version <major.minor of the TestScript>`, `-tx <terminology-server, default n/a>`,
  `-locale`, `-ig <suite>/Profiles` (if it exists), `-ig` per entry of the suite's `igs`, `extra-arguments`.
* The IGs are **per suite** (`SuiteConfig.profilesDir` + `SuiteConfig.igs`) and travel in every
  `ValidationRequest(body, fhirVersion, profile, profilesDir, igs)`: a TestScript names a profile only by canonical
  URL, never by package/version, so the suite (≈ one IG) supplies the definitions. `igs` needs a pinned
  `fhir-version` (`RunConfiguration.problems()`); the former `testrunner.validation.igs` makes the start fail
  (`TestRunnerAutoConfiguration`) instead of being silently ignored.
* What an `igs` entry needs locally: a package id with version is taken from `~/.fhir/packages` or downloaded once
  from packages.fhir.org (with dependencies); `#dev` (local IG Publisher build), folders and files must exist
  locally. The package must define **the canonical URL the TestScript uses** – the published
  `hl7.at.fhir.core.r4#2.0.0` uses `http://hl7.at/fhir/HL7ATCoreProfiles/4.0.1/...`, the AT Core TestScripts
  `https://fhir.hl7.at/core/r4/...` (2.1.0, so far only `Profiles/` or `#dev`); a mismatch is an `error`.
* A suite with `package-id` (`source.PackageSuites`) gets `igs` = the package, its non-core dependencies, then the
  configured `igs`, and the package's FHIR version; its profiles folder does not exist (skipped as `-ig`). With
  validation on, the runner resolves the dependencies transitively into `~/.fhir/packages` first (verified against
  the registry's SHA-1, ADR-0018) and passes them with the version found (`7.4.x` → `7.4.2`, a missing `dev` →
  `current`), so the validator loads them from the cache; `#current` packages are the CI build the validator would
  pick too.
* `OperationOutcomeReader` parses the result; errors make it invalid, warnings are logged at DEBUG.
* `ValidatorArguments.problems` (jar missing, java not executable) – also reported at start-up by `ValidatorCheck`.

## Process mode (`mode: process`, `ValidatorCliProfileValidator`)

One `java -jar validator_cli.jar <file> ... [-profile <canonical>] -output <file>` per validation in its own temp
folder (`ProcessRunner`), timeout `validation.timeout` (5m). 5–15 s per validation, nearly all package loading.
Nothing shared, so a validator problem only hits that validation. It is also the fallback of the server mode.

## Server mode (default since 2026-10, `ValidatorServerProfileValidator` + `ValidatorServer`)

* `java -jar validator_cli.jar server <port> <engine args>`; `POST /validateResource[?profile=..]` per validation,
  `POST /stop` to end. One server per **(FHIR version, profiles folder, igs)** – in practice per suite and version –
  started lazily, `127.0.0.1` on a free port,
  ready when the port accepts connections (`server.startup-timeout`, 5m), one retry if the port was taken.
* 0.1–0.5 s per validation after a 5–7 s start; ≈ 1 GB per server; requests are handled one at a time (parallel
  TestScripts queue). Measured on AT Core R4 (validator 7.0.2): 35.2 s ⇒ 6.2 s.
* Lifetime: application-scoped, `AutoCloseable`; Spring closes it with the context; a JVM shutdown hook destroys
  leftovers. The TestScript check passes the suite's profiles folder and IGs so it lands on the same server.
* Failures:

  | Problem | Behaviour |
  |---|---|
  | does not start / exits / startup-timeout | server marked failed, **not restarted** |
  | process dies while running | marked failed |
  | failed server | `server.fallback-to-process=true` (default): its validations – including the one that hit the failure – run as processes, one WARN log line per server; `false`: `error` whose message names `testrunner.validation.server.fallback-to-process` and `testrunner.validation.mode=process` |
  | HTTP 500 on one validation (e.g. unknown profile) | `error` for that assertion, no fallback |
  | validation exceeds `validation.timeout` (hanging server) | server ended (`ValidatorServer.hanging`) and marked failed ⇒ falls back like a crash; costs one timeout, a short `validation.timeout` (e.g. 1m) surfaces it sooner |

* Why it is the default (ADR-0011 › Update): dev configs used it from the start, `RealValidatorIT` passes in both
  modes (validator 7.0.2, server without fallback), the hang gap is closed, fallback stays on by default.
* Switch off without rebuild: `--testrunner.validation.mode=process`; tests that need process mode set it
  (`EndToEndIT.negativeControlFailsBecauseValidationRuns`).

## The negative control

`config/negative-control.yml` runs `testdata/r4/Test_Scripts/TestScript-testscript-patient-create-negative-at-core.json`;
**it must FAIL** – a validator that passes everything looks exactly like one that works. Its fixture has two social
security numbers (`Patient.identifier:socialSecurityNumber: max allowed = 1, but found 2` against AT Core Patient
2.1.0). The PoC's original control (missing name/gender) passed silently because 2.1.0 dropped those cardinalities.
`EndToEndIT.negativeControlFailsBecauseValidationRuns` and `RealValidatorIT` guard it.

## Tests

* `ValidatorCliProfileValidatorTest`, `ValidatorServerProfileValidatorTest`, `EndToEndIT` use the fake validator
  (`FakeValidatorJar`, see `testrunner-testing`): markers simulate invalid resources, no output, crashes, failing or
  slow start, unknown profile; `FAKE_VALIDATOR_LOG` counts starts and calls.
* `RealValidatorIT` (opt-in via `VALIDATOR_JAR`) runs AT Core R4 incl. the negative control in both modes.

## Not validated

Terminology with the default `-tx n/a` (set e.g. `https://tx.fhir.org/r4`; slower, needs network). `Profiles/`
folders are hand-maintained copies; prefer the published package in the suite's `igs` once its canonical URLs match
the TestScripts'.

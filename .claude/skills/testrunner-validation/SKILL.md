---
name: testrunner-validation
description: Profile validation in the FHIR TestScript Runner (TestRunnerOnJava) – the ProfileValidator port, the HL7 Validator CLI in process mode and server mode, fallback and failure handling, why a green run must prove validation ran (silent-pass protection), and the negative control. Use when working in testrunner-validation-cli, on validateProfileId, on validator settings (testrunner.validation.*), or when validation results look wrong or suspiciously green.
---

# Profile validation (ADR-0006, ADR-0011, `docs/validation.md`)

`assert.validateProfileId` and (if `validation.validate-test-scripts`, default true) the check of every TestScript
against its FHIR version go through the port `core.spi.ProfileValidator` (`isEnabled`, `description`,
`validate(ValidationRequest(body, fhirVersion, profile, profilesDir))` → `ValidationResult`).
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
  `-locale`, `-ig <suite>/Profiles` (if it exists), `-ig` per `igs` entry, `extra-arguments`.
* `OperationOutcomeReader` parses the result; errors make it invalid, warnings are logged at DEBUG.
* `ValidatorArguments.problems` (jar missing, java not executable) – also reported at start-up by `ValidatorCheck`.

## Process mode (default, `ValidatorCliProfileValidator`)

One `java -jar validator_cli.jar <file> ... [-profile <canonical>] -output <file>` per validation in its own temp
folder (`ProcessRunner`), timeout `validation.timeout` (5m). 5–15 s per validation, nearly all package loading.
Nothing shared, so a validator problem only hits that validation. It is also the fallback of the server mode.

## Server mode (opt-in, `ValidatorServerProfileValidator` + `ValidatorServer`)

* `java -jar validator_cli.jar server <port> <engine args>`; `POST /validateResource[?profile=..]` per validation,
  `POST /stop` to end. One server per **(FHIR version, profiles folder)**, started lazily, `127.0.0.1` on a free port,
  ready when the port accepts connections (`server.startup-timeout`, 5m), one retry if the port was taken.
* 0.1–0.5 s per validation after a 5–7 s start; ≈ 1 GB per server; requests are handled one at a time (parallel
  TestScripts queue). Measured on AT Core R4 (validator 7.0.2): 35.2 s ⇒ 6.2 s.
* Lifetime: application-scoped, `AutoCloseable`; Spring closes it with the context; a JVM shutdown hook destroys
  leftovers. The TestScript check passes the suite's profiles folder so it lands on the same server.
* Failures:

  | Problem | Behaviour |
  |---|---|
  | does not start / exits / startup-timeout | server marked failed, **not restarted** |
  | process dies while running | marked failed |
  | failed server | `server.fallback-to-process=true` (default): its validations – including the one that hit the failure – run as processes, one WARN log line per server; `false`: `error` whose message names `testrunner.validation.server.fallback-to-process` and `testrunner.validation.mode=process` |
  | HTTP 500 on one validation (e.g. unknown profile) | `error` for that assertion, no fallback |
  | validation exceeds `validation.timeout` | `error` for that assertion |

* **Known limitation**: a *hanging* server is not detected as failed; queued validations time out. Mitigation: a
  short `validation.timeout` (e.g. 1m) in server mode, or `mode=process`.
* Switch off without rebuild: `--testrunner.validation.mode=process` (dev configs in `config/` use `server`).

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
folders are hand-maintained copies; prefer `igs: [hl7.at.fhir.core.r4#<version>]` for the published package.

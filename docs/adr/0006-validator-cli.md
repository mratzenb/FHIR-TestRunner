# ADR-0006: Profile validation with the HL7 Validator CLI as an external process

Extended by [ADR-0011](0011-validator-server-mode.md) (server mode, the default since 2026-10). This ADR describes
`testrunner.validation.mode=process`, the original default and the fallback of the server mode.

## Context
The PoC calls `validator_cli.jar` per assertion and parses stdout. Options: keep the CLI (jar path configurable),
or embed the validator library (`ca.uhn.hapi.fhir:org.hl7.fhir.validation`).

## Decision
* Port `ProfileValidator`; adapter `ValidatorCliProfileValidator` runs the CLI as a process, jar path
  `testrunner.validation.validator-jar`.
* The result is read from the OperationOutcome the validator writes with `-output` – not from stdout. No output file
  is an error (`ValidatorUnavailableException`), never a pass.
* `-version` comes from the TestScript; `-ig <suite>/Profiles`, further `-ig`, `-tx`, `-locale` and extra
  arguments are configurable. Each validation works in its own temp folder (thread-safe).

## Alternatives
* *Embedded validator library* – ~100× faster after warm-up (one engine, packages loaded once) and no process
  handling. But: large dependency set with frequent releases, needs careful packaging of IGs/packages, couples the
  runner's Java and dependency versions to the validator's, and behaves subtly differently from the CLI IG authors
  use. Deferred; it is a drop-in `ProfileValidator`.
* *Validator in server mode* (`validator_cli.jar server`, HTTP) – fast and isolated, same port. **Added as an
  option**, see [ADR-0011](0011-validator-server-mode.md).

## Consequences
* Each validation (`validateProfileId` and the check of each TestScript) costs 5–15 s in this mode, nearly all of
  it package loading (measured with validator 6.10.4 and 7.0.2); `testrunner.validation.mode=server` (ADR-0011)
  pays that once per server instead.
* This mode stays the default and the fallback of the server mode: nothing is shared between validations, so a
  validator problem affects only the validation that hit it.
* Upgrading the validator = replacing a file, no rebuild.
* The adapter is testable without the real validator (`FakeValidatorJar`), and with it (`RealValidatorIT`).

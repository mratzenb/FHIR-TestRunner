# Profile validation

`assert.validateProfileId` checks a response (or fixture) against a profile declared in `TestScript.profile`.
Optionally every TestScript is validated against its FHIR version before it runs
(`testrunner.validation.validate-test-scripts`). Both go through the port `ProfileValidator`; the adapter runs the
HL7 FHIR Validator CLI as a separate process per validation (decision: [ADR-0006](adr/0006-validator-cli.md)).

## Setup

* Download the validator (≈ 200 MB) and point `testrunner.validation.validator-jar` at it:
  ```sh
  curl -L -o validator_cli.jar https://github.com/hapifhir/org.hl7.fhir.core/releases/latest/download/validator_cli.jar
  ```
* The validator is started with the Java running the runner (≥ 25 here, the validator needs ≥ 17);
  `testrunner.validation.java-executable` overrides it.
* On its first run the validator downloads the FHIR core packages into `~/.fhir/packages`; afterwards it works offline.
* The suite's `Profiles/` folder is always passed as `-ig`; packages (e.g. `hl7.at.fhir.core.r4#2.0.0`) can be added
  with `testrunner.validation.igs`.
* The FHIR version (`-version 4.0` / `5.0`) is the version of the TestScript – the PoC's `validation.fhirVersion`
  setting and the bugs from forgetting it are gone.

At start-up, `ValidatorCheck` warns if validation is enabled but the jar or java is missing.

## A green run must prove that validation ran

The PoC's notes start with: *"profile validation can fail silently in ways that look like a passing test suite"*.
The design answers each silent path explicitly:

| Silent-pass path in the PoC | Here |
|---|---|
| Validator could not start → empty stdout → parsed as success | The result is read from the OperationOutcome written with `-output`. No file ⇒ `ValidatorUnavailableException` ⇒ assertion `error`, with exit code and the tail of the validator output in the message |
| `--novalidator` → assertion silently passed | `validateProfileId` is reported as **warning** ("Profile validation is disabled, … was not checked") in log, JUnit `system-out` and summary |
| Warnings lost on green runs (only parsed after `*FAILURE*`) | All issues are read from the OperationOutcome; warnings are logged at DEBUG |
| Validator crashed half-way / timed out | Timeout (`testrunner.validation.timeout`) ⇒ `error` |
| Missing profile id | `error` naming the declared ids, not `fail` |

### The negative control

A validator that passes everything looks exactly like one that works; only a resource that **must** be rejected
proves the difference. `config/negative-control.yml` runs
`testdata/r4/Test_Scripts/TestScript-testscript-patient-create-negative-at-core.json`.
**This TestScript is expected to FAIL.**

While porting it turned out that the PoC's negative control had stopped working: the AT Core R4 profile 2.1.0 in
`Profiles/` no longer has `min = 1` on `Patient.name` and `Patient.gender`, so the fixture (which omitted both) was
valid – a silent pass of exactly the kind the control is meant to catch. The fixture now violates a cardinality the
current profile does enforce:

| Fixture `Patient-HL7ATCorePatientNonConformantTestExample` | Result (validator 7.0.2 and 6.10.4, measured) |
|---|---|
| Base FHIR R4, `meta.profile` removed | 0 errors – the server accepts it |
| AT Core Patient 2.1.0 | 1 error: `Patient.identifier:socialSecurityNumber: max allowed = 1, but found 2` |

It is still a cardinality rule, not a terminology rule (terminology is off by default, `-tx n/a`).
**The FSH source of this TestScript and fixture must be changed accordingly** if they are ever ported to the IG
(see VALIDATION-NOTES.md of the PoC, section 9.3).

### Tests

* `ValidatorCliProfileValidatorTest` and `EndToEndIT` use `FakeValidatorJar`: a real jar, compiled at test time,
  started as a process like the real validator, that writes OperationOutcomes (valid, invalid, or none at all to
  simulate a broken validator). This tests process handling, arguments and `-output` parsing without the 200 MB jar.
* `RealValidatorIT` runs the AT Core R4 suite including the negative control with the real validator. It is opt-in:
  ```sh
  VALIDATOR_JAR=/path/to/validator_cli.jar mvn verify
  ```
  Verified with validator 6.10.4 and 7.0.2: create and update pass, the negative control fails with the
  socialSecurityNumber error; with `parallelism=3` the suite takes ≈ 18 s.

## What is not validated

* **Terminology** with the default `terminology-server: n/a`. Set it to `https://tx.fhir.org/r4` (or an
  Austrian terminology server) to check codes; expect slower and network-dependent runs.
* **Local profile drift** (PoC notes, section 6): the `Profiles/` folders are hand-maintained copies. Prefer
  `igs: [hl7.at.fhir.core.r4#<version>]` to validate against the published package; confirm the version first, it
  changes what every profile assertion means.

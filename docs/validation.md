# Profile validation

`assert.validateProfileId` checks a response (or fixture) against a profile declared in `TestScript.profile`.
Optionally every TestScript is validated against its FHIR version before it runs
(`testrunner.validation.validate-test-scripts`). Both go through the port `ProfileValidator`, implemented with the
HL7 FHIR Validator CLI in one of two modes: one process per validation (default,
[ADR-0006](adr/0006-validator-cli.md)) or long-running validator servers ([ADR-0011](adr/0011-validator-server-mode.md)).

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

At start-up, `ValidatorCheck` warns if validation is enabled but the jar or java is missing (both modes). The first
lines of every run log name the mode in use (`Profile validation: …`).

## Process mode and server mode

| | `mode: process` (default) | `mode: server` |
|---|---|---|
| How | `java -jar validator_cli.jar <file> ... -output <file>` per validation | `java -jar validator_cli.jar server <port> ...` once per FHIR version and profiles folder; `POST /validateResource` per validation |
| Cost per validation | 5–15 s (loads packages every time) | 0.1–0.5 s after a start-up of 5–7 s |
| AT Core R4 suite, parallelism 1 / 3 (measured, validator 7.0.2) | 35.2 s / 18.1 s | 6.2 s / 6.6 s |
| Parallel validations | Run in parallel, one JVM each | Queue on the server (one request at a time) |
| Isolation | Nothing shared | One JVM per server, ≈ 1 GB, lives as long as the application |
| On failure | That validation is an `error` | Server failed ⇒ fall back to process mode (default) or `error`; see below |

```yaml
testrunner:
  validation:
    mode: server                      # process | server
    jvm-arguments: [-Xmx2g]           # validator JVM, both modes
    server:
      startup-timeout: 5m             # first start may download packages
      fallback-to-process: true       # false: server problems are errors instead
```

Server mode is opt-in. If it misbehaves:

1. It falls back by itself: a server that does not come up within `startup-timeout`, or whose process dies, is
   marked failed and **not restarted**; its validations continue as one process each, with one warning in the log
   naming the reason (including the server's last output lines).
2. Switch it off with `--testrunner.validation.mode=process` – no rebuild, no other change.
3. To make server problems visible instead of absorbed (e.g. in CI while evaluating the mode), set
   `server.fallback-to-process=false`; they are then reported as `error`s naming both switches.

A problem with one validation on a running server (e.g. HTTP 500 for an unknown profile) is reported for that
assertion only, without fallback, since the process mode would fail the same way.

**Watch out for a hanging server.** A validation that exceeds `validation.timeout` is an `error`, but the server
process is still running, so it is not considered failed and there is no fallback. Because the server works one
request at a time, the validations queued behind it time out too. Once a server is up a validation takes well under
a second, so in server mode a short timeout (e.g. `validation.timeout: 1m`) surfaces this quickly; switching to
`mode: process` avoids it entirely. The servers listen on
`127.0.0.1` only, the server's console output goes to the log at DEBUG (`[validator server R4] ...`), and they
are stopped when the application ends (`POST /stop`, then process termination; a shutdown hook covers crashes of
the runner).

## A green run must prove that validation ran

The PoC's notes start with: *"profile validation can fail silently in ways that look like a passing test suite"*.
The design answers each silent path explicitly:

| Silent-pass path in the PoC | Here |
|---|---|
| Validator could not start → empty stdout → parsed as success | The result is read from the OperationOutcome written with `-output`. No file ⇒ `ValidatorUnavailableException` ⇒ assertion `error`, with exit code and the tail of the validator output in the message |
| `--novalidator` → assertion silently passed | `validateProfileId` is reported as **warning** ("Profile validation is disabled, … was not checked") in log, JUnit `system-out` and summary |
| Warnings lost on green runs (only parsed after `*FAILURE*`) | All issues are read from the OperationOutcome; warnings are logged at DEBUG |
| Validator crashed half-way / timed out | Timeout (`testrunner.validation.timeout`) ⇒ `error` |
| Validator server down or crashed (server mode) | Fallback to a process per validation (with warning), or `error` if `server.fallback-to-process=false` – never a pass |
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

* `ValidatorCliProfileValidatorTest` and `EndToEndIT` use `FakeValidatorJar` (`testrunner-test-support`): a jar
  started with `java -jar` like the real validator, that writes OperationOutcomes (valid, invalid, or none at all to
  simulate a broken validator). This tests process handling, arguments and `-output` parsing without the 200 MB jar.
  The jar contains only a manifest; it runs the class `FakeValidator` of `testrunner-test-support` from the test
  class path ([ADR-0009](adr/0009-testing.md)).
* `ValidatorServerProfileValidatorTest` runs the fake validator as a server process: one server per version and
  folder, shared by parallel validations, crash and start-up failures with and without fallback, start-up timeout,
  stop on close. `EndToEndIT.serverModeValidatesWithOneServerAndStopsItWithTheApplication` does the same through the
  Spring application.
* `RealValidatorIT` runs the AT Core R4 suite including the negative control with the real validator, once per mode
  (server without fallback). It is opt-in:
  ```sh
  VALIDATOR_JAR=/path/to/validator_cli.jar mvn verify
  ```
  Verified with validator 6.10.4 and 7.0.2: create and update pass, the negative control fails with the
  socialSecurityNumber error, in both modes. Timings: [ADR-0011](adr/0011-validator-server-mode.md).

## What is not validated

* **Terminology** with the default `terminology-server: n/a`. Set it to `https://tx.fhir.org/r4` (or an
  Austrian terminology server) to check codes; expect slower and network-dependent runs.
* **Local profile drift** (PoC notes, section 6): the `Profiles/` folders are hand-maintained copies. Prefer
  `igs: [hl7.at.fhir.core.r4#<version>]` to validate against the published package; confirm the version first, it
  changes what every profile assertion means.

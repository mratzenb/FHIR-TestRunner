# ADR-0009: Tests without FHIR server, network or real validator

## Context
The brief: unit and integration tests must not assume an available FHIR server; the PoC's TestScripts (R4 and R5)
may be used.

## Decision
* `testrunner-test-support` provides
  * `InMemoryFhirServer` – a real HTTP server on a free local port (JDK `HttpServer`) with enough FHIR behaviour
    (create/read/vread/update/delete/search/history/metadata/batch, Location/ETag headers). Tests exercise the real
    HTTP client and communication log rather than a mocked transport;
  * `FakeValidatorJar` – compiles and packages a small validator stand-in at test time, so the validator adapters
    are tested against a real process: with `-output` (process mode) and as HTTP server with `/validateResource` and
    `/stop` (server mode). It can simulate invalid resources, a validator that writes no result, a crash, a server
    that fails or is slow to start, and an unresolvable profile, and records every start and call in a file
    (`FAKE_VALIDATOR_LOG`) so tests can count them;
  * `SampleData` – the PoC's TestScripts, Example Instances and profiles, copied to `testdata/` (`r4`, `r5`, `samples`).
* Levels: unit tests per module (surefire, `*Test`), end-to-end through the Spring Boot app (failsafe, `*IT`),
  architecture rules (ArchUnit). `RealValidatorIT` runs the AT Core R4 suite with the real validator in both
  validator modes; it is opt-in via `VALIDATOR_JAR`.

## Consequences
* `mvn verify` runs ~115 tests in under a minute, offline (after dependencies are cached).
* The in-memory server is not a FHIR server; behaviour that depends on a real server (search semantics, conditional
  delete rules) is out of scope of the automated tests – the docker-compose HAPI setup of the PoC remains the way to
  test against one.

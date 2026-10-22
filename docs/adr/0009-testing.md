# ADR-0009: Tests without FHIR server, network or real validator

## Context
The brief: unit and integration tests must not assume an available FHIR server; the PoC's TestScripts (R4 and R5)
may be used.

## Decision
* `testrunner-test-support` provides
  * `InMemoryFhirServer` – a real HTTP server on a free local port (Jetty) with enough FHIR behaviour
    (create/read/vread/update/delete/search/history/metadata/batch, Location/ETag headers). Tests exercise the real
    HTTP client and communication log rather than a mocked transport;
  * `FakeValidatorJar` – stands in for `validator_cli.jar`, so the validator adapters are tested against a real
    process without the 200 MB validator: with `-output` (process mode) and as HTTP server with `/validateResource`
    and `/stop` (server mode). It can simulate invalid resources, a validator that writes no result, a crash, a server
    that fails or is slow to start, and an unresolvable profile, and records every start and call in a file
    (`FAKE_VALIDATOR_LOG`) so tests can count them. The fake itself is the ordinary class `FakeValidator` of this
    module; `FakeValidatorJar.create` writes a jar holding only a manifest whose `Main-Class` is `FakeValidator` and
    whose `Class-Path` points at this module and Jetty, so the adapter can start it with `java -jar` like the real one;
  * `SampleData` – the PoC's TestScripts, Example Instances and profiles, copied to `testdata/` (`r4`, `r5`, `samples`).
* **The test doubles are code in `src/main/java` of their own module, used in test scope only.** Every module
  depends on `testrunner-test-support` with `<scope>test</scope>`; an enforcer rule (`bannedDependencies` in the
  parent POM) fails the build for any other scope, so the doubles never reach a runtime class path or the exec jar.
* **No JDK-specific or internal APIs** (`com.sun.*`, `sun.*`, `jdk.internal.*`) in main code or test doubles: they are
  not part of Java SE. HTTP servers in tests use Jetty (version from the Spring Boot BOM). Enforced by
  `ArchitectureTest.noJdkSpecificOrInternalApis`, which also covers this module, since it is on the app module's test
  class path but not test code itself.
* Levels: unit tests per module (surefire, `*Test`), end-to-end through the Spring Boot app (failsafe, `*IT`),
  architecture rules (ArchUnit). `RealValidatorIT` runs the AT Core R4 suite with the real validator in both
  validator modes; it is opt-in via `VALIDATOR_JAR`.

## Alternatives
* *Test doubles in `src/test/java` of one module, shared as `test-jar` (`<type>test-jar</type>`, classifier
  `tests`)* – Maven does not resolve the transitive dependencies of a test-jar (here Jackson and Jetty), so every
  user would have to repeat them; the maven-jar-plugin documentation recommends a separate module with the shared
  code in `src/main/java` for this reason. The doubles also do not belong to any one module: core, the FHIR
  modules, the validator adapter and the app all use them, and `test-support` must not depend on core so that core
  can use it.
* *A copy of the doubles in every module's `src/test/java`* – duplication.
* *JDK `com.sun.net.httpserver` for the HTTP servers* (used until 2026-10) – no extra dependency, but a JDK-specific
  API outside Java SE.
* *Generating and compiling the fake validator's source at test time* (used until 2026-10, to get a self-contained
  jar) – the fake was a Java text block that the compiler, IDE and `-Xlint` never saw, and it needed the
  `javax.tools` compiler, i.e. a JDK rather than a JRE, to run the tests.

## Consequences
* `mvn verify` runs ~115 tests in under a minute, offline (after dependencies are cached).
* The fake validator jar works only on the machine and class path it was created on (absolute `Class-Path`
  entries); it is created per test run in a temporary folder, so that is all it needs.
* Jetty is a test dependency only; the runner itself has no HTTP server.
* The in-memory server is not a FHIR server; behaviour that depends on a real server (search semantics, conditional
  delete rules) is out of scope of the automated tests – the docker-compose HAPI setup of the PoC remains the way to
  test against one.

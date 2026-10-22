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
    whose `Class-Path` points at this module's test classes and Jetty, so the adapter can start it with `java -jar`
    like the real one;
  * `SampleData` – the PoC's TestScripts, Example Instances and profiles, copied to `testdata/` (`r4`, `r5`, `samples`).
* **The test doubles are test code**: they live in `src/test/java` of `testrunner-test-support`, which publishes them
  as test-jar (`maven-jar-plugin:test-jar`, classifier `tests`). Every module uses them as
  ```xml
  <dependency>
      <groupId>at.hl7.testing</groupId>
      <artifactId>testrunner-test-support</artifactId>
      <type>test-jar</type>
      <classifier>tests</classifier>
      <scope>test</scope>
  </dependency>
  ```
  (type and classifier are also set in the parent's `dependencyManagement`). Code analysis treats them as tests:
  SonarQube's Maven scanner takes `src/test/java` as `sonar.tests`, i.e. excluded from the main-code rules, coverage
  and duplication metrics, without any Sonar configuration. An enforcer rule (`bannedDependencies` in the parent POM)
  fails the build for any scope but `test`, so the doubles never reach a runtime class path or the exec jar.
* Dependencies of the doubles (Jackson, Jetty) are declared in **compile** scope in `testrunner-test-support`: Maven
  passes a test-jar's compile dependencies on to its users (in their test scope), but not its test-scoped ones. The
  module's main jar is empty; Maven requires one as the module's main artefact.
* **No JDK-specific or internal APIs** (`com.sun.*`, `sun.*`, `jdk.*`) in main or test code: they are not part of
  Java SE. HTTP servers in tests use Jetty (version from the Spring Boot BOM). Enforced by the compiler
  (`--limit-modules java.se`, [ADR-0010](0010-build.md)).
* Levels: unit tests per module (surefire, `*Test`), end-to-end through the Spring Boot app (failsafe, `*IT`),
  architecture rules (ArchUnit). `RealValidatorIT` runs the AT Core R4 suite with the real validator in both
  validator modes; it is opt-in via `VALIDATOR_JAR`.

## Alternatives
* *A test-support module with the doubles in `src/main/java`, used in test scope* (until 2026-10) – the layout the
  maven-jar-plugin documentation suggests, since a test-jar does not pass on test-scoped dependencies. But it makes
  test code main code: SonarQube and similar tools would analyse the doubles as production code (rules, coverage,
  duplication). Declaring the doubles' dependencies in compile scope solves the dependency problem of the test-jar.
* *The doubles in `src/test/java` of an existing module (e.g. core), shared as its test-jar* – the doubles do not
  belong to any one module, and core's tests would drag core's test dependencies into every user.
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
* A module that needs a new test double adds it to `testrunner-test-support/src/test/java`; a dependency the double
  needs goes into that module's POM in compile scope.
* The in-memory server is not a FHIR server; behaviour that depends on a real server (search semantics, conditional
  delete rules) is out of scope of the automated tests – the docker-compose HAPI setup of the PoC remains the way to
  test against one.

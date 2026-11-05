# ADR-0010: Maven build

## Decision
* Maven (Artifactory mirrors), parent `at.hl7.testing:testrunner-parent`, Java 25 (`maven.compiler.release`).
* **Versions only in properties**: third-party libraries come from two imported BOMs whose versions are properties
  (`spring-boot.version`, `hapi-fhir.version`); the few artefacts not covered by them (`hapi-fhir-caching-caffeine`,
  `archunit-junit5`) are managed once in the parent with a property. Plugin versions are properties too. Module
  POMs declare no versions.
* `maven-enforcer-plugin` requires Java ≥ 25 and Maven ≥ 3.9, and allows `testrunner-test-support` only in test
  scope (`bannedDependencies`, [ADR-0009](0009-testing.md)).
* The app module builds a plain jar (library) and `testrunner-app-<version>-exec.jar` (Spring Boot executable,
  classifier `exec`), so failsafe tests and library users use plain classes.
* Coverage with JaCoCo: the agent runs in every module's unit and integration tests, `testrunner-app` aggregates one
  report at `verify`. A report, not a gate: thresholds would reward tests written for the number.
* `-Xlint:all` without warnings, enforced: the compiler plugin sets `failOnWarning` (`-Werror`), so a warning fails
  the build instead of relying on a convention.
* `--limit-modules java.se` for main and test code: the compiler sees only Java SE, so `com.sun.*`, `sun.*` and other
  `jdk.*` APIs do not compile (they may be missing or change in other JDKs). Jetty serves HTTP in tests.
* `testrunner-test-support` is published as test-jar (classifier `tests`); its type and classifier are part of the
  parent's `dependencyManagement` entry ([ADR-0009](0009-testing.md)).

## Consequences
* Upgrading Spring Boot or HAPI is a one-property change; BOM alignment keeps Jackson, SLF4J, JUnit consistent.
* Build: `mvn verify` (tests + ITs), `mvn -DskipTests package` for the jar.

# ADR-0010: Maven build

## Decision
* Maven (Artifactory mirrors), parent `at.hl7.testing:testrunner-parent`, Java 25 (`maven.compiler.release`).
* **Versions only in properties**: third-party libraries come from two imported BOMs whose versions are properties
  (`spring-boot.version`, `hapi-fhir.version`); the few artefacts not covered by them (`hapi-fhir-caching-caffeine`,
  `archunit-junit5`) are managed once in the parent with a property. Plugin versions are properties too. Module
  POMs declare no versions.
* `maven-enforcer-plugin` requires Java ≥ 25 and Maven ≥ 3.9.
* The app module builds a plain jar (library) and `testrunner-app-<version>-exec.jar` (Spring Boot executable,
  classifier `exec`), so failsafe tests and library users use plain classes.
* `-Xlint:all` without warnings.

## Consequences
* Upgrading Spring Boot or HAPI is a one-property change; BOM alignment keeps Jackson, SLF4J, JUnit consistent.
* Build: `mvn verify` (tests + ITs), `mvn -DskipTests package` for the jar.

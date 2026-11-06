---
name: testrunner-build-and-run
description: How to build, test and run the FHIR TestScript Runner (TestRunnerOnJava) – Maven commands, the JDK/Maven paths on this machine, running single tests and the opt-in real-validator IT, starting the exec jar with dev configs, and the POM conventions (BOMs, version properties, enforcer, test-jar). Use before running mvn or java, or when editing a pom.xml.
---

# Build and run

## Toolchain

Requirements are enforced: Java ≥ 25, Maven ≥ 3.9 (wrong ones stop the build at `enforce`). `mvn` below means a
Maven 3.9 with `JAVA_HOME` on a JDK 25. If the shell's `mvn` does not work, call Maven by its full path, e.g. on
the original development machine (where the `mvn` shell function needs an unset `M2_HOME`):

```sh
export JAVA_HOME=~/iheroot/tools/JDK/jdk-25.0.2/Contents/Home
~/iheroot/tools/Maven/3.9.16/bin/mvn -B verify
```

Offline (`-o`) works only once all dependencies and plugins are in the local repository.

## Commands

| Goal | Command (`mvn` as described above) |
|---|---|
| Everything: compile, unit tests (surefire `*Test`), ITs (failsafe `*IT`) | `mvn -B verify` (< 1 min, no FHIR server, no network) |
| Jar without tests | `mvn -B -DskipTests package` |
| One module and what it needs | `mvn -B -pl testrunner-validation-cli -am test` |
| One test class | `mvn -B -pl testrunner-core -am test -Dtest=OperationsTest -Dsurefire.failIfNoSpecifiedTests=false` |
| One IT only | `mvn -B -pl testrunner-app -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=EndToEndIT -Dfailsafe.failIfNoSpecifiedTests=false` |
| With the real HL7 validator (`RealValidatorIT`, both modes, ~1 min) | `VALIDATOR_JAR=/path/to/validator_cli.jar mvn -B verify` (a validator 7.0.2 ships with the PoC: `../HL7-AT-TestFhiry-TestRunner/data_r4/validator_cli.jar`) |

`RealValidatorIT` is skipped unless `VALIDATOR_JAR` is set; the skip in the summary is expected.
`HapiServerIT` (Testcontainers, HAPI FHIR R4 + R5 + a second R4) runs only where Docker is reachable, else its 4 tests are
skipped. Only it: the "One IT only" command with `-Dit.test=HapiServerIT`. On this machine (Rancher Desktop) set
`DOCKER_HOST=unix://$HOME/.rd/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` –
without them `HapiServerIT` is **skipped and the build stays green** ("Could not find a valid Docker environment");
check for `Skipped: 4` before claiming it ran. Wiki: `docs/wiki/build-and-test.md#docker-on-macos`.
Testcontainers' version comes from the Spring Boot BOM (`testcontainers-junit-jupiter`, test scope in
`testrunner-app`); the image tag `hapiproject/hapi:v8.12.0-1` is a constant in `HapiServerIT`.

## CI (GitHub Actions, `.github/workflows/build.yml`)

* Job `verify`: push to `main` and every pull request → `mvn -B -ntp verify` on Temurin 25 (`setup-java` with
  `cache: maven`); `ubuntu-latest` has Docker, so `HapiServerIT` runs there; the coverage report is uploaded as
  artefact `coverage`, the SBOM as `sbom`, on failure the surefire/failsafe reports as `test-reports`; then
  `docker build -t testrunner:ci testrunner-app` and a `--check` of `config/dev-r4.yml` with the image.
* `.github/dependabot.yml`: weekly (Monday) PRs for Maven dependencies/plugins (groups `spring-boot`, `hapi-fhir`,
  `maven-plugins`; major versions of Spring Boot and HAPI ignored) and GitHub Actions. A HAPI update should also
  raise `HapiServerIT.IMAGE`.
* Job `real-validator`: nightly (`cron 17 3 * * *`) and `workflow_dispatch` only → downloads the pinned
  `validator_cli.jar` (`env.VALIDATOR_VERSION`, 7.0.2 = the version `docs/validation.md` was verified with), caches it
  and `~/.fhir/packages` (`actions/cache`, key `validator-<version>`), runs only `RealValidatorIT` (the command
  of the "One IT only" row with `VALIDATOR_JAR`). Raising the version: run that command locally with the new jar
  first; the negative control must still FAIL.
* Actions are pinned to major versions (`checkout@v7`, `setup-java@v6`, `cache@v6`, `upload-artifact@v7`);
  `permissions: contents: read`.

## Running the application

```sh
java -jar testrunner-app/target/testrunner-app-0.1.0-SNAPSHOT-exec.jar --config=config/dev-r4.yml
#   --testrunner.validation.mode=process   one validator process per validation (default: server)
#   --novalidator                          no profile validation
#   --check                                load + check the TestScripts only, no server, nothing sent
#   --testscript='*update*' --test=Read    only these TestScripts (file name, name, url) / tests (name), globs
#   --list                                 print what would run (with the selection), send and write nothing
#   zsh: quote '--testrunner.suites[0].base-dir=…' – unquoted [0] is a glob ("no matches found")
#   --testrunner.<property>=<value>        any property, see testrunner-configuration
```

* The dev configs read `VALIDATOR_JAR` from the environment and expect a FHIR server at
  `http://localhost:8080/fhir` (`dev-all.yml` also `8081` for R5 and `8082`, a second R4 for `several-servers`):
  `docker run -p 8080:8080 -e hapi.fhir.fhir_version=R4 hapiproject/hapi:latest`.
* `config/negative-control.yml` is **expected to FAIL** (proves validation runs).
* Results: `target/testrunner-results/run_<timestamp>/` with the dev configs (`testrunner-results/` otherwise).
* Exit codes: 0 pass, 1 fail/error, 2 invalid configuration, 3 skipped (default: only when every TestScript was;
  `testrunner.exit-on-skip=all|any|never`, `RunCommand.exitCode`).
* The app module builds a plain library jar and `*-exec.jar` (Spring Boot, classifier `exec`).

## Container image (ADR-0015)

```sh
mvn -B -DskipTests package && docker build -t testrunner testrunner-app      # needs the exec jar first
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/workspace" testrunner --config=config/dev-r4.yml --check --novalidator
docker compose run --rm testrunner [--config=… --novalidator …]              # + HAPI FHIR v8.12.0-1, waits for it
docker compose down
```

* `testrunner-app/Dockerfile`: `eclipse-temurin:25.0.5_8-jre-noble`, two stages (`jarmode=tools extract --layers`,
  then dependencies / loader / snapshot / application layers into `/app`, `java -jar /app/testrunner.jar`), user
  `runner` 10001, `HOME=/home/runner` (`.fhir/packages` and `/workspace` mode 1777 for any `--user`), working dir
  `/workspace`, entry point via the base image's `/__cacert_entrypoint.sh` (`USE_SYSTEM_CA_CERTS=1` + `/certificates`).
  `.dockerignore` lets only `target/testrunner-app-*-exec.jar` into the context.
* `docker-compose.yml` (root): `hapi` (settings as `HapiServerIT`, port 8080), `hapi-ready` (`curlimages/curl` retries
  `/fhir/metadata`; HAPI's image is distroless, no shell), `testrunner` (build `testrunner-app`, mounts `.`,
  `${VALIDATOR_JAR:-./validator_cli.jar}` → `/opt/validator/validator_cli.jar`, `~/.fhir/packages`; env
  `TESTRUNNER_FHIRSERVER_BASEURL=http://hapi:8080/fhir`; default command `--config=config/dev-r4.yml`).
* CI job `verify` builds `testrunner:ci` and runs `--check` on `config/dev-r4.yml` with it; nothing is pushed.
  Dependabot (`package-ecosystem: docker`, `/testrunner-app`) raises the base image.
* On this machine `docker` needs `DOCKER_HOST=unix://$HOME/.rd/docker.sock` (Rancher Desktop).

## POM conventions (ADR-0010)

* Third-party versions come from two imported BOMs whose versions are properties in the root POM:
  `spring-boot.version` (Jackson, SLF4J, Logback, JUnit, AssertJ, Jetty, JsonPath) and `hapi-fhir.version`. Artefacts
  outside them (`hapi-fhir-caching-caffeine`, `archunit-junit5`) are managed once in the parent with a property.
* Plugin versions are properties too. **Module POMs declare no versions.**
* Coverage (JaCoCo, `jacoco-maven-plugin.version`): `prepare-agent` in every module (root `<build><plugins>`, only
  `at.hl7.testing.runner.*` instrumented; it sets `argLine` for surefire and failsafe – a module that sets its own
  `argLine` must append `@{argLine}`), `report-aggregate` in `testrunner-app` at `verify` →
  `testrunner-app/target/site/jacoco-aggregate/index.html` (+ `jacoco.xml`, `jacoco.csv`). Report only, no gate.
  `report-aggregate` covers only **direct** dependencies of the app, which is why the app POM names `testrunner-core`
  and `testrunner-fhir-hapi` itself; a new module must be a direct dependency of the app too.
* SBOM (CycloneDX, `cyclonedx-maven-plugin.version`): `makeBom` in `testrunner-app` at `package` →
  `testrunner-app/target/bom.json`/`bom.xml`, runtime libraries only (`includeTestScope=false`). Skipped in offline
  mode (`-o`): "Goal makeBom requires online mode".
* Imported BOMs manage dependencies only, never plugins: every plugin version is a property in the root POM, even if
  the Spring Boot BOM's own `pluginManagement` names it (CycloneDX does; it does not reach importers).
* Compiler: `release 25`, `-parameters`, `-Xlint:all,-processing,-serial`, `--limit-modules java.se`,
  `failOnWarning` (`-Werror`: any warning fails the build; fix it, or `@SuppressWarnings` with a reason).
* Project modules are in the parent's `dependencyManagement`. `testrunner-test-support` is there with
  `<type>test-jar</type><classifier>tests</classifier>`; a module uses it as

  ```xml
  <dependency>
      <groupId>${project.groupId}</groupId>
      <artifactId>testrunner-test-support</artifactId>
      <type>test-jar</type>
      <classifier>tests</classifier>
      <scope>test</scope>
  </dependency>
  ```

  Any other scope fails the enforcer (`bannedDependencies`). Its main jar is empty on purpose (Maven needs a main
  artefact; the "JAR will be empty" warning is expected).
* A new module: add it to `<modules>` and to `dependencyManagement`; if it is a FHIR version, also as dependency of
  `testrunner-app` (see `testrunner-fhir-versions`).

## Typical failures

| Symptom | Cause |
|---|---|
| `/bin/mvn: no such file` | plain `mvn` used; use the full path |
| Build stops at `enforce` | wrong JDK (`JAVA_HOME`) or Maven, or test-support used outside test scope |
| `warnings found and -Werror specified` (German: `Warnungen gefunden und -Werror angegeben`) | a compiler warning, e.g. a raw type or an unchecked cast; `[WARNING]` lines above name it |
| `package com.sun... is not visible` | JDK-specific API used; only Java SE is allowed (use Jetty for HTTP servers in tests) |
| `ArchitectureTest` fails | a module boundary was crossed, see `testrunner-overview` |
| `No tests matching pattern` | `-Dtest`/`-Dit.test` without the matching `failIfNoSpecifiedTests=false` |
| `-pl <module>` alone fails with `HAPI-1680 … structure JAR` or `POM … is invalid` | without `-am`, the other modules come from the local repository – possibly an old `mvn install`. Always `-pl <module> -am` |

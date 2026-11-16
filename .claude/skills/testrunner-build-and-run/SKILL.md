---
name: testrunner-build-and-run
description: How to build, test and run the FHIR TestScript Runner (TestRunnerOnJava) – Maven commands, how to find and select a JDK 25 and Maven 3.9, running single tests and the opt-in real-validator IT, starting the exec jar with dev configs, and the POM conventions (BOMs, version properties, enforcer, test-jar). Use before running mvn or java, or when editing a pom.xml.
---

# Build and run

## Toolchain

Requirements are enforced: Java ≥ 25, Maven ≥ 3.9 (wrong ones stop the build at `enforce`). `mvn` below means a
Maven ≥ 3.9 that runs on a JDK 25 or newer. The repository has no Maven wrapper and the install locations differ per
machine, so **find them, do not assume them**. CI uses Temurin 25 (`actions/setup-java`).

1. See what the shell has: `mvn -v` prints the Maven version and the JDK it runs on (`Java version: …`). If that
   says Java 25+ and Maven 3.9+, you are done. A `JAVA_HOME` that is already set may name an older JDK (e.g. 17 or
   21), and `mvn -v` then fails the requirement or shows the wrong one.
2. Otherwise select a JDK 25 by setting `JAVA_HOME` for the command (every tool call starts a fresh shell, so set it
   in the same call as `mvn`):

   | Where the JDK is | How |
   |---|---|
   | macOS, registered with the system | `export JAVA_HOME=$(/usr/libexec/java_home -v 25)`; `/usr/libexec/java_home -V` lists what is installed |
   | Linux, from a package | `export JAVA_HOME=/usr/lib/jvm/<jdk-25 folder>`, e.g. `temurin-25-jdk-amd64`; `ls /usr/lib/jvm` shows the names |
   | SDKMAN | `sdk install java 25-tem`, then `sdk use java 25-tem` (it sets `JAVA_HOME` and `PATH`) |
   | Unpacked anywhere (a tools folder, a download) | the folder that contains `bin/java`; in a macOS `.tar.gz` that is `<folder>/Contents/Home` |

   Check it with `"$JAVA_HOME/bin/java" -version` before building.
3. If `mvn` is missing, or runs another Maven than you want (a wrapper script or shell function, an `M2_HOME` that
   points elsewhere), call the Maven you want by its full path instead: `/path/to/apache-maven-3.9.x/bin/mvn -B verify`
   (`unset M2_HOME` first if the script insists on it).

```sh
export JAVA_HOME=<JDK 25 home, see above>
mvn -B verify          # or the full path of a Maven 3.9, see 3.
```

If there is no JDK 25 on the machine, install one (e.g. Temurin 25) or ask the user where it is; do not put a
machine's paths into this skill, a README or a script.

Offline (`-o`) works only once all dependencies and plugins are in the local repository.

## Commands

| Goal | Command (`mvn` as described above) |
|---|---|
| Everything: compile, unit tests (surefire `*Test`), ITs (failsafe `*IT`) | `mvn -B verify` (< 1 min, no FHIR server, no network) |
| Jar without tests | `mvn -B -DskipTests package` |
| One module and what it needs | `mvn -B -pl testrunner-validation-cli -am test` |
| One test class | `mvn -B -pl testrunner-core -am test -Dtest=OperationsTest -Dsurefire.failIfNoSpecifiedTests=false` |
| One IT only | `mvn -B -pl testrunner-app -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=EndToEndIT -Dfailsafe.failIfNoSpecifiedTests=false` |
| With the real HL7 validator (`RealValidatorIT`, both modes, ~1 min) | `VALIDATOR_JAR=/path/to/validator_cli.jar mvn -B verify` (get the version CI pins, `VALIDATOR_VERSION` in `.github/workflows/build.yml`: `curl -fsSL -o /tmp/validator_cli.jar https://github.com/hapifhir/org.hl7.fhir.core/releases/download/<version>/validator_cli.jar`; a `validator_cli.jar` in the repository root is git-ignored and the default of `docker-compose.yml`) |

`RealValidatorIT` is skipped unless `VALIDATOR_JAR` is set; the skip in the summary is expected.
`HapiServerIT` (Testcontainers, HAPI FHIR R4 + R5 + a second R4) runs only where Docker is reachable, else its 4 tests are
skipped. Only it: the "One IT only" command with `-Dit.test=HapiServerIT`.

**Docker on macOS with Rancher Desktop or Colima.** Testcontainers looks for `/var/run/docker.sock` (Linux and CI
have it); these tools put their socket elsewhere. Then `HapiServerIT` is **skipped and the build stays green**
("Could not find a valid Docker environment"), so check for `Skipped: 4` before claiming it ran. Which tool is in use:
`ls ~/.rd/docker.sock ~/.colima/default/docker.sock` (or `docker context ls`). Set both variables in the shell that
runs Maven:

```sh
export DOCKER_HOST=unix://$HOME/.rd/docker.sock                  # Rancher Desktop
# export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock    # Colima (documented default, untested here)
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Details and why: `docs/wiki/build-and-test.md#docker-on-macos`.

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
* Job `real-validator`: every pull request, push to `main`, nightly (`cron 17 3 * * *`) and `workflow_dispatch`
  (in parallel to `verify`; together they run every test before a merge into `main`) → downloads the pinned
  `validator_cli.jar` (`env.VALIDATOR_VERSION`, 7.0.2 = the version `docs/validation.md` was verified with), caches it
  and `~/.fhir/packages` (`actions/cache`, key `validator-<version>`), fills the package cache with one serial
  validator run in a step of its own (so a network problem fails there, not inside the IT; the runner itself already
  keeps validators from racing on an empty cache, `PackageCacheGate`, see `testrunner-validation`), then runs only
  `RealValidatorIT` (the command of the "One IT only" row with `VALIDATOR_JAR`). To try a cold cache locally without
  touching `~/.fhir`: `TESTRUNNER_VALIDATION_JVMARGUMENTS=-Duser.home=/tmp/fhir-home` in front of that command. Raising the version: run that command locally with the new jar
  first; the negative control must still FAIL.
* `.github/workflows/image.yml` (ADR-0020): jobs `jar` (`mvn verify`, `package` only on a pull request; fails if
  `VALIDATOR_VERSION` of `build.yml` and of the Dockerfile differ; hands the exec jar on) and `image` (matrix `slim` and
  `validator`): build for amd64, `ci/smoke-test-image.sh`, the Action (`uses: ./`, validator leg against
  `docker compose run --rm hapi-ready`), and on a push to `main` or a tag `vX.Y.Z` a push of amd64+arm64 with
  provenance and SBOM to `ghcr.io/<owner>/fhir-testrunner` (tags via `docker/metadata-action`: `X.Y.Z`, `X.Y`, `X` not for
  0.x, `latest`, `edge`, `sha-…`, the validator image with the suffix `-validator`, also on `latest`). Pull requests push
  nothing. The tag, not the POM version, names the images; the first push creates the package private.
* Actions are pinned to major versions (`checkout@v7`, `setup-java@v6`, `cache@v6`, `upload-artifact@v7`, `download-artifact@v8`, `docker/*` v4 to v7);
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

## Container images (ADR-0015, ADR-0020)

```sh
mvn -B -DskipTests package && docker build -t testrunner testrunner-app      # needs the exec jar first
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/workspace" testrunner --config=config/dev-r4.yml --check --novalidator
docker build -t testrunner:validator --target validator testrunner-app       # + validator_cli.jar at /opt/validator
ci/smoke-test-image.sh testrunner slim; ci/smoke-test-image.sh testrunner:validator validator   # needs the internet
docker compose run --rm testrunner [--config=… --novalidator …]              # + HAPI FHIR v8.12.0-1, waits for it
docker compose down
```

* `testrunner-app/Dockerfile`: `eclipse-temurin:25.0.5_8-jre-noble` (Java 25 LTS; Dependabot ignores majors of it),
  stages `layers` (`jarmode=tools extract --layers`), `base` (dependencies / loader / snapshot / application layers into
  `/app`, user `runner` 10001, `HOME=/home/runner` with `.fhir/packages` and `/workspace` mode 1777 for any `--user`,
  working dir `/workspace`), `validator` (`FROM base`, `ADD --checksum` of `ARG VALIDATOR_VERSION`/`VALIDATOR_SHA256`
  to `/opt/validator`, its `LICENSE.txt`, `ENV VALIDATOR_JAR`; the folder is made first, `ADD --chmod` would give it the
  file mode) and `slim` (`FROM base`, the **last** stage, so a plain `docker build` is the runner only).
  Entry point: `testrunner-app/docker/testrunner`, a launcher on the `PATH` (`exec /__cacert_entrypoint.sh java -jar
  /app/testrunner.jar "$@"`; `USE_SYSTEM_CA_CERTS=1` + `/certificates`), also what a pipeline with `entrypoint: [""]`
  calls. `.dockerignore` lets only `target/testrunner-app-*-exec.jar` and `docker/testrunner` into the context.
* Raising the validator: `VALIDATOR_VERSION` in `build.yml` **and** the two `ARG`s of the Dockerfile (`sha256sum` of the
  jar); `image.yml` fails if the versions differ. Docker on macOS: bind mounts do not keep the owner of files a container
  user creates (a run as `--user 12345` on a mount failed with `NoSuchFileException` for the result folder), so the smoke
  test uses a Docker volume.
* `docker-compose.yml` (root): `hapi` (settings as `HapiServerIT`, port 8080), `hapi-ready` (`curlimages/curl` retries
  `/fhir/metadata`; HAPI's image is distroless, no shell), `testrunner` (build `testrunner-app`, mounts `.`,
  `${VALIDATOR_JAR:-./validator_cli.jar}` → `/opt/validator/validator_cli.jar`, `~/.fhir/packages`; env
  `TESTRUNNER_FHIRSERVER_BASEURL=http://hapi:8080/fhir`; default command `--config=config/dev-r4.yml`).
* CI job `verify` builds `testrunner:ci` and runs `--check` on `config/dev-r4.yml` with it; nothing is pushed there.
  `image.yml` publishes (see above). Dependabot (`package-ecosystem: docker`, `/testrunner-app`) raises the base image.
* Consumers: `action.yml` (composite GitHub Action, `docker run --user $(id -u) --network host`, package cache with
  cache/restore + cache/save, outputs `exit-code` and `results-path`, version from `github.action_ref`) and
  `ci/testrunner.gitlab-ci.yml` (hidden job `.testrunner`, `entrypoint: [""]`, `docker: {user: "0"}`, variables
  `FHIR_TESTRUNNER_*` – **not** `TESTRUNNER_*`, which the runner reads as properties – packages in `.fhir-cache/`, junit
  report). The GitLab template is not run on GitLab by CI. Wiki: `docs/wiki/ci-integration.md`.
* On macOS with Rancher Desktop or Colima, `docker` may need `DOCKER_HOST` set as in the Commands section above if
  `docker info` cannot connect.

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
| `/bin/mvn: no such file`, or `mvn` not found | the shell's `mvn` is a broken wrapper or missing; call a Maven 3.9 by its full path (Toolchain, step 3) |
| Build stops at `enforce` | wrong JDK (`JAVA_HOME`) or Maven, or test-support used outside test scope |
| `warnings found and -Werror specified` (German: `Warnungen gefunden und -Werror angegeben`) | a compiler warning, e.g. a raw type or an unchecked cast; `[WARNING]` lines above name it |
| `package com.sun... is not visible` | JDK-specific API used; only Java SE is allowed (use Jetty for HTTP servers in tests) |
| `ArchitectureTest` fails | a module boundary was crossed, see `testrunner-overview` |
| `No tests matching pattern` | `-Dtest`/`-Dit.test` without the matching `failIfNoSpecifiedTests=false` |
| `-pl <module>` alone fails with `HAPI-1680 … structure JAR` or `POM … is invalid` | without `-am`, the other modules come from the local repository – possibly an old `mvn install`. Always `-pl <module> -am` |

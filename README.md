# FHIR TestScript Runner (Java)

Executes FHIR® `TestScript` resources (R4 and R5, R6-ready) against a FHIR server and reports the results as
JUnit XML, text summary and JSON, with a full log and a communication log of every request. Java re-design of the
Python proof of concept [HL7-AT-TestFhiry-TestRunner](../HL7-AT-TestFhiry-TestRunner).

## Quick start

Requirements: Java 25, Maven 3.9+. For profile validation the HL7 Validator CLI (`validator_cli.jar`).

```sh
mvn verify                                   # build, unit tests, integration tests (no FHIR server needed)
VALIDATOR_JAR=/path/to/validator_cli.jar mvn verify   # additionally with the real validator, both modes

# run the AT Core R4 TestScripts against a local server (the validator keeps running as a server)
export VALIDATOR_JAR=/path/to/validator_cli.jar
java -jar testrunner-app/target/testrunner-app-0.1.0-SNAPSHOT-exec.jar --config=config/dev-r4.yml

# with one validator process per validation instead
java -jar testrunner-app/target/testrunner-app-0.1.0-SNAPSHOT-exec.jar --config=config/dev-r4.yml --testrunner.validation.mode=process

# without profile validation
java -jar testrunner-app/target/testrunner-app-0.1.0-SNAPSHOT-exec.jar --config=config/dev-r4.yml --novalidator

# only check the TestScripts: load, check, validate them – no FHIR server needed, nothing is sent
java -jar testrunner-app/target/testrunner-app-0.1.0-SNAPSHOT-exec.jar --config=config/dev-r4.yml --check
```

A local HAPI server: `docker run -p 8080:8080 -e hapi.fhir.fhir_version=R4 hapiproject/hapi:v8.12.0-1`.

Writing a TestScript of your own: start with the walk-through in [docs/wiki/samples.md](docs/wiki/samples.md).

Results go to `target/testrunner-results/run_<timestamp>/` (`junit.xml`, `summary.txt`, `result.json`, a FHIR
`TestReport` per TestScript in `testreports/`, `testrunner.log`, `communication/`). Exit code: 0 passed, 1 failed/errors, 2 invalid configuration, 3 everything skipped (nothing tested;
`testrunner.exit-on-skip`). A check (`--check`) writes `check_<timestamp>/` without TestReports and communication;
exit code 1 means a TestScript has problems.

## Project layout

```
pom.xml                                 parent: modules, BOMs, version properties
testrunner-core/                        engine, version-neutral model, HTTP, reports, ports – no Spring, no HAPI
testrunner-fhir-hapi/                   generic TestScript mapping and FHIRPath on HAPI FHIR
testrunner-fhir-r4/  testrunner-fhir-r5/   one FHIR version each (FhirContext + dialect)
testrunner-validation-cli/              profile validation with validator_cli.jar (process or server mode)
testrunner-spring-boot-autoconfigure/   pre-defined Spring configuration (testrunner.* properties)
testrunner-app/                         executable jar, end-to-end ITs, architecture rules
testrunner-test-support/                test doubles (src/test, test-jar): in-memory FHIR server, fake validator, sample data
config/                                 development configurations
testdata/                               TestScripts, Example Instances, profiles from the PoC (r4, r5, samples)
docs/                                   architecture, decisions, configuration, logging, validation, FHIR versions
.claude/skills/                         condensed project knowledge for coding agents (Claude Code skills)
```

## Documentation

* [Wiki](docs/wiki/README.md) – guided tour: modules, how a run works, design insights, extending, troubleshooting
* [Architecture](docs/architecture.md) – modules, runtime, execution semantics, differences to the PoC, open points
* [FHIR versions](docs/fhir-versions.md) – how R4/R5 are mapped without duplication, and how to add R6
* [Configuration](docs/configuration.md) – all properties, PoC mapping, library use, extending the Spring configuration
* [Logging](docs/logging.md) – run log, request ids, communication log
* [Validation](docs/validation.md) – validator setup, process and server mode, silent-pass protection, the negative control
* [Decisions (ADRs)](docs/adr/README.md)

Condensed versions for coding agents live in [`.claude/skills/`](.claude/skills) (Claude Code project skills,
start with `testrunner-overview`). They summarize the documents above; when a behaviour changes, update the
document, the ADR and the matching skill together.

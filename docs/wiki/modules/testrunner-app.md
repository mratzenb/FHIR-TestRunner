# testrunner-app

[Home](../README.md) › [Modules](README.md) › testrunner-app

The executable jar. A small Spring Boot command line application. It also hosts the end-to-end tests and the
architecture rules, since it is the only module that sees everything.

**Dependencies:** autoconfigure, fhir-r4, fhir-r5, validation-cli, `spring-boot-starter`.

## Classes

| Class | Job |
|---|---|
| `TestRunnerApplication` | `main`. Translates the short options, starts Spring Boot, exits with the exit code. |
| `RunCommand` | `ApplicationRunner`: runs the configured TestScripts once, turns the outcome into the exit code. |
| `ValidatorCheck` | Warns at start-up when validation is on but the jar or Java is missing. |

## From command line to exit code

```mermaid
sequenceDiagram
    participant U as User
    participant M as TestRunnerApplication.main
    participant S as Spring Boot
    participant R as RunCommand
    participant T as TestRunner
    U->>M: java -jar …-exec.jar --config=dev-r4.yml
    M->>M: translate: --config → spring.config.additional-location<br/>--novalidator → testrunner.validation.enabled=false
    M->>S: SpringApplication.run
    S->>S: auto-configuration binds testrunner.*, wires beans
    S->>R: run(args)
    R->>T: run(RunConfiguration)
    T-->>R: RunResult
    R-->>S: exit code 0 / 1 / 2
    S-->>M: context closed (validator servers stopped)
    M-->>U: System.exit(code)
```

## Command line

```sh
java -jar testrunner-app/target/testrunner-app-0.1.0-SNAPSHOT-exec.jar --config=config/dev-r4.yml
```

| Option | Short for |
|---|---|
| `--config=<file>` | `--spring.config.additional-location=file:<file>` |
| `--novalidator` | `--testrunner.validation.enabled=false` |
| `--testrunner.<property>=<value>` | any single property |

All properties: [configuration.md](../../configuration.md#reference).

`testrunner.run-on-startup=false` turns off `RunCommand`. Useful to embed the app or to test the wiring.

## Defaults of the jar

`src/main/resources/application.yml` holds only defaults: no banner, no web server, console at `INFO`, all four
report formats. Real settings belong in a `--config` file. Development configurations are in
[`config/`](../../../config).

## Two jars

The build produces two artefacts ([ADR-0010](../../adr/0010-build.md)):

| Jar | Use |
|---|---|
| `testrunner-app-<version>.jar` | plain jar – a library, and what the failsafe tests use |
| `testrunner-app-<version>-exec.jar` | Spring Boot executable jar (~97 MB, mostly HAPI) |

## Tests in this module

| Test | Kind | Covers |
|---|---|---|
| `TestRunnerApplicationTest` | unit | translation of the short options |
| `EndToEndIT` | failsafe | the whole app on the sample TestScripts, with an in-memory FHIR server and the fake validator |
| `RealValidatorIT` | failsafe, opt-in | AT Core R4 suite incl. the negative control with the **real** validator, both modes. Runs only with `VALIDATOR_JAR` set. |
| `ArchitectureTest` | unit (ArchUnit) | the module boundaries |

`ArchitectureTest` lives here because only this module has every other module on its classpath.

## Related

* [Build and test](../build-and-test.md)
* Skills: `.claude/skills/testrunner-build-and-run`, `testrunner-configuration`

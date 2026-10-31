# Configuration

Everything the runner needs is one immutable object, `RunConfiguration` (package
`at.hl7.testing.runner.core.config`). It does not depend on any framework:

* **As a library**, build it in code and call `TestRunner.run(configuration)`.
* **As the executable jar**, Spring Boot binds it from the externalized properties under the prefix `testrunner`:
  YAML/properties files, command line arguments, system properties, environment variables, profiles.

Absent values get the defaults below; `RunConfiguration.problems()` lists everything that keeps a run from starting,
all at once (exit code 2).

## Running

```sh
java -jar testrunner-app/target/testrunner-app-0.1.0-SNAPSHOT-exec.jar --config=config/dev-r4.yml
```

| Option | Meaning |
|---|---|
| `--config=<file>` | Adds a configuration file (short for `--spring.config.additional-location=file:<file>`) |
| `--novalidator` | Disables profile validation (short for `--testrunner.validation.enabled=false`) |
| `--testrunner.<property>=<value>` | Overrides any single property |
| `--spring.profiles.active=<p>` | Activates `application-<p>.yml` files placed next to the jar or in `./config/` |

Precedence (highest first) follows Spring Boot: command line → system properties → environment variables
(`TESTRUNNER_FHIRSERVER_BASEURL=…`) → `--config` file → `application.yml` in the jar.

Development configurations live in [`config/`](../config): `dev-r4.yml`, `dev-r5.yml`, `dev-all.yml` (all
samples, parallel, two servers), `negative-control.yml`. They read the validator location from the
environment variable `VALIDATOR_JAR`; like the defaults they use the validator server mode
(`--testrunner.validation.mode=process` switches to one process per validation).

## Reference

```yaml
testrunner:
  fhir-server:
    base-url: http://localhost:8080/fhir   # without it every TestScript is skipped
    headers:                               # added to every request (unless the TestScript sets the header)
      Authorization: Bearer ${FHIR_TOKEN}

  suites:                                  # one entry per set of TestScripts (typically one IG)
    - name: at-core-r4                     # default: folder name of base-dir
      base-dir: testdata/r4                # the folders below are resolved against it
      fhir-version: AUTO                   # AUTO (detect per file) | R4 | R5 | R6 | 4.0 | 5.0.0 …
      test-scripts-dir: Test_Scripts       # default
      example-instances-dir: Example_Instances  # default; resources behind fixture.resource.reference
      profiles-dir: Profiles               # default; passed to the validator as -ig
      igs: []                              # further -ig for this suite: package ids (hl7.at.fhir.core.r4#2.0.0),
                                           # folders, files; needs a pinned fhir-version; see validation.md
      include: ["*.json", "*.xml"]         # glob patterns relative to test-scripts-dir; default all JSON/XML
      fhir-server:                         # optional: own server for this suite (e.g. an R5 server)
        base-url: http://localhost:8081/fhir
      parallel: true                       # false: with parallelism > 1, run this suite's TestScripts one after
                                           # the other before all others, see ADR-0008
    - package-id: hl7.at.fhir.core.r4#2.1.0  # instead of base-dir: a suite read from a FHIR package, see below

  http:
    connect-timeout: 10s
    request-timeout: 60s
    follow-redirects: true

  execution:
    parallelism: 1                         # TestScripts run at the same time, see ADR-0008

  validation:
    enabled: true                          # false: validateProfileId becomes a warning, nothing is validated
    mode: server                           # server: long-running validator servers (default), see validation.md
                                           # process: one validator process per validation
    validator-jar: validator_cli.jar       # REQUIRED when enabled; path to the HL7 Validator CLI
    java-executable:                       # default: the java running the runner
    jvm-arguments: []                      # for the validator JVM, e.g. [-Xmx2g]
    terminology-server: n/a                # value of -tx; n/a = no terminology checks
    locale: en                             # language of the validator messages
    timeout: 5m                            # per validation; in server mode consider 1m, see validation.md
    validate-test-scripts: true            # validate each TestScript against its FHIR version before running it
    extra-arguments: []                    # passed to the validator as they are (both modes)
    server:                                # only for mode: server
      startup-timeout: 5m                  # until a server must accept connections
      fallback-to-process: true            # server failed to start or died: use process mode for its validations;
                                           # false: report those validations as errors

  reporting:
    output-dir: testrunner-results         # every run gets output-dir/run_<timestamp>/
    formats: [junit, summary]              # junit | summary | json | testreport (FHIR TestReports, logging.md)
                                           # | any format of an added ReportWriter; the app sets all four
    communication-log: true                # one folder per request/response, see logging.md
    redact-headers: [Authorization, Proxy-Authorization, Cookie, Set-Cookie]
                                           # values masked in the communication log ([] = none)
    log-file: true                         # testrunner.log in the run folder
    log-file-level: DEBUG                  # TRACE | DEBUG | INFO | WARN | ERROR

  packages:                                # for suites with package-id
    cache-dir: ~/.fhir/packages            # default; the cache of the validator, IG Publisher and SUSHI
    registry: https://packages.fhir.org    # default; where missing packages are downloaded from

  run-on-startup: true                     # app only: false to wire everything without running (tests, embedding)
  exit-on-skip: all                        # app only: when skipped TestScripts give exit code 3 instead of 0:
                                           # all (default, nothing was tested) | any (a skip, no failure) | never
```

### Suites from FHIR packages

An IG is published as a FHIR package; the IG Publisher puts the TestScripts into it (`package/TestScript-*.json`)
and the examples into its example folder. A suite with `package-id` instead of `base-dir` runs them from there:

```yaml
testrunner:
  suites:
    - package-id: hl7.at.fhir.core.r4#2.1.0   # name#version; #dev = built locally by the IG Publisher
      include: ["TestScript-*-create-*.json"] # optional, default all TestScript-*.json|xml of the package
```

Before the run (`PackageSuites`), the package is looked up in the package cache (`packages.cache-dir`, as
`<id>#<version>/package/`); a missing one is downloaded from `packages.registry` and unpacked into the cache, where
the validator finds it too. A `#dev` package is never downloaded. From the package's `package.json` the suite takes

| Suite setting | From the package |
|---|---|
| TestScripts | `package/`, the files matching `include` (default `TestScript-*.json`, `TestScript-*.xml`) |
| Example Instances (fixtures, required CapabilityStatements) | `directories.example` (`example/` or `examples/`) |
| `fhir-version` | `fhirVersions`, unless configured |
| `igs` | the package itself, its `dependencies` without the core package, then the configured `igs` |
| `name` | the package id, unless configured |

A package that cannot be found, downloaded or read stops the run before anything is sent (exit code 2), like any
other configuration problem. The cache can be shared by several runs and the validator at once: a download is
unpacked into a folder of its own and moved into place, and a package another process put there meanwhile is used.
Archives with long file names (GNU or pax headers, as npm writes them) are read completely.

Relaxed binding applies: `base-dir`, `baseDir` and `BASEDIR` are the same; durations accept `90s`, `2m`, `PT1M`.

### Mapping from the PoC's `config.json`

| PoC | Here |
|---|---|
| `fhirServer` | `testrunner.fhir-server.base-url` |
| `path` | `testrunner.suites[n].base-dir` |
| `testscripts: [...]` | `testrunner.suites[n].include` (globs, relative to `Test_Scripts/`) |
| `results_path` | `testrunner.reporting.output-dir` |
| `validation.fhirVersion` | not needed: the version comes from each TestScript (or `suites[n].fhir-version`) |
| `<path>/validator_cli.jar` (fixed) | `testrunner.validation.validator-jar` (any path) |
| `--novalidator` | `--novalidator` or `testrunner.validation.enabled=false` |
| (validator started per assertion) | `testrunner.validation.mode=process`; `server` (default) keeps it running |
| `logging.consoleLevel` | `logging.level.at.hl7.testing.runner` (Spring Boot logging) |
| `logging.fileLevel` | `testrunner.reporting.log-file-level` |
| `logging.communicationLogs` | `testrunner.reporting.communication-log` |
| `logging.includeCommunicationInLog` | `logging.level.at.hl7.testing.runner.communication=DEBUG` |
| `url` (IG download) | a suite read from the IG's package: `suites[n].package-id`, see [Suites from FHIR packages](#suites-from-fhir-packages) |

## Using it as a library

```java
RunConfiguration configuration = new RunConfiguration(
        FhirServerConfig.of("http://localhost:8080/fhir"),
        List.of(SuiteConfig.of(Path.of("testdata/r4"), "R4")),
        null, new ExecutionConfig(4),
        ValidationConfig.enabled(Path.of("validator_cli.jar")).withMode(ValidatorMode.SERVER),
        null, null);                       // reporting and packages: defaults
ProfileValidator validator = ProfileValidators.create(configuration.validation());
try {
    RunResult result = TestRunners.create(configuration.http(), validator).run(configuration);
} finally {
    if (validator instanceof AutoCloseable closeable) {
        closeable.close();                 // stops the validator servers (server mode)
    }
}
```

`TestRunners.create` discovers the FHIR version modules on the classpath with `ServiceLoader`;
`ProfileValidators.create` picks the adapter for `validation.mode`.

## Extending the pre-defined Spring configuration

`TestRunnerAutoConfiguration` defines every bean with `@ConditionalOnMissingBean`, so an application replaces any
part by declaring its own bean, and adds to the lists by declaring more:

| Bean | Replace to … | Default |
|---|---|---|
| `RunConfiguration` | build the configuration in code or from another source | bound from `testrunner.*` |
| `HttpTransport` | add a proxy, mutual TLS, OAuth token refresh, a recorder | `JdkHttpTransport` |
| `ProfileValidator` | use another validator, e.g. in-process | `ValidatorCliProfileValidator` or `ValidatorServerProfileValidator` by `validation.mode` (if on classpath and enabled); Spring stops the servers when the context closes |
| `FhirVersionSupport` (any number) | override a version's mapping | modules found by `ServiceLoader` |
| `ReportWriter` (any number) | add a format, e.g. HTML; select it in `reporting.formats` | junit, summary, json, testreport |
| `RunListener` (any number) | react to run/TestScript events (notifications, metrics) | `RunLogFileListener` |
| `Clock` | fixed time in tests | system clock |
| `TestRunner` | wire everything yourself | — |

```java
@Configuration
class MyRunnerConfiguration {

    @Bean
    HttpTransport httpTransport(RunConfiguration configuration, TokenService tokens) {
        JdkHttpTransport delegate = new JdkHttpTransport(configuration.http());
        return request -> delegate.send(new FhirRequest(request.method(), request.uri(),
                request.headers().with("Authorization", "Bearer " + tokens.current()), request.body()));
    }

    @Bean
    ReportWriter htmlReport() {
        return new MyHtmlReportWriter();   // format() = "html", then: testrunner.reporting.formats=junit,html
    }
}
```

`TestRunnerAutoConfigurationTest` covers binding, replacing and adding beans.

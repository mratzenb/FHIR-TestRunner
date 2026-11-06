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
| `--check` | Only loads and checks the TestScripts, sends nothing (short for `--testrunner.mode=check`), see [Check mode](#check-mode) |
| `--list` | Prints what would run, sends and writes nothing (short for `--testrunner.mode=list`), see [Selecting what runs](#selecting-what-runs) |
| `--testscript=<glob>` | Runs only the TestScripts whose file name, `name` or `url` matches; repeatable, or comma-separated (adds to `testrunner.select.test-scripts`) |
| `--test=<glob>` | Runs only the tests whose name matches; setup and teardown always run (adds to `testrunner.select.tests`) |
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
    oauth2:                                # instead of a fixed Authorization header: a token per run, see below
      token-url: https://auth.example.org/token
      client-id: testrunner
      client-secret: ${OAUTH_CLIENT_SECRET}
      scope: system/*.rs                   # optional, space separated
      client-authentication: basic         # basic (default) | post

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
        base-url: http://localhost:8081/fhir   # with headers and oauth2 of its own, if needed
      parallel: true                       # false: with parallelism > 1, run this suite's TestScripts one after
                                           # the other before all others, see ADR-0008
    - package-id: hl7.at.fhir.core.r4#2.1.0  # instead of base-dir: a suite read from a FHIR package, see below;
                                           # also #2.1.x, #current, #current$<branch>, #dev

  http:
    connect-timeout: 10s
    request-timeout: 60s
    follow-redirects: true

  execution:
    parallelism: 1                         # TestScripts run at the same time, see ADR-0008
    rewrite-references: autocreate         # none | autocreate (default) | all – rewrite references in fixtures to
                                           # the ids the server assigned; runner-specific, see below and ADR-0012
    timeout:                               # the whole run, e.g. 30m; default none. See "Run control" below
    test-script-timeout:                   # each TestScript, e.g. 2m; default none
    fail-fast: false                       # true: after the first fail or error, start no further TestScript

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
                                           # | html (report.html, one page) | any format of an added
                                           # ReportWriter; the app sets all five
    communication-log: true                # one folder per request/response, see logging.md
    redact-headers: [Authorization, Proxy-Authorization, Cookie, Set-Cookie]
                                           # values masked in the communication log ([] = none)
    log-file: true                         # testrunner.log in the run folder
    log-file-level: DEBUG                  # TRACE | DEBUG | INFO | WARN | ERROR

  select:                                  # which TestScripts and tests of the suites run; default all
    test-scripts: []                       # globs on file name (with or without extension), TestScript.name or url
    tests: []                              # globs on test names; setup and teardown always run

  packages:                                # for suites with package-id
    cache-dir: ~/.fhir/packages            # default; the cache of the validator, IG Publisher and SUSHI
    registry: https://packages.fhir.org    # default; where missing released packages are downloaded from,
                                           # verified against its checksum (dist.shasum)
    ci-build: https://build.fhir.org       # default; where #current packages (CI builds of IGs) come from

  run-on-startup: true                     # app only: false to wire everything without running (tests, embedding)
  mode: run                                # app only: run (default) | check – load and check, send nothing (--check)
                                           # | list – print what would run (--list)
  exit-on-skip: all                        # app only: when skipped TestScripts give exit code 3 instead of 0:
                                           # all (default, nothing was tested) | any (a skip, no failure) | never
  shutdown-grace: 30s                      # app only: on Ctrl-C or kill, how long to wait for the run to tear
                                           # down and write its reports before writing them with what is known
```

### Rewriting references in fixtures (runner-specific)

`rewrite-references` is a convenience of this runner. The FHIR TestScript specification sends a fixture as written, so
a TestScript that relies on it may fail on another test engine.

| Value | References in fixture resources that are rewritten to the server's ids |
|---|---|
| `none` | none; autocreate fixtures are created in declaration order – the behaviour of the specification |
| `autocreate` (default) | references to `autocreate` fixtures, which are created in dependency order |
| `all` | also references to fixtures that a `create` or `update` action sent |

Run a suite with `--testrunner.execution.rewrite-references=none` to check that its TestScripts are portable. Details
and examples: [wiki › Fixtures](wiki/fixtures.md#references-to-fixtures-on-the-server),
[ADR-0012](adr/0012-reference-rewriting.md).

### Run control

Three limits keep a run from taking longer than it is worth, for example in CI. None is set by default.

| Setting | Once it has passed |
|---|---|
| `execution.timeout: 30m` | The running TestScripts stop before their next action; the others do not start. |
| `execution.test-script-timeout: 2m` | That TestScript stops before its next action. The other TestScripts are not affected. |
| `execution.fail-fast: true` | After the first TestScript with `fail` or `error`, no further TestScript starts; the running ones finish. |

The limits are checked **between actions**: a request that is running is never interrupted, so a limit can be
overrun by one `http.request-timeout`. A stopped TestScript is `error` ("Stopped before the next action: the
TestScript timeout of 2m has passed"); its remaining tests are `skip`, and its **teardown and autodelete still run**,
so the server is left clean. A TestScript that did not start is `skip` with the reason ("Not started: fail-fast:
TestScript 02_… ended with FAIL"). Why: [ADR-0013](adr/0013-run-control.md).

**Reports are always written** once a run has started – also after an unexpected exception, and on Ctrl-C or a
kill (SIGTERM). The app then stops the run as above and waits up to `testrunner.shutdown-grace` (default `30s`) for
the teardowns and the reports. If the run does not end in time, the reports are written with what is known: a
TestScript still running is `error` "Did not end: the run was aborted before it ended", one not started `skip`. In
CI, set `shutdown-grace` below the time the CI system waits between SIGTERM and SIGKILL. `kill -9` cannot be handled.

Embedded, a run is stopped from another thread with a `RunControl`:

```java
RunControl control = new RunControl();
// e.g. in a shutdown hook or a UI:
//   control.cancel("stopped by the user");
//   if (!control.awaitFinished(Duration.ofSeconds(30))) control.reportNow();
RunResult result = runner.run(configuration, control);
```

### Check mode

`--check` (`testrunner.mode=check`) does everything a run does before it sends the first request, and stops there:

* discovers and loads the TestScripts (version detection, fixtures from Example Instances or `contained`),
* applies every check before sending ([How a run works › Checks before sending](wiki/run-lifecycle.md#6-checks-before-sending)),
* validates each TestScript against its FHIR version if `validation.validate-test-scripts` is on (the default).

It needs no FHIR server, and the validator only for that validation: `--check --novalidator` needs neither.

```sh
java -jar testrunner-app/target/testrunner-app-0.1.0-SNAPSHOT-exec.jar --config=my-suite.yml --check
```

Every TestScript gets one phase, `Load`: `pass` with the warnings a run would show (e.g. an element the FHIR version
ignores, an assertion the runner cannot evaluate), or `error` listing every problem. The reports go to
`output-dir/check_<timestamp>/` – `junit.xml`, `summary.txt`, `result.json` – but no FHIR TestReports: nothing was
executed. Exit code 0 no problems, 1 problems, 2 configuration. In a library, call `TestRunner.check(configuration)`.
Why it is built this way: [ADR-0014](adr/0014-author-workflow.md).

### Selecting what runs

`select` narrows a run – and a check and a list – to some TestScripts and tests, without touching the suites:

```sh
java -jar testrunner-app/target/testrunner-app-0.1.0-SNAPSHOT-exec.jar --config=config/dev-r4.yml \
  --testscript='*patient-update*' --test='Update*'
```

| Setting | Short form | Matches |
|---|---|---|
| `select.test-scripts: ["*update*"]` | `--testscript='*update*'` | the file name (`TestScript-x.json` or `TestScript-x`), `TestScript.name` or `TestScript.url` |
| `select.tests: [UpdatePatient]` | `--test=UpdatePatient` | `TestScript.test.name` |

* Patterns are globs: `*` any characters, `?` one, case-insensitive, matching the whole text – `read` does not match
  `read-patient`, `read*` does. Several patterns: any of them. Quote them in a shell.
* With `select.tests`, a TestScript runs only if at least one of its tests is selected.
* **Setup and teardown always run.** Tests left out are not run and **not reported** – not as `skip` either. The log
  says how many were left out.
* A selected test that needs a response kept by a test left out is a `Load` error naming that test: select it too.
* `include` of a suite still applies first: the selection picks from what the suites hold.

`--list` shows what a run with the same arguments would run, and sends and writes nothing:

```
 1. r4: testdata/r4/Test_Scripts/TestScript-testscript-patient-update-at-core.json  (FHIR R4) TestScriptATCorePatientUpdate
      UpdatePatient
1 TestScript(s), 1 test(s) would run (selected: TestScripts [*patient-update*], tests [Update*])
```

A TestScript with tests left out says so (`– 1 of 4 tests`); one that cannot be read shows why. Exit code 0, or 1 if
a TestScript cannot be read. In a library: `TestRunner.list(configuration)`.

### Servers protected by OAuth2

A FHIR server that wants an OAuth2 access token needs no code. With `fhir-server.oauth2` the runner uses the client
credentials grant (RFC 6749, 4.4):

```yaml
testrunner:
  fhir-server:
    base-url: https://fhir.example.org/r4
    oauth2:
      token-url: https://auth.example.org/realms/fhir/protocol/openid-connect/token
      client-id: testrunner
      client-secret: ${OAUTH_CLIENT_SECRET}   # from the environment, never in a file under version control
      scope: system/*.rs system/*.cud
```

* Before the first request, the runner posts `grant_type=client_credentials` (and `scope`) to `token-url`. Client
  id and secret go as HTTP Basic authentication; `client-authentication: post` sends them as form parameters instead.
* Every request to the FHIR server gets `Authorization: Bearer <token>`.
* One token per run and client, shared by parallel TestScripts. It is renewed shortly before it expires
  (`expires_in`, at most 30 s early); a token without `expires_in` is used for the whole run.
* A TestScript that sets `Authorization` itself (`requestHeader`) keeps its value, e.g. to test a wrong token.
* A token request that fails is an `error` of the operation that needed it, with the endpoint's `error` and
  `error_description`: `REQ-0001 GET …/metadata: not sent (IOException: No OAuth2 access token from
  https://auth.example.org/…/token: HTTP 401 Unauthorized, invalid_client - …)`.
* The token request is not in `communication/`: it carries the client secret and is no exchange with the FHIR
  server. The token itself is masked there (`Authorization: Bearer ***`, see
  [logging.md](logging.md#sensitive-data)).
* `token-url`, `client-id` and `client-secret` are required; `oauth2` together with a `headers.Authorization` is a
  configuration problem (exit code 2). A suite with a `fhir-server` of its own uses its own `oauth2`.

For other schemes (mutual TLS, a token from a file that changes), replace the `HttpTransport`, see
[Extending the pre-defined Spring configuration](#extending-the-pre-defined-spring-configuration).

### Suites from FHIR packages

An IG is published as a FHIR package; the IG Publisher puts the TestScripts into it (`package/TestScript-*.json`)
and the examples into its example folder. A suite with `package-id` instead of `base-dir` runs them from there:

```yaml
testrunner:
  suites:
    - package-id: hl7.at.fhir.core.r4#2.1.0   # name#version
      include: ["TestScript-*-create-*.json"] # optional, default all TestScript-*.json|xml of the package
```

Before the run (`PackageSuites`), the package is looked up in the package cache (`packages.cache-dir`, as
`<id>#<version>/package/`), where the validator finds it too. The version says where a missing one comes from:

| Version | Package | When it is not in the cache |
|---|---|---|
| `2.1.0` | that release | downloaded from `packages.registry` and verified against the SHA-1 the registry lists for it |
| `2.1.x` | the highest patch release of 2.1 | the highest one in the cache, else the highest the registry lists |
| `current` | the latest CI build of the IG | from `packages.ci-build`: the repository with the most recent build in `ig/qas.json`, as the validator finds it |
| `current$<branch>` | the latest CI build of a branch | `<ci-build>/ig/<org>/<repo>/branches/<branch>/` |
| `dev` | built locally by the IG Publisher | never downloaded |

A `#current` package in the cache is used while it is the latest build (the `date` of its `package.json` equals the
build's `package.manifest.json`); a newer build replaces it. If the CI server cannot be reached, the cached build is
used with a warning. CI builds have no checksum: the log says that such a download is not verified.

From the package's `package.json` the suite takes

| Suite setting | From the package |
|---|---|
| TestScripts | `package/`, the files matching `include` (default `TestScript-*.json`, `TestScript-*.xml`) |
| Example Instances (fixtures, required CapabilityStatements) | `directories.example` (`example/` or `examples/`) |
| `fhir-version` | `fhirVersions`, unless configured |
| `igs` | the package itself, every package it depends on without the core package, then the configured `igs` |
| `name` | the package id, unless configured |

With profile validation on, the dependencies are resolved like the package itself – transitively, each once, verified
– and passed to the validator with the version found (`hl7.terminology.r4#7.4.x` → `#7.4.2`). So the validator finds
every package in the cache and downloads nothing unverified. Without validation, and for `--list`, only the suite's
own package is resolved.

A package that cannot be found, downloaded, verified or read stops the run before anything is sent (exit code 2),
like any other configuration problem; for a dependency the message names the package that needs it. The cache can be
shared by several runs and the validator at once: a download is unpacked into a folder of its own and moved into
place, and a package another process put there meanwhile is used. Archives with long file names (GNU or pax headers,
as npm writes them) are read completely.

The downloads use one HTTP client per run, with the timeouts of `testrunner.http` and the JVM's proxy and TLS
settings, the same as for the FHIR server: `-Dhttps.proxyHost=… -Dhttps.proxyPort=…`, `-Dhttp.nonProxyHosts=…`,
`-Djavax.net.ssl.trustStore=…` (in the container image: the company certificates, see
[ADR-0015](adr/0015-container-image.md)). Details: [ADR-0018](adr/0018-package-resolution.md).

Relaxed binding applies: `base-dir`, `baseDir` and `BASEDIR` are the same; durations accept `90s`, `2m`, `PT1M`.

### Mapping from the PoC's `config.json`

| PoC | Here |
|---|---|
| `fhirServer` | `testrunner.fhir-server.base-url` |
| `path` | `testrunner.suites[n].base-dir` |
| `testscripts: [...]` | `testrunner.suites[n].include` (globs, relative to `Test_Scripts/`); for one run also `--testscript` |
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
        null, ExecutionConfig.parallel(4),
        ValidationConfig.enabled(Path.of("validator_cli.jar")).withMode(ValidatorMode.SERVER),
        null, null, null);                 // reporting, packages, select: defaults
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
| `HttpTransport` | add a proxy, mutual TLS, a token scheme other than `fhir-server.oauth2`, a recorder | `JdkHttpTransport` |
| `ProfileValidator` | use another validator, e.g. in-process | `ValidatorCliProfileValidator` or `ValidatorServerProfileValidator` by `validation.mode` (if on classpath and enabled); Spring stops the servers when the context closes |
| `FhirVersionSupport` (any number) | override a version's mapping | modules found by `ServiceLoader` |
| `ReportWriter` (any number) | add a format, e.g. CSV; select it in `reporting.formats` | junit, summary, json, testreport, html |
| `RunListener` (any number) | react to run/TestScript events (notifications, metrics) | `RunLogFileListener` |
| `Clock` | fixed time in tests | system clock |
| `TestRunner` | wire everything yourself | — |

```java
@Configuration
class MyRunnerConfiguration {

    @Bean
    HttpTransport httpTransport(RunConfiguration configuration, TokenService tokens) {
        JdkHttpTransport delegate = new JdkHttpTransport(configuration.http());
        return request -> delegate.send(request.withHeaders(
                request.headers().with("Authorization", "Bearer " + tokens.current())));
    }

    @Bean
    ReportWriter csvReport() {
        return new CsvReportWriter();      // format() = "csv", then: testrunner.reporting.formats=junit,csv
    }
}
```

`TestRunnerAutoConfigurationTest` covers binding, replacing and adding beans.

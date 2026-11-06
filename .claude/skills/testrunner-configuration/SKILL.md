---
name: testrunner-configuration
description: Configuration of the FHIR TestScript Runner (TestRunnerOnJava) – every testrunner.* property with its default, the immutable RunConfiguration record tree, how Spring Boot binds it, the dev configs, using the runner as a library, and replacing or adding beans of the Spring auto-configuration. Use when adding or changing a setting, writing a YAML configuration, wiring the runner in code or another Spring app, or touching testrunner-spring-boot-autoconfigure / testrunner-app.
---

# Configuration (ADR-0002, ADR-0003, `docs/configuration.md`)

`core.config.RunConfiguration` – a tree of immutable records – is the **only** input of `TestRunner.run`:
`fhirServer: FhirServerConfig`, `suites: List<SuiteConfig>`, `http: HttpConfig`, `execution: ExecutionConfig`,
`validation: ValidationConfig` (+ `ServerModeConfig server`, `ValidatorMode mode`), `reporting: ReportingConfig`,
`packages: PackagesConfig`, `select: SelectConfig` (8 components; `withSuites`, `withSelect`).
Defaults are applied in the **canonical constructors** (null ⇒ default; `@Nullable` parameters, `@Nonnull`
components, ADR-0017), so binding, code and tests share them. `FhirServerConfig.base()` is an `Optional<String>`.
`problems()` lists everything that blocks a run, all at once (exit code 2).

## Properties (prefix `testrunner`, relaxed binding, durations `90s`/`2m`/`PT1M`)

```yaml
testrunner:
  fhir-server: {base-url: , headers: {}}     # no base-url ⇒ every TestScript skipped
    oauth2: {token-url: , client-id: , client-secret: , scope: , client-authentication: basic}
                                             # OAuth2Config: client credentials grant; core http.AccessTokens (one per
                                             # config per run in RunContext, renewed ≤30 s before expires_in);
                                             # FhirHttpClient adds Bearer unless the request sets Authorization;
                                             # problems(): 3 required fields, not with headers.Authorization
  suites:                                    # at least one required
    - name:                                  # default: folder name of base-dir
      base-dir:                              # required
      fhir-version: AUTO                     # AUTO | R4 | R5 | R6 | 4.0 | 5.0.0 …
      test-scripts-dir: Test_Scripts
      example-instances-dir: Example_Instances
      profiles-dir: Profiles                 # passed to the validator as -ig
      igs: []                                # further -ig of this suite (package ids, folders, files); needs a
                                             # pinned fhir-version (problems()), see testrunner-validation
      include: ["*.json", "*.xml"]           # globs relative to test-scripts-dir
      fhir-server: {base-url: }              # optional own server per suite (own headers/oauth2)
      parallel: true                         # false: TestScripts one after the other, before the parallel ones
      package-id:                            # instead of base-dir: name#version of a FHIR package (PackageSuites);
                                             # a pinned fhir-version is checked by problems() as for folders
  http: {connect-timeout: 10s, request-timeout: 60s, follow-redirects: true}
  execution: {parallelism: 1, rewrite-references: autocreate}   # parallelism ≥ 1; rewrite-references none |
                                             # autocreate | all (ReferenceRewriting, runner-specific, ADR-0012)
    timeout:                                 # Duration, whole run; default null = none (ADR-0013, RunControl)
    test-script-timeout:                     # Duration, each TestScript; default none; both must be > 0 (problems())
    fail-fast: false                         # after the first fail/error no further TestScript starts
  validation:
    enabled: true
    mode: server                             # server (default) | process
    validator-jar:                           # required when enabled (the app's application.yml: validator_cli.jar)
    java-executable:                         # default: the runner's java
    jvm-arguments: []
    terminology-server: n/a
    locale: en
    timeout: 5m
    validate-test-scripts: true              # (validation.igs moved to suites[n].igs; the old key fails the start)
    extra-arguments: []
    server: {startup-timeout: 5m, fallback-to-process: true}
  reporting:
    output-dir: testrunner-results           # run folder output-dir/run_<timestamp>/
    formats: [junit, summary]                # record default; also json, testreport, html (report.html);
                                             # the app's application.yml sets all five
    communication-log: true
    redact-headers: [Authorization, Proxy-Authorization, Cookie, Set-Cookie]   # masked in communication log; [] = none
    log-file: true
    log-file-level: DEBUG                    # TRACE|DEBUG|INFO|WARN|ERROR
  packages: {cache-dir: ~/.fhir/packages, registry: https://packages.fhir.org}   # PackagesConfig
  select:                                    # SelectConfig, default all (ADR-0014); engine: core.engine.Selection
    test-scripts: []                         # globs (* ? case-insensitive, whole text) on file name ± extension,
                                             # TestScript.name or url; empty entry ⇒ problems()
    tests: []                                # globs on test names; setup/teardown always run; deselected tests are
                                             # left out (not skip), a TestScript without a selected test too
  run-on-startup: true                       # app only (RunCommand); false = wire without running
  mode: run                                  # app only (RunCommand.Mode, Binder): run | check (--check: load +
                                             # check, send nothing, TestRunner.check, ADR-0014) | list (--list:
                                             # TestRunner.list, printed to stdout by RunCommand.list, writes nothing)
  exit-on-skip: all                          # app only (RunCommand.ExitOnSkip, bound via Binder): all | any | never
  shutdown-grace: 30s                        # app only (RunCommand, Binder): wait on Ctrl-C/SIGTERM before reportNow
```

Sources, highest first: command line → system properties → env vars (`TESTRUNNER_FHIRSERVER_BASEURL`) → `--config`
file → `testrunner-app/src/main/resources/application.yml`. CLI shortcuts (`TestRunnerApplication`):
`--config=<file>` = `--spring.config.additional-location=file:<file>`, `--novalidator` =
`--testrunner.validation.enabled=false`, `--check` = `--testrunner.mode=check`, `--list` = `--testrunner.mode=list`,
`--testscript=<glob>` / `--test=<glob>` (repeatable or comma-separated; `--opt value` too) =
`--testrunner.select.test-scripts[i]=…` / `--testrunner.select.tests[i]=…` (indexed, collected over all options). Console logging is ordinary Spring Boot (`logging.level.*`).

Dev configs in `config/`: `dev-r4.yml`, `dev-r5.yml`, `dev-all.yml` (samples + r4 + r5, parallelism 4, R5 on
`:8081`), `negative-control.yml` (expected FAIL). All take `validator-jar: ${VALIDATOR_JAR:validator_cli.jar}`,
`mode: server`, `output-dir: target/testrunner-results`.

## Adding a setting

1. Record component in the right `*Config` record, as a **boxed** type (`Integer`, `Boolean`, `Duration`, …) so that
   absent means null, + default in its canonical constructor (null ⇒ default). The config records have an explicit
   canonical constructor (ADR-0017): the parameter is `@Nullable`, the component `@Nonnull` if it has a default
   (`@Nullable` if "not set" stays null, like `execution.timeout`), plus `this.x = x;` at its end.
2. If it can be wrong: a check in `RunConfiguration.problems()` naming the property path (`testrunner.x.y`).
3. Use it where needed – components get their part of the configuration, never a global.
4. Binding works automatically (Binder, relaxed names). Test it in `TestRunnerAutoConfigurationTest`
   (`bindsTheExternalizedProperties`) and the default in `RunConfigurationTest`.
5. Document in `docs/configuration.md` (reference YAML; PoC mapping table if relevant) and in this skill.

Constructors with all components are positional – adding a component changes every `new XxxConfig(...)` call
(tests, `Harness`, docs examples); prefer factories like `ValidationConfig.enabled(jar)`, `withMode`, `withServer`,
`SuiteConfig.of`, `FhirServerConfig.of`, `HttpConfig.defaults()`.

## Spring wiring (`testrunner-spring-boot-autoconfigure`)

`TestRunnerAutoConfiguration` binds with `Binder.get(env).bind("testrunner", RunConfiguration.class)` (no
`@ConfigurationProperties`, the record stays framework-free) and defines every bean `@ConditionalOnMissingBean`.
`ValidatorCliAutoConfiguration` (before it, `@ConditionalOnClass(ProfileValidators)`) provides the `ProfileValidator`;
Spring closes it (stops validator servers). Registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

| Bean | Default | Replace / add to … |
|---|---|---|
| `RunConfiguration` | bound from `testrunner.*` | build it in code |
| `HttpTransport` | `JdkHttpTransport` (closed with context) | proxy, mTLS, token schemes beyond `oauth2` |
| `ProfileValidator` | by `validation.mode` | another validator (e.g. in-process) |
| `FhirVersionSupport` (list) | `ServiceLoader` modules | override a version |
| `ReportWriter` (list) | junit, summary, json, testreport, html | add a format; select via `reporting.formats` |
| `RunListener` (list) | `RunLogFileListener` (only with Logback) | events, metrics; gets `RunInfo` |
| `Clock` | system | fixed time in tests |
| `TestRunner` | wired from the above | wire yourself |

`testrunner-app`: `TestRunnerApplication` (main, argument shortcuts, `web-application-type: none`), `RunCommand`
(`ApplicationRunner` + `ExitCodeGenerator` + `ApplicationListener<ContextClosedEvent>`, conditional on
`run-on-startup`; `testrunner.mode` run / check / list – `@Autowired` public constructor, a package-private one takes
the `PrintStream` for `--list`; runs with a `RunControl` and on context close – Ctrl-C/SIGTERM, before beans are
destroyed – `stop(control, shutdownGrace)`: cancel, `awaitFinished`, else `reportNow`), `ValidatorCheck` (start-up
warning).

## Library use (no Spring)

```java
RunConfiguration configuration = new RunConfiguration(FhirServerConfig.of("http://localhost:8080/fhir"),
        List.of(SuiteConfig.of(Path.of("testdata/r4"), "R4")), null, ExecutionConfig.parallel(4),
        ValidationConfig.enabled(Path.of("validator_cli.jar")).withMode(ValidatorMode.SERVER), null, null, null);
ProfileValidator validator = ProfileValidators.create(configuration.validation());
try {
    RunResult result = TestRunners.create(configuration.http(), validator).run(configuration);
} finally {
    if (validator instanceof AutoCloseable closeable) closeable.close();   // stops validator servers
}
```

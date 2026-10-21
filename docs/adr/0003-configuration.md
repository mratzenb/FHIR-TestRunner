# ADR-0003: One immutable configuration object, externalized via Spring Boot binding

## Context
The PoC reads `config.json` into a global `ConfigManager` queried from everywhere; defaults and validation are
spread over the code. The brief: the configuration shall be externalizable, development configurations may live in
the project, and the JAR takes "a configuration object as input".

## Decision
* `RunConfiguration` – a tree of Java records (`FhirServerConfig`, `SuiteConfig`, `HttpConfig`, `ExecutionConfig`,
  `ValidationConfig`, `ReportingConfig`) – is the *only* input of `TestRunner.run`. Defaults are applied in the
  compact constructors, so binding, code and tests get the same defaults.
* `RunConfiguration.problems()` returns all problems at once; the runner refuses to start with any (exit code 2).
* Externalization uses Spring Boot's standard sources; `--config=<file>` is a shortcut. Dev configs live in
  `config/`, the jar's `application.yml` holds only defaults.
* **Suites** replace the PoC's single `path`: a run can mix IGs, FHIR versions and servers.

## Alternatives
* *Keep `config.json`* – would need its own loader, no env var/profile support. A converter from the old format is
  easy to add if needed (mapping table in configuration.md).
* *Mutable POJO with setters* – the common Spring style, but shared mutable configuration is exactly what made the
  PoC hard to reason about, and dangerous with parallelism.

## Consequences
* Nothing reads configuration from a global place; each component gets the part it needs.
* Adding a setting = a record component + a default + (if needed) a check in `problems()`.

# Architecture Decision Records

Format: context, decision, alternatives considered, consequences. Status of all: *accepted* (proof of concept, 2026-10).

| # | Decision |
|---|---|
| [0001](0001-hexagonal-modules.md) | Hexagonal core with adapters, split into Maven modules |
| [0002](0002-spring-boot-at-the-edge.md) | Spring Boot 4 only at the edge; framework-free core |
| [0003](0003-configuration.md) | One immutable configuration object, externalized via Spring Boot binding |
| [0004](0004-no-global-state.md) | No global state: run context and execution state are passed explicitly |
| [0005](0005-fhir-version-mapping.md) | Version-neutral model, one generic HAPI mapper, a dialect per FHIR version |
| [0006](0006-validator-cli.md) | Profile validation with the HL7 Validator CLI as an external process (extended by 0011) |
| [0007](0007-logging.md) | SLF4J + MDC, a log file and a communication folder per run, run-wide request ids |
| [0008](0008-parallel-execution.md) | Parallelism across TestScripts, never within one |
| [0009](0009-testing.md) | Tests without FHIR server, network or real validator |
| [0010](0010-build.md) | Maven build: BOMs, version properties, enforcer, exec jar |
| [0011](0011-validator-server-mode.md) | Validator server mode with fallback to one process per validation (default since 2026-10) |
| [0012](0012-reference-rewriting.md) | Rewriting references to fixtures created on the server: a configurable convenience of this runner |
| [0013](0013-run-control.md) | Run control: timeouts, fail-fast and cancel, checked between actions, never a killed thread; reports always written, also on Ctrl-C |
| [0014](0014-author-workflow.md) | Author workflow: a check mode that loads and checks TestScripts without a server and sends nothing; selecting TestScripts and tests, and listing what would run |
| [0015](0015-container-image.md) | Container image: the runner on a Java 25 JRE, everything else mounted; a compose file with HAPI FHIR; built in CI, not published |
| [0016](0016-typed-comparisons.md) | Assertions compare values by their FHIRPath type: numbers by value, dates and times as points in time; what cannot be compared fails with the reason |
| [0017](0017-nullness.md) | The public API says where null is allowed: jakarta `@Nonnull`/`@Nullable` on every reference-typed position, `Optional` for absent return values, checked by `ArchitectureTest` |
| [0018](0018-package-resolution.md) | Package resolution: `#current`, `2.1.x` and dependencies as the HL7 validator reads them, resolved transitively into the shared cache, every released package verified against the registry's checksum; one closed HTTP client with the JVM's proxy and TLS settings |
| [0019](0019-several-servers.md) | Several servers: each `TestScript.destination` index mapped to a server of the suite (`suites[n].fhir-servers`), every index required, no fallback; fixtures created on each server, capabilities checked per destination |

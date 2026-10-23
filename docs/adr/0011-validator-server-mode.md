# ADR-0011: Optional validator server mode

Builds on [ADR-0006](0006-validator-cli.md).

## Context
With one validator process per validation, almost all of the time goes into loading the FHIR core packages and IGs
(5–15 s per `validateProfileId` and per TestScript check). The HL7 Validator CLI can run as an HTTP server
(`validator_cli.jar server <port> ...`, endpoint `POST /validateResource?profile=...`, `POST /stop`) that loads
them once. The server mode is newer and less used than the CLI, so it must be possible to switch it off and to
survive its failures.

## Decision
* `testrunner.validation.mode`: `process` (default, ADR-0006) or `server`. Same jar, same `-version`, `-tx`,
  `-locale`, `-ig`, extra arguments (built by one class, `ValidatorArguments`), same OperationOutcome parsing.
* `ValidatorServerProfileValidator` keeps one `ValidatorServer` per **FHIR version, profiles folder and IGs**
  (the suite's `igs`) – the settings a server is started with. Servers start lazily on first use, bind to `127.0.0.1` on a free port, are
  ready when the port accepts connections, and are stopped (`/stop`, then process termination) when the application
  closes; a JVM shutdown hook covers abnormal ends.
* Failure handling, because "unforeseen problems" are expected:

  | Problem | Behaviour |
  |---|---|
  | Server does not start (bad jar, wrong Java, port taken twice, `server.startup-timeout` exceeded) | Server marked failed |
  | Server process dies while running | Server marked failed |
  | A failed server | Not restarted. With `server.fallback-to-process=true` (default) its validations run as one process each, one warning per server; with `false` they are `error`s whose message names both switches |
  | One validation fails on a running server (HTTP 500, e.g. unknown profile) | `error` for that assertion, no fallback (the process mode would fail the same way) |
  | Validation exceeds `validation.timeout` | `error` for that assertion |

  Never a silent pass: every path ends in an OperationOutcome that was actually returned, or in an error.
* `testrunner.validation.jvm-arguments` (both modes) sets e.g. `-Xmx` for the validator JVM.
* The check of a TestScript itself (base specification, no profile) passes the suite's profiles folder and IGs
  although it needs none of them, so that it lands on the same server as the `validateProfileId` assertions of that suite instead
  of starting a second one.
* The default stays `process`. The development configurations in `config/` opt in to `server`, so the mode gets
  used before it becomes the default.

## Alternatives
* *Server as default* – 2–6× faster on the AT Core suite (more on larger suites), but newer and with a larger blast radius
  (one process for all validations). Kept opt-in until it has proven itself; flipping the default is a one-line
  change.
* *Restart a crashed server* – risks a crash loop on an input that kills it each time; the process fallback isolates
  such inputs instead.
* *One server per run* – would need to restart servers per run; servers per application (Spring singleton) reuse
  the loaded packages across runs.
* *A single server for all versions/folders* – the validator binds version and IGs at start; requests cannot change
  them.

## Consequences
* Measured on the AT Core R4 suite (3 TestScripts, 6 validations: 3 TestScript checks + 3 `validateProfileId`),
  Java 25, Apple M-series, warm package cache, same results (2 pass, negative control fails) in every cell:

  | Validator | parallelism | process | server |
  |---|---|---|---|
  | 7.0.2 | 1 | 35.2 s | 6.2 s |
  | 7.0.2 | 3 | 18.1 s | 6.6 s |
  | 6.10.4 | 1 | 46.9 s | 8.7 s |
  | 6.10.4 | 3 | 17.1 s | 7.9 s |

  The server time is almost entirely its single start-up (5–7 s); a validation then takes 0.1–0.5 s. The gain
  grows with the number of validations: process mode pays the start-up per validation, server mode once.
* A server uses ≈ 1 GB per FHIR version × profiles folder × IGs (in practice per suite) while the application runs.
* The validator server handles requests one at a time; parallel TestScripts queue on it, which is still far faster
  than parallel processes (see the table: parallelism no longer shortens validation, ADR-0008).
* **Known limitation – a hanging server is not detected as failed.** A validation that exceeds
  `validation.timeout` is an `error`, but the server process is still alive, so it is not marked failed and there is
  no fallback; because the server works sequentially, the validations queued behind a hanging one time out too. With
  the default timeout of 5 minutes this is slow to surface. Mitigations today: a shorter `validation.timeout` in
  server mode (validations take well under a second once the server is up), or `mode=process`. A possible
  improvement: treat a timeout in server mode as a server failure (end the process, fall back).

## Reverting
`--testrunner.validation.mode=process` (or `TESTRUNNER_VALIDATION_MODE=process`) – no rebuild. Nothing else depends
on the mode: results, reports and log lines per validation are the same in both modes.

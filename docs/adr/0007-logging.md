# ADR-0007: SLF4J + MDC, a log file and a communication folder per run, run-wide request ids

## Context
The latest PoC commits introduced detailed run logging and communication logging; the brief calls them crucial.
The PoC implements them with global handlers and module state in `log_setup.py`/`communication_logger.py`.

## Decision
* Logging API: SLF4J only, loggers per class under `at.hl7.testing.runner`. The backend (Logback) is configured by
  Spring Boot; the core does not configure logging.
* MDC `testScript=<NN>_<name>` while a TestScript runs, printed in every pattern, so parallel output stays readable.
* `RunLogFileListener` (a `RunListener`) adds a file appender per run in the run folder, at its own level.
* `FhirHttpClient` assigns run-wide request ids and logs one line per request; `CommunicationLog` writes each
  exchange to `communication/<NN>_<TestScript>/<REQ-id>_<METHOD>_<path>/` and to the logger
  `…runner.communication` at DEBUG, as exchanged except for the values of credential headers
  (`reporting.redact-headers`, default `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`), which are
  masked so the folder can be shared with a bug report.
* Every assertion result carries the request id it checked; reports carry it too.

## Alternatives
* *A logger per run instance passed around* – would avoid MDC but pollute every signature.
* *One log file per TestScript* – nice with parallelism, but a run log is what people read first; the MDC
  prefix plus `grep` gives the per-TestScript view.

## Consequences
* The run folder is self-contained: log, reports and exchanges with matching ids.
* The run log file needs Logback; with another backend the console log and communication folder still work.

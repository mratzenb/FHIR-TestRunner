# ADR-0004: No global state

## Context
The PoC keeps `FIXTURES`, `REQ_RESP`, `VARIABLES`, `PROFILES`, `last_interaction`, `FHIR_SERVER_BASE`, the config
manager, the result tracker and the communication logger's request counter and folders in module-level globals,
cleared in `finally` blocks. Tests must reset them (`conftest.clean_state`), and parallel execution is impossible.

## Decision
State has an owner with a clear lifetime:

| State | Owner | Lifetime |
|---|---|---|
| Run id, run folder, request counter, configuration | `RunContext` (record) | one run |
| Fixtures, kept responses, last response | `ExecutionState` | one TestScript execution, one thread |
| Communication folder of a TestScript | `CommunicationLog` | one TestScript execution |
| Results | immutable `PhaseResult`/`TestScriptResult`/`RunResult`, returned, not tracked | — |

`TestRunner` and all adapters are stateless (or thread-safe and immutable) and can serve many runs. The only static
fields are constants and loggers. The SLF4J MDC is set
and cleared per TestScript by the thread that runs it.

## Consequences
* Parallel TestScripts need no locking beyond the `AtomicInteger` request counter.
* Tests need no reset logic; each test builds what it uses.
* Results are values: report writers and listeners cannot corrupt them.

# ADR-0008: Parallelism across TestScripts, never within one

## Context
The brief asks to consider parallel execution "if it is configured". A TestScript is a sequence: later actions
depend on earlier responses, variables and fixtures.

## Decision
* `testrunner.execution.parallelism` (default **1**) is the number of TestScripts run concurrently, on a fixed pool of
  platform threads (`testscript-N`). The actions of one TestScript always run in order on one thread.
* Isolation comes from ADR-0004: each execution owns its state; shared are only the HTTP transport (thread-safe JDK
  `HttpClient`), the request counter (`AtomicInteger`), the validator and the report list. The validator is
  thread-safe in both modes: in process mode every validation has its own process and temp folder; in server mode
  parallel validations go to the same server (ADR-0011).
* Results keep their discovery index and are reported in that order, regardless of completion order.

## Alternatives
* *Virtual threads* – work equally well for the HTTP part; a bounded pool is still needed so a large suite does not
  start dozens of validator processes (≈ 1 GB each) at once. Platform threads make the bound obvious.
* *Parallel tests within a TestScript* – violates the TestScript execution model (tests share setup state).

## Consequences
* TestScripts that touch the same server data can interfere when run in parallel (e.g. two scripts PUT the same
  `Patient/HL7ATCorePatientUpdateTestExample`, or a conditional delete removes another script's resource). That is
  why the default is 1. Next step if needed: per-suite `parallel: false` or serial groups.
* With profile validation the validator mode decides what parallelism buys (measured on the AT Core R4 suite,
  validator 7.0.2, see ADR-0011):

  | | parallelism 1 | parallelism 3 |
  |---|---|---|
  | process mode | 35.2 s | 18.1 s – faster, but up to 3 validator JVMs at once |
  | server mode | 6.2 s | 6.6 s – no gain: the validator server answers one request at a time |

  In process mode, validator memory (≈ 1 GB per running validation) sets the practical limit. In server mode,
  parallelism still overlaps the HTTP traffic with the FHIR server, but validations queue on the server.

# ADR-0008: Parallelism across TestScripts, never within one

## Context
The brief asks to consider parallel execution "if it is configured". A TestScript is a sequence: later actions
depend on earlier responses, variables and fixtures.

## Decision
* `testrunner.execution.parallelism` (default **1**) is the number of TestScripts run concurrently, on a fixed pool of
  platform threads (`testscript-N`). The actions of one TestScript always run in order on one thread.
* Isolation comes from ADR-0004: each execution owns its state; shared are only the HTTP transport (thread-safe JDK
  `HttpClient`), the request counter (`AtomicInteger`), the validator (own process and temp folder per call) and
  the report list.
* Results keep their discovery index and are reported in that order, regardless of completion order.

## Alternatives
* *Virtual threads* – work equally well for the HTTP part; a bounded pool is still needed so a large suite does not
  start dozens of validator processes (≈ 1 GB each) at once. Platform threads make the bound obvious.
* *Parallel tests within a TestScript* – violates the TestScript execution model (tests share setup state).

## Consequences
* TestScripts that touch the same server data can interfere when run in parallel (e.g. two scripts PUT the same
  `Patient/HL7ATCorePatientUpdateTestExample`, or a conditional delete removes another script's resource). That is
  why the default is 1. Next step if needed: per-suite `parallel: false` or serial groups.
* With profile validation, parallelism mostly overlaps validator start-ups: the AT Core R4 suite takes 35.2 s with
  parallelism 1 and 18.1 s with 3 (validator 7.0.2), at the price of up to 3 validator JVMs at once. Validator
  memory (≈ 1 GB per running validation) sets the practical limit.

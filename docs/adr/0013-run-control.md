# ADR-0013: Run control – limits checked between actions, never a killed thread

## Context
A run could not be bounded or stopped (finding A2 of [next-steps](../wiki/next-steps.md#a2--run-control)). A server
that answers slowly held the run for `request-timeout` × requests; a CI job that is killed on its own timeout left no
`junit.xml`; a run whose first TestScript showed the server was down still ran every other TestScript.

Three things must hold whatever stops a run:

* **The server is left clean.** A TestScript that created fixtures runs its teardown and autodelete.
* **Nothing looks green that did not run.** What did not run is reported, with the reason.
* **No global state** ([ADR-0004](0004-no-global-state.md)): the state of the run that stops it belongs to the run.

## Decision
* Three settings in `testrunner.execution`, with defaults that keep the behaviour so far:

  | Setting | Default | When it applies |
  |---|---|---|
  | `timeout` | none | the whole run |
  | `test-script-timeout` | none | each TestScript, from its start |
  | `fail-fast` | `false` | after the first TestScript that ended with `fail` or `error` |

* **Checked between actions, nothing is interrupted.** A request that is running finishes (it has its own
  `http.request-timeout`). Killing a thread would leave fixtures on the server and the communication log half
  written. So a limit can be overrun by one request.
* **What a limit does:**

  | Limit | The running TestScript | TestScripts not started |
  |---|---|---|
  | `test-script-timeout` | phase `error` "Stopped before the next action: …", remaining tests `skip`, teardown and autodelete run | not affected |
  | `timeout` or a cancel (`RunControl.cancel`) | same | `skip`, phase `Load` "Not started: …" |
  | `fail-fast` | finishes | `skip`, phase `Load` "Not started: fail-fast: …" |

  A stopped TestScript is `error`, not `skip`: it ran partly, and a timeout says something about the server or
  the TestScript that someone has to look at.
* **`RunControl`**, one per run, holds the reason a run stops; `TestRunner.run(configuration, control)` takes it,
  `run(configuration)` creates one. It is the only way to stop a run from outside, e.g. from a shutdown hook. A
  `Deadline` per TestScript execution combines it with the TestScript timeout and is asked before every action of the
  setup and the tests; the teardown is never stopped.

## Alternatives
* *`Future.cancel(true)` / interrupting the thread* – stops inside a request or between the create and the delete of a
  fixture; the server keeps the data, the communication log is cut off.
* *A per-request deadline instead of between actions* – needs the HTTP client to give up; `request-timeout` already
  does that per request.
* *Report timed-out TestScripts as `skip`* – hides that the server or the TestScript is too slow.
* *Fail-fast that also stops the running TestScripts* – leaves no complete result for any of them; the first failure
  is what the user wants to see, the others are short anyway.

## Consequences
* A limit can be overrun by up to one `http.request-timeout` plus the teardown.
* With `parallelism > 1`, fail-fast lets the TestScripts that are running finish: more than one failure may be reported.
* The run summary and the reports name why a run ended early; the exit code follows the outcomes as before (1 for an
  `error`, 3 if everything was skipped).
* `RunControl` is part of the public API of the core.

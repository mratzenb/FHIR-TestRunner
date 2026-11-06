# ADR-0019: Several servers – each `TestScript.destination` index mapped to a configured server

## Context
A TestScript can address several servers: `TestScript.destination` declares them by index (1, 2, …) and a profile
code (`FHIR-Server`, `FHIR-SDC-FormManager`, …), and `operation.destination` says which one receives a request. The
runner had one client per TestScript, for one server, and rejected such TestScripts with a `Load` error: sending
every request to one server would test something else (feature F8 of [next steps](../wiki/next-steps.md)).
`TestScript.origin` names the clients that send the requests; the runner plays all of them itself.

The specification leaves the binding of an index to a real server to the test engine ("Test engines could then use
the destination-profile mapping to offer a filtered list of test systems"). It says that autocreated fixtures are
created, and autodeleted, "on each server being tested", and that `metadata.capability.destination` names the server
a capability applies to.

## Decision
* **Configuration: `suites[n].fhir-servers`, by destination index.** A map from index to a server with the settings
  of `fhir-server` (`base-url`, `headers`, `oauth2`):

  ```yaml
  suites:
    - base-dir: testdata/two-servers
      fhir-servers:
        1: {base-url: http://localhost:8080/fhir}
        2: {base-url: http://localhost:8081/fhir}
  ```

  By index, not by profile code: two destinations of one TestScript may have the same code (the specification's own
  multi-system example declares two `FHIR-Server`s), and `FHIR-Server` is what a missing code means. Within a suite –
  one IG – the indexes name the same systems. Per suite, like `fhir-server`, since the indexes belong to the
  TestScripts of an IG.
* **One server or several, decided by the TestScript.** A TestScript addresses several servers when it declares more
  than one destination or its operations use more than one index; otherwise one, as before:
  * **One server:** every request goes to `fhir-servers.<index>` if the index (used, else declared) is mapped there,
    else to the suite's `fhir-server`, else to the run's. Nothing changes for the TestScripts that ran before; a
    single undeclared `destination: 1` is still fine.
  * **Several servers:** the servers are the declared and the used indexes. Each must be mapped in `fhir-servers`;
    otherwise the TestScript is a `Load` error naming each missing property, and nothing is sent. The suite's and the
    run's `fhir-server` are not used: a fallback would quietly send two destinations to one server again.
* **Rules of the TestScript, checked before sending** (`TestScriptChecks`, also by `--check`): with several servers,
  every operation names its `destination` (the specification defines no default), every `operation.destination` and
  `metadata.capability.destination` is a declared destination if any are declared, and no index is declared twice.
  Whether the indexes are mapped is checked only when a run is about to send – `--check` needs no server.
* **One client per server** (`ExecutionFactory`): each with the headers and OAuth2 token of its server, all writing
  into the TestScript's folder of the communication log; request ids stay unique across the run.
* **Fixtures on each server.** `autocreate` creates each fixture on every server of the TestScript (each server in
  dependency order), and `autodelete` deletes it there. A `targetId` naming a fixture addresses the copy on the
  operation's server; references to autocreated fixtures are rewritten to that server's ids. An assertion on a fixture
  sees it as it is on the server of the last operation.
* **Capabilities per server.** A `metadata.capability` with `destination` is checked against that server; without,
  against every server of the TestScript. An unmet requirement skips the TestScript, naming the destination.
* **Origins are not modelled.** The runner sends every request itself, so it is every `origin`. TestScripts that wait
  for another client to send a request (they test a client) still cannot run.
* **`destination.url` (R5) is not used.** The configuration decides where requests go, never the TestScript: a
  TestScript read from a package must not direct the runner to a server of its author's choosing.
* **Results:** `TestScriptResult.fhirServers` lists the base URL of every server the TestScript ran against, in index
  order (replacing `fhirServer`); a FHIR TestReport has one `participant` per server; `report.html` and
  `result.json` show them.

## Alternatives
* *Map by profile code* – ambiguous for two destinations with one code, and every destination of a plain FHIR server
  test has the same code. Could be added later as a second key, if a suite needs it.
* *Run-wide `fhir-servers`* – one map for every suite. Suites (IGs) number their destinations independently; a
  per-suite map is explicit, and YAML anchors repeat one where needed.
* *Fall back to `fhir-server` for an unmapped index* – convenient for "the server plus a second one", but with two
  unmapped indexes both requests would reach one server, the case this feature exists to avoid.
* *Create autocreate fixtures only on the first server* – simpler, but a `targetId` on another server would fail, and
  it contradicts "on each server being tested".
* *An operation without destination goes to the lowest index* – a guess the specification does not make; the author
  names the server instead, and the check says where.

## Consequences
* TestScripts for several servers run once their indexes are mapped; the `Load` error now names the property to set.
* `SuiteConfig` has a twelfth component, `fhirServers` (`withFhirServers` makes a copy with them),
  `CapabilityRequirement` a sixth, `destination`. `TestScriptResult.fhirServer` became `fhirServers`, and
  `TestReportContent.server` became `servers` (CHANGELOG).
* An autocreated fixture costs one request per server, and as many on teardown.

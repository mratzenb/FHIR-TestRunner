# Glossary

[Home](README.md) › Glossary

| Term | Meaning |
|---|---|
| **TestScript** | FHIR resource that describes a test: fixtures, setup, tests, teardown. The runner executes it against a server. |
| **TestReport** | FHIR resource with the result of a TestScript. The runner writes one per TestScript (format `testreport`). |
| **Suite** | A set of TestScripts with its folders, usually one IG. Configured under `testrunner.suites`. |
| **Run** | One call of `TestRunner.run`. Has a run id and a run folder. |
| **Run folder** | `<output-dir>/run_<timestamp>/` – reports, log and communication log of one run. |
| **Phase** | Load, Setup, one Test, or Teardown. Each gets a result. |
| **Action** | One step in a phase: an operation or an assertion. |
| **Operation** | A request to the server (`create`, `read`, `search`, …). |
| **Assertion** | A check on a response, a request sent or a fixture. Carries exactly one `Check`. |
| **Fixture** | A resource used by a TestScript, from `Example_Instances/` or from `TestScript.contained` (`#id`). `autocreate`/`autodelete` create and delete it on the server. See [Fixtures](fixtures.md). |
| **Variable** | A value declared in `TestScript.variable`, read from a fixture or response by FHIRPath, path or header. Used as `${name}`, evaluated at each use. See [Variables and placeholders](variables.md). |
| **Built-in value** | A value the runner provides, `${testrunner.runId}`, `${testrunner.uuid}`, …; fixed per TestScript execution. |
| **Example Instances** | Folder with the resources behind `fixture.resource.reference`. |
| **Profiles folder** | Folder with StructureDefinitions, passed to the validator as `-ig`. |
| **IG** | Implementation Guide. Its profiles are needed to validate. Configured per suite (`igs`) or taken from a package. |
| **FHIR package** | The published form of an IG (`name#version`). Cached in `~/.fhir/packages`. |
| **Port** | An interface in the core for something that varies (`FhirVersionSupport`, `ProfileValidator`, …). |
| **Adapter** | An implementation of a port, usually in its own module. |
| **Version-neutral model** | The records in `core.model`. Every FHIR version is mapped to them. |
| **Dialect** | `TestScriptDialect`: the semantic differences of one FHIR version. |
| **Read warning** | Content a FHIR version had to ignore while reading. Reported in the Setup phase. |
| **Request id** | `REQ-0001`, … unique per run. Links log lines, reports and communication folders. |
| **OAuth2 access token** | Fetched by the runner with the client credentials grant when `fhir-server.oauth2` is set; sent as `Authorization: Bearer …`, renewed before it expires. |
| **Communication log** | One folder per HTTP exchange with `request.http` and `response.http`. Credential headers are masked (`Authorization: Bearer ***`). |
| **MDC** | SLF4J's mapped diagnostic context. Key `testScript` names the running TestScript in every log line. |
| **Process mode** | One validator process per validation. |
| **Server mode** | Long-running validator servers. The default. |
| **Fallback** | Server mode switching to process mode after a server failed. |
| **Negative control** | A TestScript that must fail. Proves that validation really runs. |
| **Outcome** | `pass`, `skip`, `fail`, `error`. Combined by severity: `error > fail > skip > pass`. |
| **fail** | The server did not behave as expected. |
| **error** | Something could not be executed: invalid TestScript, unsupported feature, validator or network problem. |
| **PoC** | The Python proof of concept this project replaces: `HL7-AT-TestFhiry-TestRunner`. |

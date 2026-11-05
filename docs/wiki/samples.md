# Samples

[Home](README.md) › Samples

Working examples to copy from. Every file under [`samples/`](samples) runs: `EndToEndIT` runs both suites on each
build, so this page and the runner cannot drift apart.

| Sample | Shows |
|---|---|
| [Your first suite](#your-first-suite) | the folder layout, a TestScript that creates, reads, searches, updates and deletes, the run folder |
| [Mistakes found before sending](#mistakes-found-before-sending) | what the runner rejects in the phase `Load`, and the messages |
| [Configurations](#configurations) | an R4 and an R5 server, parallel runs, an OAuth2 server, an IG package, CI |
| [Reading a failure](#reading-a-failure) | from `summary.txt` to the exact bytes in `communication/` |
| [Assertions](#assertions) | one line per assertion type |

## Your first suite

### The folders

A suite is a folder with two subfolders. [`samples/first-suite`](samples/first-suite):

```
first-suite/
├─ Test_Scripts/
│  └─ TestScript-create-read-search.json
└─ Example_Instances/
   └─ Patient-example-patient.json        the resource of fixture "patient"
```

A third folder, `Profiles/`, is optional. It holds the StructureDefinitions for `validateProfileId`, see
[validation.md](../validation.md).

### The fixture

[`Patient-example-patient.json`](samples/first-suite/Example_Instances/Patient-example-patient.json) is a plain
Patient. Its identifier is `${testrunner.uuid}`, so each run creates a Patient no other run can find:

```json
{
  "resourceType": "Patient",
  "id": "example-patient",
  "identifier": [{ "system": "urn:example:testrunner-wiki", "value": "${testrunner.uuid}" }],
  "name": [{ "family": "Muster", "given": ["Maria"] }],
  "gender": "female",
  "birthDate": "1980-04-01"
}
```

The TestScript names it by `Patient/example-patient`. The runner finds the file by the type and id in that reference
(`Patient-example-patient.json`), see [Fixtures](fixtures.md#where-the-resource-comes-from).

### The TestScript

[`TestScript-create-read-search.json`](samples/first-suite/Test_Scripts/TestScript-create-read-search.json), the
parts that matter:

```json
"fixture": [{ "id": "patient", "autocreate": false, "autodelete": false,
              "resource": { "reference": "Patient/example-patient" } }],
"variable": [
  { "name": "patientId",  "expression": "Patient.id",                  "sourceId": "created" },
  { "name": "identifier", "expression": "Patient.identifier[0].value", "sourceId": "sent" }
],
```

| Test | Operation | Checks |
|---|---|---|
| `Create` | `create Patient` with `sourceId: patient`, keeps the request as `sent` and the response as `created` | `response: created`, `headerField: Location` `notEmpty` |
| `Read` | `read Patient`, `params: /${patientId}` | `responseCode: 200`, `minimumId: patient`, `Patient.name.family` equals `Muster` |
| `Search` | `search Patient`, `params: ?identifier=urn:example:testrunner-wiki\|${identifier}` | `Bundle.entry.count() = 1`, the entry's id equals `${patientId}` |
| `Update` | `update Patient`, `params: /${patientId}`, `sourceId: patient` | `responseCode: 200` |
| teardown | `delete Patient`, `params: /${patientId}` | – |

Three things to notice:

* `patientId` reads the **response** of the create: the id the server assigned. It is evaluated each time it is
  used, see [Variables](variables.md).
* `identifier` reads the **request** as it was sent (`requestId: sent`): the UUID the runner put into the fixture.
  The fixture as written still has `${testrunner.uuid}`.
* The update sends the fixture to `Patient/<server id>`. The fixture says `"id": "example-patient"`; the runner
  replaces it with the id of the URL, otherwise the server would reject the update.

### Run it

Start a FHIR R4 server and run the suite with [`samples/first-suite.yml`](samples/first-suite.yml):

```sh
docker run -d -p 8080:8080 -e hapi.fhir.fhir_version=R4 hapiproject/hapi:v8.12.0-1
mvn -B -DskipTests package
java -jar testrunner-app/target/testrunner-app-0.1.0-SNAPSHOT-exec.jar \
  --config=docs/wiki/samples/first-suite.yml --novalidator
```

```yaml
testrunner:
  fhir-server:
    base-url: http://localhost:8080/fhir
  suites:
    - name: first-suite
      fhir-version: R4
      base-dir: docs/wiki/samples/first-suite
  reporting:
    output-dir: target/testrunner-results
    formats: [junit, summary, json]
```

`--novalidator` switches profile validation off; this TestScript validates nothing. The console shows one line per
request (HAPI FHIR 8.12, abbreviated):

```
INFO  [01_TestScript-create-read-search] REQ-0001 POST http://localhost:8080/fhir/Patient -> 201 Created (118 ms)
INFO  [01_TestScript-create-read-search] Assert passed: response 'created' [REQ-0001]
INFO  [01_TestScript-create-read-search] Assert passed: headerField 'Location' notEmpty [REQ-0001]
INFO  [01_TestScript-create-read-search] REQ-0002 GET http://localhost:8080/fhir/Patient/1000 -> 200 OK (36 ms)
INFO  [01_TestScript-create-read-search] Assert passed: minimumId 'patient' [REQ-0002]
INFO  [01_TestScript-create-read-search] REQ-0003 GET http://localhost:8080/fhir/Patient?identifier=urn:example:testrunner-wiki%7C2ff47… -> 200 OK
INFO  [01_TestScript-create-read-search] Assert passed: expression 'Bundle.entry.count() = 1' [REQ-0003]
INFO  [01_TestScript-create-read-search] REQ-0004 PUT http://localhost:8080/fhir/Patient/1000 -> 200 OK (12 ms)
INFO  [01_TestScript-create-read-search] REQ-0005 DELETE http://localhost:8080/fhir/Patient/1000 -> 200 OK (16 ms)
```

The `|` of the token search is sent as `%7C` (`encodeRequestUrl`, default `true`). The run ends with exit code 0 and
this `summary.txt`:

```
Run:               run_2026-11-05_17-54-43-991
Result:            PASS
TestScripts:       1
  passed:          1
  failed:          0
  errors:          0
  skipped:         0
------------------------------------------------------------
PASS   CreateReadSearch (TestScript-create-read-search.json)
```

Everything else is in the run folder, see [logging.md](../logging.md):

```
target/testrunner-results/run_2026-11-05_17-54-43-991/
├─ summary.txt  junit.xml  result.json  testrunner.log
└─ communication/01_TestScript-create-read-search/
   ├─ REQ-0001_POST_Patient/              request.http  response.http
   ├─ REQ-0002_GET_Patient_1000/
   ├─ REQ-0003_GET_Patient/
   ├─ REQ-0004_PUT_Patient_1000/
   └─ REQ-0005_DELETE_Patient_1000/
```

## Mistakes found before sending

[`samples/mistakes`](samples/mistakes) holds a TestScript with four mistakes. Each would fail in every run, so the
runner reports all of them at once and sends **nothing** – no fixture is half-created on the server:

```json
"variable": [{ "name": "patientId", "expression": "Patient.id", "sourceId": "created" }],
"test": [{ "name": "Mistakes", "action": [
  { "operation": { "type": { "code": "read" },   "resource": "Patient", "params": "/${patientId}" } },
  { "assert":    { "contentType": "json", "operator": "greaterThan", "value": "x" } },
  { "operation": { "type": { "code": "update" }, "resource": "Patient", "params": "/1" } },
  { "operation": { "type": { "code": "create" }, "resource": "Patient", "sourceId": "patinet", "responseId": "created" } }
]}]
```

```sh
java -jar testrunner-app/target/testrunner-app-0.1.0-SNAPSHOT-exec.jar --novalidator \
  --testrunner.suites[0].base-dir=docs/wiki/samples/mistakes --testrunner.suites[0].fhir-version=R4
```

`summary.txt`, exit code 1:

```
ERROR  Mistakes (TestScript-mistakes.json)
       Load 'Load': ERROR
         TestScript is not valid: Test 'Mistakes', action 1: variable 'patientId' reads 'created', which is not there
         yet, it is kept by Test 'Mistakes', action 4; Test 'Mistakes', action 2: Operator greaterThan cannot be used
         with contentType (allowed: contains, notContains, equals, notEquals); Test 'Mistakes', action 3: The PUT of
         operation 'update' has no sourceId, so there is nothing to send; Test 'Mistakes', action 4: sourceId 'patinet'
         is neither a fixture nor the responseId or requestId of an operation
```

| Action | Mistake | Fix |
|---|---|---|
| 1 | uses a response kept only by a later action | move the create before the read |
| 2 | `contentType` only knows `contains`, `notContains`, `equals`, `notEquals` | `"operator": "contains"` |
| 3 | an update without a body | add `"sourceId": "patient"` |
| 4 | a typo in a fixture id | `"sourceId": "patient"`, and declare the fixture |

The full list of what is checked before sending:
[How a run works › Checks before sending](run-lifecycle.md#6-checks-before-sending). What is **not** checked there,
because it depends on values only known while running: a variable that finds nothing, a server that answers
differently than expected, an invalid URL built from a variable.

## Configurations

Each of these works as a file for `--config=<file>`; the OAuth2 one only sets the server, add `suites` to it. The full
reference with every default: [configuration.md](../configuration.md#reference). Paths are relative to the folder
the runner is started in.

### An R4 and an R5 server in one run

```yaml
testrunner:
  fhir-server:
    base-url: http://localhost:8080/fhir     # the default server: R4
  suites:
    - name: at-core-r4
      fhir-version: R4
      base-dir: testdata/r4
      include:                               # create and update, not the negative control
        - TestScript-testscript-patient-create-at-core.json
        - TestScript-testscript-patient-update-at-core.json
    - name: at-core-r5
      fhir-version: R5
      base-dir: testdata/r5
      fhir-server:
        base-url: http://localhost:8081/fhir # this suite has a server of its own
  validation:
    validator-jar: ${VALIDATOR_JAR}          # from the environment
```

```sh
docker run -d -p 8080:8080 -e hapi.fhir.fhir_version=R4 hapiproject/hapi:v8.12.0-1
docker run -d -p 8081:8080 -e hapi.fhir.fhir_version=R5 hapiproject/hapi:v8.12.0-1
```

### Parallel, with one suite that must run alone

```yaml
testrunner:
  suites:
    - name: shared-patients
      base-dir: testdata/samples
      parallel: false        # these TestScripts change the same Patients: one after the other, before the rest
    - name: own-data
      base-dir: docs/wiki/samples/first-suite   # uses ${testrunner.uuid}: safe in parallel
  execution:
    parallelism: 4
```

Why and when: [ADR-0008](../adr/0008-parallel-execution.md).

### A server protected by OAuth2

```yaml
testrunner:
  fhir-server:
    base-url: https://fhir.example.org/r4
    oauth2:
      token-url: https://auth.example.org/realms/fhir/protocol/openid-connect/token
      client-id: testrunner
      client-secret: ${OAUTH_CLIENT_SECRET}  # never in a file under version control
      scope: system/*.rs system/*.cud
```

The token is fetched once per run and renewed before it expires. In `communication/` it shows as
`Authorization: Bearer ***`. More: [configuration.md › Servers protected by OAuth2](../configuration.md#servers-protected-by-oauth2).

### The TestScripts of a published IG

```yaml
testrunner:
  suites:
    - package-id: hl7.at.fhir.core.r4#2.1.0  # TestScripts, examples, FHIR version and IGs from the package
      include: ["TestScript-*-create-*.json"]
```

The package is taken from `~/.fhir/packages`, else downloaded from packages.fhir.org. More:
[configuration.md › Suites from FHIR packages](../configuration.md#suites-from-fhir-packages).

### Only some TestScripts

`include` takes glob patterns relative to `Test_Scripts/`. A file runs if any pattern matches:

```yaml
    - name: at-core-r4
      base-dir: testdata/r4
      include:
        - "TestScript-testscript-patient-create-at-core.json"
        - "TestScript-testscript-patient-update-*.json"
```

There is no exclude: `"!TestScript-*-negative-*.json"` is a pattern that matches no file, not an exception. List what
to run instead.

### In a CI job

Every setting is also an environment variable, so a CI job needs no file:

```sh
export TESTRUNNER_FHIRSERVER_BASEURL=https://fhir.test.example.org/r4
export TESTRUNNER_SUITES_0_BASEDIR=testdata/r4
export TESTRUNNER_VALIDATION_VALIDATORJAR=/opt/validator/validator_cli.jar
java -jar testrunner-app-exec.jar --testrunner.exit-on-skip=any
```

A CI job usually also wants limits, so that a slow server ends the job with a result instead of a kill:

```sh
java -jar testrunner-app-exec.jar --testrunner.exit-on-skip=any \
  --testrunner.execution.timeout=20m --testrunner.execution.test-script-timeout=2m \
  --testrunner.execution.fail-fast=true
```

A TestScript that takes longer than 2 minutes is stopped before its next action and is an `error`; its teardown still
runs. With `fail-fast`, the first `fail` or `error` keeps the rest from starting – they are `skip`, so the job ends
early with exit code 1. More: [configuration.md › Run control](../configuration.md#run-control).

Exit codes: 0 pass, 1 fail or error, 2 invalid configuration, 3 skipped. `exit-on-skip=any` makes a single skipped
TestScript fail the job, see [Troubleshooting › Exit codes](troubleshooting.md#exit-codes). `junit.xml` in the run
folder is what CI systems show as test results.

## Reading a failure

Change the expected family name in the first suite to `Mustermann` and run it again. `summary.txt`:

```
FAIL   CreateReadSearch (TestScript-create-read-search.json)
       Test 'Read': FAIL
         Assert failed: expression 'Patient.name.family' equals 'Mustermann' [REQ-0002] - [expression
         'Patient.name.family'] Expected 'Mustermann', got 'Muster'
```

1. `REQ-0002` names the response that was checked.
2. `communication/01_TestScript-create-read-search/REQ-0002_GET_Patient_1000/response.http` has it, byte for byte:

   ```
   HTTP/1.1 200 OK
   Content-Type: application/fhir+json;charset=utf-8
   ETag: W/"1"

   {
     "resourceType": "Patient",
     "id": "1000",
     …
     "name": [ { "family": "Muster", "given": [ "Maria" ] } ],
   ```

3. `grep REQ-0002 testrunner.log` shows the request, every assertion on it and, at DEBUG, the variable values.

`fail` means the server did not do what the TestScript expects; `error` means the TestScript could not be executed.
Who acts on which: [Design insights › fail versus error](design-insights.md#8-fail-versus-error).

## Assertions

One assertion per line, each a complete `assert` element. The checks and their operators:

| Assertion | Passes when |
|---|---|
| `{ "response": "created" }` | the status is `201` (the version's code table, e.g. `okay` = 200, `notFound` = 404) |
| `{ "responseCode": "200,201", "operator": "in" }` | the status is one of them |
| `{ "contentType": "json" }` | `Content-Type` contains `application/fhir+json` (`json`/`xml` are short forms) |
| `{ "headerField": "ETag", "operator": "notEmpty" }` | the header is there |
| `{ "headerField": "Location", "operator": "contains", "value": "/Patient/" }` | the header contains the value |
| `{ "resource": "Patient" }` | the body is a Patient |
| `{ "expression": "Patient.active" }` | the FHIRPath is `true` (default operator `eval`) |
| `{ "expression": "Patient.name.family", "value": "Muster", "operator": "equals" }` | the single value equals |
| `{ "expression": "Bundle.total", "operator": "greaterThan", "value": "0" }` | numbers compare as numbers |
| `{ "path": "$.name[0].family", "value": "Muster" }` | JSONPath on JSON, XPath on XML (`Patient/name/family`) |
| `{ "minimumId": "patient" }` | the body has everything of the fixture `patient` (id excepted, order free) |
| `{ "navigationLinks": true }` | the Bundle has `first`, `last` and `next` links |
| `{ "validateProfileId": "at-core-patient" }` | the validator accepts the body against that profile |
| `{ "expression": "Patient.id", "operator": "equals", "compareToSourceId": "created", "compareToSourceExpression": "Patient.id" }` | the value equals the same value in another response or fixture |

Add `"sourceId": "<id>"` to check a kept response, request or fixture instead of the last response,
`"direction": "request"` to check the last request, `"warningOnly": true` to turn a failure into a warning.
Which operator each check allows is listed in `core.engine.ActionRules`; a wrong one is a `Load` error, as in
[Mistakes found before sending](#mistakes-found-before-sending).

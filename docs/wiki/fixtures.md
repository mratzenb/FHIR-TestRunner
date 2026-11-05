# Fixtures

[Home](README.md) › Fixtures

A fixture is a resource a TestScript works with. The TestScript declares it in `TestScript.fixture` and refers to it
by its id. This page explains where the runner finds a fixture, when it creates one on the server, and what a fixture
looks like each time it is used.

## Declaring a fixture

```json
"fixture": [{
  "id": "patient-create",
  "autocreate": false,
  "autodelete": false,
  "resource": { "reference": "Patient/HL7ATCorePatientExample" }
}]
```

| Element | Meaning |
|---|---|
| `id` | the name operations and assertions use: `sourceId`, `targetId`, `compareToSourceId`, `minimumId`, `variable.sourceId` |
| `autocreate` | create the resource on the server before the setup actions run |
| `autodelete` | delete the created resource after the teardown |
| `resource.reference` | where the resource comes from; see below |

A fixture without `resource` only reserves its id.

## Where the resource comes from

There are two places:

* **Inside the TestScript**, in `TestScript.contained`, when the reference starts with `#`.
* **In a file** in the suite's `Example_Instances/` folder, for any other reference.

### A resource contained in the TestScript

A small fixture can live in the TestScript itself. Then the TestScript is complete in one file:

```json
{
  "resourceType": "TestScript",
  "contained": [
    { "resourceType": "Patient", "id": "pat", "name": [{ "family": "Muster" }] }
  ],
  "fixture": [{
    "id": "patient",
    "autocreate": false,
    "autodelete": false,
    "resource": { "reference": "#pat" }
  }],
  …
}
```

* `#pat` names the contained resource whose `id` is `pat`.
* The resource is used in the format of the TestScript: JSON for a JSON TestScript, XML for an XML one.
* It works in R4 and R5. `contained` looks the same in both.
* If no contained resource has that id, the TestScript gets an `error` in the phase `Load`. The message lists the
  ids that exist: `… has no resource with the id 'other' (contained: [pat])`.
* After loading, a contained fixture is like any other. Placeholders, `autocreate` and `autodelete` work the same way.

### A file in Example Instances

`ExampleInstanceResolver` looks in the suite's `Example_Instances/` folder. The files are named
`<ResourceType>-<id>.json` or `.xml`. These spellings of the reference all find `Patient-Example.json`:

| Reference | Written by |
|---|---|
| `Patient/Example` | SUSHI, for `Reference(Example)` |
| `Patient-Example.html` | the IG Publisher (page name) |
| `http://example.org/fhir/Patient/Example` | an absolute URL; only the last two segments count |

If no file matches, the TestScript gets an `error` in the phase `Load`. The message lists every file tried.

## What happens when

```mermaid
flowchart TD
    load["Load<br/>read every fixture file"] --> reg["Setup starts<br/>register the fixtures, as written"]
    reg --> ac["autocreate<br/>placeholders replaced once,<br/>created in dependency order"]
    ac --> use["Setup, tests, teardown<br/>each use: placeholders replaced,<br/>references rewritten"]
    use --> ad["after the teardown<br/>autodelete, reverse order"]
```

1. **Load.** The resources are read with the TestScript, from `contained` or from the files.
2. **Register.** When the setup starts, every fixture is kept as written.
3. **Autocreate.** Fixtures with `autocreate` are sent with `POST [base]/[type]`, in dependency order (references
   first). The server's id is kept. A rejected create makes the Setup `error` and names the request (`REQ-0003`) and the status.
4. **Use.** Every other fixture is prepared each time it is used. See the next section.
5. **Autodelete.** After the teardown, fixtures with `autodelete` are deleted, in reverse creation order. This also
   happens if the setup or a test failed.

## A fixture at each use

A fixture that is not autocreated is prepared each time it is used:

* **Placeholders** such as `${pid}` are replaced with their values at that moment. See
  [Variables and placeholders](variables.md#placeholders-in-fixture-resources).
* **References to fixtures created on the server** are rewritten to the id the server assigned – a convenience of
  this runner, see [below](#references-to-fixtures-on-the-server).

"Used" means: sent as the body of an operation (`sourceId`), read by an assertion or a variable, compared by
`minimumId`, or named by `targetId`.

An autocreated fixture is prepared once, when it is created. After that, every use sees what was sent.

### References to fixtures on the server

> **A convenience of this runner – not part of the FHIR specification.**
> The TestScript specification sends a fixture exactly as written. Another test engine will not rewrite references.
> A TestScript that relies on rewriting may fail there. `testrunner.execution.rewrite-references` controls it, see
> [below](#how-far-the-rewriting-goes) and [ADR-0012](../adr/0012-reference-rewriting.md).

A fixture written for an IG references other examples by their ids, e.g. `Organization/org-1`. On the server, that
Organization gets another id. Sent as written, the Patient points to a resource the server does not have. The runner
rewrites such references for you:

| Fixture | Written | Sent |
|---|---|---|
| `org` (created first) | `{"resourceType":"Organization","id":"org-1",…}` | created as `Organization/1` |
| `patient` (sent later) | `"managingOrganization": {"reference": "Organization/org-1"}` | `"managingOrganization": {"reference": "Organization/1"}` |

Rules:

* A fixture is matched by its resource type and id as written. A fixture without `id` is never rewritten.
* Relative and absolute references both work. `http://example.org/fhir/Organization/org-1` becomes `Organization/1`.
* A version is dropped: `Organization/org-1/_history/2` becomes `Organization/1`.
* `#contained` and `urn:uuid:` references stay as they are.
* A reference is rewritten only **after** the referenced fixture was created. Actions are never reordered.

#### How far the rewriting goes

| `rewrite-references` | References that are rewritten | Autocreate order |
|---|---|---|
| `none` | none – every fixture is sent as written, like the specification | declaration order |
| `autocreate` (default) | references to **autocreated** fixtures | dependency order |
| `all` | also references to fixtures that a `create` or `update` **action** sent | dependency order |

```yaml
testrunner:
  execution:
    rewrite-references: all     # none | autocreate | all
```

With `all`, two setup actions are enough. The Organization is created first, then the Patient references it:

```json
"fixture": [
  { "id": "org",     "autocreate": false, "autodelete": false, "resource": { "reference": "Organization/org-1" } },
  { "id": "patient", "autocreate": false, "autodelete": false, "resource": { "reference": "Patient/pat-1" } }
],
"setup": { "action": [
  { "operation": { "type": { "code": "create" }, "resource": "Organization", "sourceId": "org" } },
  { "operation": { "type": { "code": "create" }, "resource": "Patient", "sourceId": "patient" } }
]}
```

The log shows each rewrite:

```
Fixture 'org' is Organization/1 on the server [REQ-0001]; references to Organization/org-1 are rewritten (rewrite-references: all)
```

With `all`, the server's id comes from the `Location` header, else from the returned resource, else – for an
`update` – from the URL. The default `autocreate` sends the Patient above with `Organization/org-1`.

#### Portable alternatives

Rewriting is the shortest way. If a TestScript must also run on other engines, use one of these instead:

* **Choose the id yourself.** Create the Organization with `update` to `Organization/org-1`. Then the reference is
  right as written. This is pure specification.
* **A placeholder.** Keep the create response (`responseId`) and write `"reference": "Organization/${orgId}"` with a
  variable on it, see [Variables and placeholders](variables.md#placeholders-in-fixture-resources). This is explicit,
  but placeholders in fixture resources are an extension of this runner too.

To check whether a suite depends on rewriting, run it once with `--testrunner.execution.rewrite-references=none`.

### Fixtures in the wrong order

Before anything is sent, the runner looks at the order of the create and update actions. A fixture that references a
fixture that only a later action creates gets a warning on the Setup phase:

```
WARNING: Setup, action 1 sends fixture 'patient', which references Organization/org-1 – but fixture 'org' is only
created by Setup, action 2; create it first, or give it autocreate
```

It is a warning, not an error. The server may have that resource already, e.g. from an earlier run. The TestScript
still runs. The warning appears for every `rewrite-references` value, because the reference is wrong in all of them.

## Errors

| Problem | Outcome |
|---|---|
| no file for `resource.reference` | `error` in phase `Load`, nothing sent |
| no contained resource for `#id` | `error` in phase `Load`, nothing sent |
| `autocreate` on content that is no FHIR resource | Setup `error` |
| two autocreated fixtures with the same type and id | Setup `error` |
| autocreated fixtures reference each other in a cycle | Setup `error`, tests `skip` |
| a fixture is sent before a fixture it references is created | warning on the Setup, the TestScript runs |
| the server rejects an autocreate | Setup `error` with request id and status |
| a placeholder in an autocreated fixture fails | Setup `error`, tests `skip`, teardown runs |
| a placeholder in another fixture fails | the operation or assertion using it is `error` |
| an autodelete fails | Teardown `error` |

## Code map

| Class | Role |
|---|---|
| `core.model.FixtureDefinition` | one declared fixture |
| `core.source.ExampleInstanceResolver` | finds the file behind `resource.reference` |
| `core.source.TestScriptLoader` | reads the fixture resources with the TestScript: `#id` from `contained`, the rest from files |
| `core.engine.FixtureManager` | `register`, `autocreate`, `resolve` (each use), `sentByOperation` (`all`), `autodelete` |
| `core.config.ReferenceRewriting` | the setting `none` / `autocreate` / `all` |
| `core.engine.TestScriptChecks.orderWarnings` | the warning about fixtures in the wrong order |
| `core.engine.ExecutionState` | keeps the fixtures as written; `find` hands them out resolved |
| `core.content.ResourceContent` | `replaceText`, `references`, `replaceReferences`, `contained` |

Tests: `FixturesTest`, `PlaceholdersTest` and `ReferenceRewritingTest`;
`ResourceContentTest.containedResourcesAreBodiesOfTheirOwn`; `EndToEndIT.fixtureFromTestScriptContainedInR4AndR5`.

The reference is [architecture.md › Execution semantics](../architecture.md#execution-semantics).

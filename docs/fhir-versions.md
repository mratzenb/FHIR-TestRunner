# FHIR versions: R4, R5 and later R6

The runner executes TestScripts written in FHIR R4 and R5 – even mixed within one folder – and is built so that R6
can be added as a module once it is published. This document explains how the mapping between the versions works,
why it is shaped this way, and what adding a version takes.

## The problem

TestScript changed between R4 and R5 (see the [R5 diff](https://hl7.org/fhir/R5/testscript.html)), and the sample
TestScripts of the proof of concept use both versions:

| Element | R4 | R5 | What the runner must do |
|---|---|---|---|
| `TestScript.profile` | `Reference` with an `id`: `{"id":"p","reference":"http://…"}` | `canonical` with the id on the primitive: `"profile":["http://…"], "_profile":[{"id":"p"}]` | Same `(id, canonical)` pair either way |
| `operation.type` code system | `testscript-operation-codes` | `restful-interaction` | Same codes for the interactions used (`read`, `create`, …) |
| `operation.resource`, `assert.resource` | `code` (`Patient`) | `uri` (`Patient` or a URL) | Resource type name |
| `assert.stopTestOnFail` | does not exist | mandatory | Absent ⇒ continue |
| `assert.response` codes | `bad`, `unprocessable`, … | `badRequest`, `unprocessableContent`, 30+ new ones | Same HTTP status |
| `assert.defaultManualCompletion`, `operator=manualEval`, `assert.requirement` | do not exist | new | Not executable by an automated runner |
| Example Instances, responses | R4 resources | R5 resources | FHIRPath evaluated with the right model |

Most elements the runner uses are *identical* in both versions. A design that duplicates the mapping per version
would copy ~95 % identical code for every version; a design that ignores versions breaks on the 5 % that differ.

## The design

<figure>
<svg viewBox="0 0 880 330" role="img" aria-label="An R4 and an R5 TestScript file each go through their own HAPI parser and FhirContext, then through the one generic TestScriptMapper that reads elements by name, asking a small per-version dialect for semantic differences, and end up as the same version-neutral TestScript model that the engine executes." xmlns="http://www.w3.org/2000/svg" style="max-width:100%;height:auto;font-family:sans-serif;font-size:12px">
  <defs>
    <marker id="m" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
      <path d="M0,0 L10,5 L0,10 z" fill="currentColor"/>
    </marker>
  </defs>
  <g fill="none" stroke="currentColor" stroke-width="1.3">
    <rect x="10" y="40" width="150" height="54" rx="6"/>
    <rect x="10" y="150" width="150" height="54" rx="6"/>
    <rect x="10" y="260" width="150" height="54" rx="6" stroke-dasharray="5 4"/>
    <rect x="210" y="40" width="170" height="54" rx="6"/>
    <rect x="210" y="150" width="170" height="54" rx="6"/>
    <rect x="210" y="260" width="170" height="54" rx="6" stroke-dasharray="5 4"/>
    <rect x="440" y="120" width="190" height="110" rx="6" stroke="#2f7d6d" stroke-width="2.2"/>
    <rect x="690" y="140" width="180" height="70" rx="6"/>
    <rect x="440" y="10" width="190" height="50" rx="6"/>
  </g>
  <g fill="currentColor" text-anchor="middle">
    <text x="85" y="63" font-weight="bold">R4 TestScript</text>
    <text x="85" y="80">profile: Reference</text>
    <text x="85" y="173" font-weight="bold">R5 TestScript</text>
    <text x="85" y="190">profile: canonical</text>
    <text x="85" y="283">R6 TestScript</text>
    <text x="85" y="300">(future)</text>
    <text x="295" y="63" font-weight="bold">FhirContext R4</text>
    <text x="295" y="80">strict parse → HAPI model</text>
    <text x="295" y="173" font-weight="bold">FhirContext R5</text>
    <text x="295" y="190">strict parse → HAPI model</text>
    <text x="295" y="283">FhirContext R6</text>
    <text x="295" y="300">+ dialect</text>
    <text x="535" y="145" font-weight="bold">TestScriptMapper</text>
    <text x="535" y="165">one implementation</text>
    <text x="535" y="185">reads elements by name</text>
    <text x="535" y="205">(ElementView)</text>
    <text x="535" y="32" font-weight="bold">TestScriptDialect</text>
    <text x="535" y="48">per version, ~0–20 lines</text>
    <text x="780" y="168" font-weight="bold">TestScript model</text>
    <text x="780" y="186">version-neutral</text>
    <text x="780" y="202">→ engine</text>
  </g>
  <g stroke="currentColor" stroke-width="1.2" fill="none" marker-end="url(#m)">
    <line x1="160" y1="67" x2="210" y2="67"/>
    <line x1="160" y1="177" x2="210" y2="177"/>
    <line x1="160" y1="287" x2="210" y2="287" stroke-dasharray="5 4"/>
    <line x1="380" y1="67" x2="440" y2="140"/>
    <line x1="380" y1="177" x2="440" y2="177"/>
    <line x1="380" y1="287" x2="440" y2="215" stroke-dasharray="5 4"/>
    <line x1="535" y1="60" x2="535" y2="120"/>
    <line x1="630" y1="175" x2="690" y2="175"/>
  </g>
  <text x="545" y="95" fill="currentColor" font-size="11">semantic differences</text>
</svg>
<figcaption>The version-specific part is the left column (HAPI's structures and a dialect). The mapping in the
middle exists once; the engine on the right never sees a version-specific type.</figcaption>
</figure>

Three layers, each with one job:

1. **Version-neutral model** (`testrunner-core`, `at.hl7.testing.runner.core.model`). Records holding exactly what
   the engine executes: `TestScript`, `Destination`, `CapabilityRequirement` (`metadata.capability`, with its
   `destination`),
   `FixtureDefinition`, `ProfileDeclaration(id, canonical)`, `Operation`, `Assertion` with a sealed `Check` (one of
   contentType, expression, …), `VariableDefinition`. Nothing in the
   engine refers to a FHIR version except `fhirVersion` passed through to the validator. The three wide records –
   `TestScript` (13 components), `Operation` (17) and `Assertion` (12) – are built with a `builder()` and copied with
   `toBuilder()`, by element name: a new element is one more builder method, and no place that creates one of them
   depends on the order of the components.

2. **One generic mapper** (`testrunner-fhir-hapi`, `TestScriptMapper`). It reads the HAPI model of *any* version
   through `ElementView`, which looks up children by element name using HAPI's runtime definitions
   (`BaseRuntimeElementCompositeDefinition#getChildByName`). An element that does not exist in a version simply
   reads as absent – that is how `stopTestOnFail` disappears in R4 without any `if (r4)`. Type-only differences are
   absorbed generically:
   * `profile`: `ElementView.isPrimitive()` ⇒ canonical (R5), else read `reference` (R4); the id comes from
     `elementId()`, which is the Reference's `id` in R4 and the primitive's `_profile.id` in R5. One code path.
   * `resource`: `code` or `uri`, both reduced to the type name.
   * `response` codes: one table mapping every code of every version to its HTTP status (no code changed its
     meaning between versions); HAPI's strict parser rejects codes a version does not know.

3. **A dialect per version** (`TestScriptDialect`, e.g. `R5Dialect`). Only *semantic* differences live here, each
   as a named method with a comment naming the element and versions:
   * `stopTestOnFailDefault()` – the value when the element is absent.
   * `check(...)` – additional checks to tell versions apart where HAPI's parsers would accept both
     (an R4 single-profile `Reference` parses as an R5 canonical-with-id-but-no-value).

   `R4Dialect` is empty; `R5Dialect` has one check. The interface is the one place to look for "what differs".

FHIRPath (`assert.expression`, `variable.expression`) is evaluated with the HAPI engine of the TestScript's version
(`HapiFhirPathEvaluator`), so `Patient.name` is evaluated on an R4 or R5 model as appropriate, for JSON and XML alike.
Its results keep their type: booleans, numbers, and dates and times as `model.FhirTemporal` with the precision and
offset as written, so that assertions compare them by the FHIRPath rules
([ADR-0016](adr/0016-typed-comparisons.md)).

The results go the other way the same way: the core describes a FHIR TestReport version-neutrally
(`TestReportContent`), and one `TestReportEncoder` writes it for the TestScript's version by element name. The only
difference so far is a type difference – `TestReport.testScript` is a `Reference` in R4 and a `canonical` in R5 –
handled in one `if`.

### Version detection

A suite either pins its version (`fhir-version: R4`) or uses `AUTO` (default). With `AUTO`, every available
version parses the file with a **strict, collecting** error handler (`CollectingErrorHandler`):

* *errors* – values of the wrong JSON type, invalid codes ⇒ the version cannot read the file;
* *warnings* – unknown elements ⇒ the version would ignore content.

`FhirVersionRegistry.detect` picks the version without errors and with the fewest warnings; on a tie the newest.
Examples from `testdata/`:

| File | R4 check | R5 check | Detected |
|---|---|---|---|
| `r4/…patient-update-at-core.json` (profile as Reference) | exact | warning: profile without canonical | R4 |
| `r5/…patient-update-at-core.json` (canonical + `_profile`, `stopTestOnFail`) | error: profile must be object | exact | R5 |
| `samples/…delete.json` (R5) | error | exact | R5 |
| `samples/…stopTestOnFail.json` (R4 file using R5-only `stopTestOnFail`) | warning: unknown element | error | **R4**, the ignored element is reported as a warning in the Setup phase |

The last case is the reason warnings are carried into the report: content a version ignores is never dropped
silently.

## Why HAPI FHIR and not hand-written JSON mapping

* HAPI already knows every element, type and code list of each version; the strict parser gives version
  detection and structural validation for free.
* R6 support will arrive as `hapi-fhir-structures-r6` (not in HAPI FHIR 8.12.1, the version used now); nothing in
  this design has to be learnt again.
* The alternative – Jackson mapping per version – would mean maintaining the TestScript schema of every version
  by hand, the duplication the brief asks to avoid.

The cost is jar size (≈ 30 MB per version). HAPI's FHIRPath engines usually load the whole specification through a
*validation support*; the runner installs a `ModelOnlyValidationSupport` instead, which keeps the `hapi-fhir-validation`
artefacts (24 MB for R5 alone) off the classpath. FHIRPath on concrete resources needs the model only.

One compatibility rewrite exists: `X.resourceType` is not an element of the FHIR model, so a spec-conformant engine
returns empty for `Patient.resourceType = 'Patient'`. The PoC's JSON-based engine (fhirpathpy) returned the JSON
property, and the sample TestScripts rely on it. `HapiFhirPathEvaluator` evaluates the step `.resourceType` as
`.type().name`, which yields the same value.

## Adding R6

This needs a HAPI FHIR release with `hapi-fhir-structures-r6` and `FhirContext.forR6()`. HAPI FHIR 8.12.1 has
neither – not even `FhirVersionEnum.R6` – so first raise `hapi-fhir.version` in the root `pom.xml` to a release
whose BOM lists `hapi-fhir-structures-r6`, and check that `mvn verify` still passes. Then:

1. Create `testrunner-fhir-r6` by copying `testrunner-fhir-r5` (pom + two classes + one service file):

   ```java
   public final class R6Support extends HapiFhirVersionSupport {
       public R6Support() {
           super(FhirVersion.R6, FhirContext.forR6(), new R6Dialect());
       }
   }
   ```
   with `META-INF/services/at.hl7.testing.runner.core.spi.FhirVersionSupport` naming it, and the dependency
   `ca.uhn.hapi.fhir:hapi-fhir-structures-r6`.
2. Read the R6 TestScript diff. For every element whose *meaning* changed, add a method to `TestScriptDialect`
   (with an R4/R5 default) and override it in `R6Dialect`. Renamed or newly typed elements usually need nothing.
   If R6 adds an element the engine should execute, add it to the neutral model and read it in `TestScriptMapper`
   – once, for all versions. Check the TestReport diff too: `TestReportEncoder` writes elements by name and absorbs
   type differences (`testScript` Reference vs. canonical); a renamed element needs a case there.
3. Add the module to the root `pom.xml` and as dependency of `testrunner-app`.
4. Put R6 samples under `testdata/r6/` and copy `R5SupportTest`.

`FhirVersion.R6` already exists in the core, so configurations can name it today; without the module the runner
stops with *"Suite … needs FHIR R6 … add the module testrunner-fhir-r6"*.

## Data mapping beyond TestScripts

The runner *executes* TestScripts; it does not convert resources between versions. Example Instances and server
responses are used as they are, in the version of the TestScript. Version-independent operations on them (resource
type, id, `meta.versionId`, references for autocreate) are done on the JSON/XML structure in `ResourceContent`,
which is identical in all versions. If cross-version execution becomes a requirement (e.g. run an R4 TestScript
against an R5 server), the place for it is a resource converter behind a new port, using HAPI's
`hapi-fhir-converter` (`VersionConvertorFactory_40_50`) – not the TestScript mapping.

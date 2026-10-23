---
name: testrunner-fhir-versions
description: How the FHIR TestScript Runner (TestRunnerOnJava) reads R4 and R5 TestScripts into one version-neutral model – the generic HAPI-based TestScriptMapper, ElementView, TestScriptDialect, version detection with strict parsing, FHIRPath per version, and the recipe for adding R6. Use when working in testrunner-fhir-hapi, -fhir-r4, -fhir-r5, when a TestScript is detected as the wrong version or maps wrongly, when mapping a new TestScript element, or when adding a FHIR version.
---

# FHIR versions (ADR-0005, `docs/fhir-versions.md`)

## Three layers

1. **Version-neutral model** – records in `core.model` (`TestScript`, `FixtureDefinition`,
   `ProfileDeclaration(id, canonical)`, `Operation`, `Assertion` + sealed `Check`, `VariableDefinition`,
   `TestDefinition`). The engine never sees a version-specific type; `FhirVersion` (`R4` 4.0.1, `R5` 5.0.0, `R6`
   6.0.0; `parse` accepts `R4`, `r4`, `4.0`, `4.0.1`) only goes to the validator.
2. **One mapper** – `fhir.hapi.TestScriptMapper` reads the HAPI model of *any* version through `ElementView`
   (children by element name via HAPI runtime definitions). An element missing in a version reads as absent
   (`stopTestOnFail` in R4). Type-only differences in one code path:
   * `profile`: `isPrimitive()` ⇒ canonical (R5) else `reference` (R4); id from `elementId()`.
   * `resource` (`code` in R4, `uri` in R5) ⇒ type name.
   * `assert.response` codes ⇒ `ResponseCodes`, one table for all versions (no code changed meaning).
3. **Dialect per version** – `fhir.hapi.TestScriptDialect`, only *semantic* differences, each a named method with a
   comment naming element and versions: `stopTestOnFailDefault()`, `check(ElementView, warnings)`. `R4Dialect` is
   empty (so `stopTestOnFail` defaults to `false` in R4); `R5Dialect.check` warns on a profile without canonical:
   an R4 TestScript with a *single* profile Reference parses in R5 too (read as canonical-with-id-but-no-value), and
   this warning is what makes detection prefer R4.

A version module = `XxSupport extends HapiFhirVersionSupport` (`super(FhirVersion.Xx, FhirContext.forXx(), new
XxDialect())`) + `META-INF/services/at.hl7.testing.runner.core.spi.FhirVersionSupport`. Use `FhirContext.forXx()`,
**not** `forXxCached()`: the context gets a `ModelOnlyValidationSupport` that must not leak into shared contexts.

## Version detection (`spi.FhirVersionRegistry.detect`)

Suite `fhir-version: AUTO` (default) ⇒ every available version parses the file strictly with
`CollectingErrorHandler`: **errors** (wrong JSON type, invalid code) ⇒ that version cannot read it; **warnings**
(unknown element) ⇒ it would ignore content. Pick: no errors, fewest warnings, newest on a tie. Warnings of the
chosen version are reported in the Setup phase – ignored content is never dropped silently. A pinned version
(`R4`, `5.0` …) skips detection; a pinned version without its module ⇒ configuration problem naming
`testrunner-fhir-<v>`.

Reference cases (`testdata/`): r4 update (profile as Reference) ⇒ R4; r5 update (canonical + `_profile`) ⇒ R5;
`samples/TestScript-testscript-delete.json` ⇒ R5; `samples/TestScript-testscript-stopTestOnFail.json` (R4 file using
R5-only `stopTestOnFail`) ⇒ **R4** with warning "unknown element 'stopTestOnFail'".

## FHIRPath

`HapiFhirPathEvaluator` uses the HAPI engine of the TestScript's version (JSON and XML). Compatibility rewrite:
step `.resourceType` is evaluated as `.type().name` (the PoC's fhirpathpy returned the JSON property; samples rely on
it). `ModelOnlyValidationSupport` keeps `hapi-fhir-validation` (24 MB for R5) off the class path.

## Recipes

**Map a new TestScript element** (e.g. a new assert attribute) – it must exist in the HAPI structures of at least
one version, since `ElementView` reads by FHIR element name (a non-FHIR element would need an extension): add it to
the neutral model record → read it once in `TestScriptMapper` with `ElementView` (by name; absent in versions that
lack it) → only if its *meaning* differs per version, add a dialect method with a default → tests: every sample of
a version is mapped exactly by `R4SupportTest.readsEverySampleR4TestScriptExactly` and
`R5SupportTest.readsTheR5SamplesExactly`, plus engine tests (`testrunner-execution-engine`).

**Add R6** – only possible with a HAPI FHIR release that has `hapi-fhir-structures-r6` and `FhirContext.forR6()`;
8.12.1 has neither (not even `FhirVersionEnum.R6`). So first raise `hapi-fhir.version` in the root POM to a release
whose BOM lists `hapi-fhir-structures-r6` and check that `mvn verify` still passes. Then:
1. Copy `testrunner-fhir-r5` to `testrunner-fhir-r6` (pom, `R6Support`, `R6Dialect`, service file); dependency
   `ca.uhn.hapi.fhir:hapi-fhir-structures-r6` (version from the HAPI BOM, none in the module POM).
2. Read the R6 TestScript diff; for each changed *meaning* add a `TestScriptDialect` method (R4/R5 default) and
   override it in `R6Dialect`. Renamed/retyped elements usually need nothing.
3. Root `pom.xml`: `<module>` + `dependencyManagement`; `testrunner-app/pom.xml`: dependency.
4. `ArchitectureTest.versionModulesDoNotKnowEachOther`: add R6 rules.
5. Samples in `testdata/r6/`, copy `R5SupportTest`; update `docs/fhir-versions.md`.

## Not in scope

The runner does not convert resources between versions; Example Instances and responses are used in the version of
the TestScript. Version-independent operations (type, id, `meta.versionId`, references) work on JSON/XML in
`core.content.ResourceContent`. Cross-version execution would be a new port using `hapi-fhir-converter`.

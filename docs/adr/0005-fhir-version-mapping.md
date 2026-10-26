# ADR-0005: Version-neutral model, one generic HAPI mapper, a dialect per FHIR version

## Context
TestScripts exist in R4 and R5 (and will in R6). The PoC handles the differences inline (`isinstance(prof, dict)`
for R4 vs R5 profiles) and needs a global `validation.fhirVersion` to validate correctly. The brief: the mapping
must be understandable and easily extensible, but avoid duplicating everything.

## Decision
1. The engine runs a **version-neutral model** (records in `core.model`).
2. **One mapper** (`TestScriptMapper`) maps the HAPI model of any version, reading elements *by name* via HAPI's
   runtime definitions (`ElementView`). Absent elements read as absent; type-only differences (Reference vs.
   canonical, code vs. uri) are handled in one code path.
3. **Semantic differences** go into a `TestScriptDialect` per version – currently one default and one check.
4. A version module = `FhirContext` + dialect + `ServiceLoader` registration (R4: two classes, 27 lines).
5. The version of a TestScript is **detected** by strict parsing with every available version (errors vs. ignored
   elements), or pinned per suite.
6. The same applies in the other direction: a FHIR **TestReport** is described version-neutrally
   (`spi.TestReportContent`, decided in the core) and written by one generic `TestReportEncoder` that sets elements by
   name (`FhirTerser`); the version module only provides its `FhirContext`.

Details and the R6 recipe: [fhir-versions.md](../fhir-versions.md).

## Alternatives
* *Mapper per version on the typed HAPI classes* (`org.hl7.fhir.r4.model.TestScript` → model): compile-time safety,
  but ~250 lines copied per version that differ in 5 % – the duplication the brief rules out.
* *Convert R4 → R5 with `hapi-fhir-converter`, then map R5 only*: one mapper, but adds a 2nd transformation,
  pulls in the converter, and R6 would need R4→R6 and R5→R6 converters before it can be supported.
* *Own JSON schema per version (Jackson)*: re-implements what HAPI knows, including code lists.

## Consequences
* Element names are strings in the mapper; a typo is not a compile error. Mitigated by tests that map every
  sample TestScript of both versions (`R4SupportTest`, `R5SupportTest`).
* A model change (new executable element) is made once for all versions.
* HAPI's structures are a large dependency (see ADR-0002); they are also what makes FHIRPath version-correct.

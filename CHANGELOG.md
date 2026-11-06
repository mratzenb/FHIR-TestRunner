# Changes

Changes that alter what an existing TestScript does or how the runner is used. The details are in the linked
documents; [next steps](docs/wiki/next-steps.md) has the plan behind them.

## Unreleased (0.1.0-SNAPSHOT)

### Changed – may change results

* **Assertions compare values by their type** (M5.2). A value found by an `expression` keeps its FHIRPath type, and
  the expected value is read as that type ([architecture.md › Execution semantics](docs/architecture.md#execution-semantics)):
  * Numbers by value: `1.0` equals `1.00`.
  * Dates and times as points in time: `2026-01-01T10:00:00+01:00` equals `2026-01-01T09:00:00Z`;
    `greaterThan`/`lessThan` order them in time, not as text.
  * Values that cannot be compared – dates of different precision (`2026-01` and `2026-01-15`), a dateTime with and
    one without offset, a number and a text that is none – now **fail** with the reason. Before, they were compared
    as text, which could pass or fail by accident.
  * A `path` or a header value stays text for `equals`, `in` and `contains`; `greaterThan` and `lessThan` order two
    numbers or two dates as such, and only other text lexicographically.

  A TestScript that relied on text equality of an `expression` value, e.g. `equals "1.0"` to tell `1.0` from `1`,
  passes now where it failed before. Compare the text with `path` instead, or with
  `expression: "Observation.value.value.toString()"`.

### Added

* Packages (M6.2, ADR-0018): `package-id` takes `#current` (the CI build of an IG), `#current$<branch>` and `#2.1.x`
  (highest patch release). With validation on, the packages a suite's package depends on are resolved transitively
  into the cache and passed to the validator with the version found. Every package from the registry is verified
  against the registry's checksum (`dist.shasum`); a mismatch stops the run (exit code 2). New setting
  `testrunner.packages.ci-build` (default `https://build.fhir.org`). The downloads use the JVM's proxy and TLS
  settings and the timeouts of `testrunner.http`.
* Binary content (M5.3): a `Binary` read in its native content type (a PDF, an image) is kept byte for byte, can be
  sent again by `sourceId`, and is written to `communication/` as `response.body.pdf` etc. Text bodies are decoded
  with the charset of their `Content-Type`.
* Container image (`testrunner-app/Dockerfile`) and `docker-compose.yml` with HAPI FHIR (M4.1, ADR-0015).
* Report format `html`: `report.html` with every request id linked into `communication/` (M4.2); on in the
  application's defaults.
* `--check`, `--list`, `--testscript`, `--test` (M3, ADR-0014); run and TestScript timeouts, fail-fast, reports on
  Ctrl-C (M2, ADR-0013).

### Changed – API

* `PackagesConfig` has a third component, `ciBuild`; `PackagesConfig.cache(dir)` is the defaults with another cache
  (M6.2).
* The public API is annotated with `jakarta.annotation.Nonnull`/`Nullable` (ADR-0017), and five methods that
  returned `null` for "absent" return an `Optional` now: `FhirServerConfig.base()`, `OperationType.defaultMethod()`,
  `ContentFormat.fromMimeType(…)`, `ContentFormat.fromFileName(…)`, `TestScriptFormatException.fhirVersion()`. Add
  `.orElse(null)` where the old value is needed. New: `Body.ofFile(path)`.
* The bodies of `FhirRequest` and `FhirResponse` are a `model.Body` (bytes, charset, format), not a `String`
  (M5.3). Use `body().text()` (or `FhirResponse.bodyText()`) for the text, `FhirResponse.text(…)` to create a text
  response in a test, `request.withHeaders(…)` in a wrapping `HttpTransport`. `FhirResponse.asBody()` is gone:
  `body()` is the `Body`.
* `TestScript`, `Operation` and `Assertion` have builders (`builder()`, `toBuilder()`); create them by name, not by
  position (M5.1).
* `RunListener` gets a read-only `RunInfo` instead of `RunContext` (M2.5).
* `RunConfiguration` has an eighth component, `select` (M3.2).

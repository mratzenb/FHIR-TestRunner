# ADR-0017: The public API says where null is allowed

## Context
The javadoc of the public API mentioned `null` in places ("`{@code null}` for none", "may be `{@code null}`"), and
nowhere else. A library user, an adapter author or an IDE could not tell whether a parameter may be `null` or a method
may return it without reading the code. Some methods returned `null` for "absent": `FhirServerConfig.base()`,
`OperationType.defaultMethod()`, `ContentFormat.fromMimeType`/`fromFileName`, `TestScriptFormatException.fhirVersion()`.

## Decision
* **Every reference-typed parameter, return value and record component of the public API is annotated**, with
  `jakarta.annotation.Nonnull` or `jakarta.annotation.Nullable` (`jakarta.annotation-api`, version from the Spring Boot
  BOM, a dependency of the core). The public API is the one named in A9 of
  [next steps](../wiki/next-steps.md#a9--public-api): the core's packages `model`, `config`, `spi`, `report` and the
  types in `ArchitectureTest.CORE_API_TYPES`, and every public type of the other modules. Internal classes are not
  annotated.
* **`@Nullable` only where `null` means something**: an element the TestScript may leave out (`Operation.params`), a
  setting that is not set (`ExecutionConfig.timeout`), a value that is not known (`TestScriptResult.fhirVersion` of an
  unreadable file), an optional argument (`Body.of(bytes, contentType)`). Everything else is `@Nonnull`.
* **Records that turn `null` into a default** (the configuration records, `FhirRequest`, `FhirResponse`) get an
  explicit canonical constructor: its parameters are `@Nullable` – Spring's binder and callers pass `null` for "the
  default" –, while the components and accessors are `@Nonnull`, since the constructor never leaves them `null`.
* **`Optional` for a return value that may be absent**, in place of a nullable return:
  `FhirServerConfig.base()`, `OperationType.defaultMethod()`, `ContentFormat.fromMimeType(…)`,
  `ContentFormat.fromFileName(…)`, `TestScriptFormatException.fhirVersion()`. Like `HttpHeaders.first`,
  `FhirVersionRegistry.get` and `SuiteConfig.pinnedFhirVersion` already did.
* **Not `Optional`** for record components, parameters and fields: a record component is the state of the record, an
  `Optional` component breaks binding and equality, and an `Optional` parameter makes the caller wrap. The
  `TestScript` model keeps `@Nullable` components for the elements a TestScript may leave out.
  `Operator.fromCode` and `OperationType.fromCode` stay `@Nullable` – `null` in, `null` out, so that the mapper can
  pass an absent element through.
* **Enforced**: `ArchitectureTest.publicApiDeclaresWhereNullIsAllowed` fails for a public API position without either
  annotation.

## Alternatives
* *JSpecify (`@NullMarked` per package, only `@Nullable` written)* – less noise and a richer model (type-use,
  generics). Not chosen: the request was for the Jakarta annotations, which Spring Boot and Jakarta EE users know and
  which are already on the runtime class path.
* *Annotate only the nullable positions* – without a package default, an unannotated position could mean either, and
  a check could not tell a forgotten annotation from a deliberate `@Nonnull`.
* *`Optional` everywhere absence is possible* – see above for components and parameters.

## Consequences
* The annotations are documentation and input for IDEs and static analysis; the runtime does not check them. The
  constructors that already rejected `null` (`Objects.requireNonNull`) still do.
* Breaking for callers of the five methods that return `Optional` now: `.base()` → `.base().orElse(null)` etc.
  ([CHANGELOG](../../CHANGELOG.md)).
* A new public method, parameter or component needs an annotation, or `ArchitectureTest` fails.

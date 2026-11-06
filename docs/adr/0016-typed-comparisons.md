# ADR-0016: Assertions compare values by their type

## Context
`Operators` compared every value as text; `greaterThan` and `lessThan` tried numbers and otherwise compared text
lexicographically. FHIRPath returns typed values, but `Values.text` threw the type away. So
`2026-01-01T10:00:00+01:00` and `2026-01-01T09:00:00Z` – the same instant – were not equal, `greaterThan` on dates of
different offsets gave the wrong answer, and `1.0` did not equal `1.00` (finding A7, feature F6 of
[next steps](../wiki/next-steps.md#a7--text-comparison)).

## Decision
* **A value keeps its type.** `HapiFhirPathEvaluator` returns `date`, `dateTime`, `instant` and `time` as
  `model.FhirTemporal` (with the precision and offset as written; HAPI's `java.util.Date` would lose both), numbers as
  `BigDecimal`/`Integer`, booleans as `Boolean`, the rest as text. `Values.value` keeps that type for assertions;
  `Values.single`, for variables, still gives text – a variable is put into text.
* **The expected value is read as the type of the actual one** (`Values.equal`, `Values.order`), or the other way
  round when only the expected value is typed (`compareToSourceExpression`). Text compared with text stays text
  for `equals`/`in`; `greaterThan`/`lessThan` order two texts as numbers or as dates if both are, as before for
  numbers. `contains`/`notContains` work on the text.
* **The FHIRPath rules** for dates and times: compared precision by precision from the year; with offsets as points in
  time (`+00:00` = `Z`); seconds and their fraction are one precision; a comparison that reaches a precision only one
  value has is undecidable. Decimals: equal by value, trailing zeros ignored.
* **What FHIRPath leaves empty is a `fail` with the reason** – dates of different precision, a dateTime with and one
  without offset (FHIRPath leaves the default offset to the implementation; the runner does not guess one), a number
  and a text that is none, an order of booleans. "Cannot be compared" is an answer about the server's data, so it is
  `fail`, not `error`: the TestScript could be executed.
* **The default, not a switch.** It is what a TestScript author means by `equals` on a date; a switch would keep the
  wrong answer alive and double the tests. The change is in the [CHANGELOG](../../CHANGELOG.md).

## Alternatives
* *A switch `execution.typed-comparisons`* – the proposal left open in next-steps; rejected as above.
* *Leave decimals compared as text, since FHIR keeps their precision* – `minimumId` still compares as written (a
  resource's content); an assertion compares values, and FHIRPath's `=` ignores trailing zeros. A TestScript that needs
  the text uses `.toString()` or `path`.
* *Compare a value without offset as UTC, or in the runner's zone* – would make the result depend on where the runner
  runs; FHIRPath leaves it open, the runner says so instead.

## Consequences
* A TestScript that relied on text equality of an `expression` value can pass where it failed before (`equals "1.0"`
  on a `1.00`). Dates compared across offsets pass now.
* `Values` and `Operators` work on `Object`; `FhirPathEvaluator` documents the types it returns. A version module that
  returns a date as `String` still works: it is compared as text, as before.
* `FhirTemporal` is a model type, so the version modules may use it (`ArchitectureTest`: `core.model` is API).

# ADR-0012: Rewriting references to fixtures created on the server – a configurable convenience

## Context
Fixtures are usually examples of an IG and reference each other by their example ids: a Patient has
`managingOrganization: Organization/org-1`. On the server the Organization gets an id of its own, e.g. `1`. Sent
as written, the Patient points to a resource the server does not have, and a server that checks references rejects
it.

The FHIR TestScript specification does not help. It sends a fixture as written. Variables may only be used in
`operation.params`, `requestHeader.value`, `url` and `assert.value`, not in fixture resources. Nothing in it rewrites
references or orders fixtures. A spec-conformant TestScript has to create the referenced resource with an id the
author chose (`update` to `Organization/org-1`) or avoid the reference.

The Python proof of concept rewrote references between `autocreate` fixtures and created them in dependency order.
The runner took that over. With fixtures resolved at each use (placeholders, see `docs/wiki/fixtures.md`), it also
rewrote references to autocreated fixtures in fixtures sent by operations. Users asked for the same when both
fixtures are created by setup actions.

## Decision
* Rewriting is a feature of **this runner**, not of the specification. It is documented as such wherever it
  appears.
* `testrunner.execution.rewrite-references` (`ReferenceRewriting`) chooses how far it goes:

  | Value | Effect |
  |---|---|
  | `none` | Fixtures are sent as written. Autocreate fixtures are created in declaration order. |
  | `autocreate` (default) | References to autocreated fixtures are rewritten, in other autocreated fixtures (created in dependency order) and in fixtures sent by operations. |
  | `all` | Also references to a fixture that a `create` or `update` operation sent, once the server answered with its id. |

* The default stays `autocreate`: it is what the PoC and the runner did so far, and existing TestScripts rely on it.
  `all` is opt-in, because it goes furthest beyond the specification.
* Actions are never reordered: in a TestScript the order is the author's, an assertion checks the response of the
  operation before it.
* Before the run, `TestScriptChecks.orderWarnings` reports a fixture sent by a create or update that references a
  fixture only a later action creates. It is a warning on the Setup phase, not an error: the server may have that
  resource already.

## Alternatives
* *Always rewrite, without a setting* – convenient, but a TestScript that passes here could fail on another engine
  without anyone noticing. `none` lets authors check that their TestScripts are portable.
* *Reorder setup actions by their references* – would change what assertions check.
* *Rewrite only with `all`, nothing by default* – spec-pure, but breaks the existing TestScripts and the PoC
  behaviour.
* *Placeholders instead of rewriting* (`"reference": "Organization/${orgId}"` with a variable on the create
  response) – works today and is explicit, but placeholders in fixture resources are an extension of the runner too.

## Consequences
* A TestScript that relies on rewriting is **not portable**: another engine sends the fixture as written. Run a suite
  with `rewrite-references: none` to find out whether it depends on it.
* With `all`, the id comes from the `Location` header, else from the returned resource, else (update) from the URL.
  A fixture is matched by its resource type and id as written. A fixture without id is not rewritten.
* A reference is rewritten only after the referenced fixture was created. The order warning points at TestScripts
  that send them the other way round.

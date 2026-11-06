# ADR-0018: Package resolution – versions as the validator reads them, dependencies verified in the shared cache

## Context
A suite can be read from a FHIR package (`suites[n].package-id`). Until 2026-11 the runner resolved only an exact
version or `#dev`, and only the suite's own package. Its dependencies went to the validator as `-ig` arguments, and
the validator downloaded them itself, unverified. Three gaps (F9 and A10 of [next steps](../wiki/next-steps.md)):

* **`#current`** – an IG's CI build on build.fhir.org – is what an IG author tests before a release. The runner
  rejected it; the validator accepted it as an IG.
* **Transitive dependencies and versions such as `2.1.x`** were not resolved by the runner.
* **The download** used a new `HttpClient` per package, never closed it, and did not check the archive. A package's
  TestScripts are sent to servers, so the integrity of the package matters.

The cache `~/.fhir/packages` is shared with the HL7 validator, the IG Publisher and SUSHI. What the runner puts there,
the validator uses without asking again.

## Decision
* **Versions as the HL7 validator reads them** (`source.PackageRef`): exact (`2.1.0`), patch wildcard (`2.1.x`, the
  highest patch release, from the cache first, else from the registry), `dev` (cache only), `current` and
  `current$<branch>` (CI build). Other forms (npm ranges, aliases) are passed to the validator as written.
* **`#current` the way the validator finds it:** the most recent build of the package in `<ci-build>/ig/qas.json`
  names the repository; the build is `<ci-build>/ig/<org>/<repo>/package.tgz` (or `branches/main`, or
  `branches/<branch>`). A cached build is reused while its `date` equals the build's `package.manifest.json`;
  otherwise it is downloaded again and replaces the old one. Without the CI server a cached build is used, with a
  warning. As in the validator's cache, `package.json` gets `version: current` and keeps the build's version as
  `original-version`. A `dev` dependency that is not in the cache becomes `current`, as in the validator.
* **Every released package is verified.** The registry's entry `<registry>/<name>` lists `dist.shasum` (SHA-1, npm
  style) per version. The archive is hashed while it is unpacked into a folder of its own; a mismatch is a
  configuration problem and nothing is put into the cache. A version without `shasum` is taken with a warning (a few
  old ones on packages.fhir.org have none). CI builds have no checksum; the log says so. The archive must also hold
  the package asked for (`name`, or `name.r4` for `name` – the validator's rule).
* **Dependencies only for the validator.** With validation on, every dependency is resolved transitively and
  breadth-first, each once, into the cache – so the validator finds each one verified – and becomes an IG of the suite
  with the version found (`7.4.x` → `7.4.2`). The core package of the FHIR version is left out, the validator has it.
  Without a validator, or in `--list`, only the suite's package is resolved; its direct dependencies are named as
  written.
* **One HTTP client per resolution.** `PackageSuites` is `AutoCloseable`; `TestRunner` closes it after resolving. The
  client follows redirects (registries redirect to their CDN), takes the connect and request timeouts from
  `testrunner.http`, and – like `JdkHttpTransport` – the JVM's proxy selector and SSL context (`-Dhttps.proxyHost`,
  `-Djavax.net.ssl.trustStore`, the container's `USE_SYSTEM_CA_CERTS`).
* **New setting:** `testrunner.packages.ci-build` (default `https://build.fhir.org`).

## Alternatives
* *Load packages with the validator's own `FilesystemPackageCacheManager`* – exact parity with the validator, but it
  ties the framework-free core to the validator's library and its dependency tree (ADR-0006).
* *Leave the dependencies to the validator* – it downloads them unverified, and only when a validation runs, so a
  missing package shows as a validator start-up failure in the middle of a run instead of a configuration problem
  before it.
* *The `HttpTransport` of the FHIR server for downloads* – that port is for exchanges with the server under test: it
  writes the communication log, carries the server's headers and tokens, and an application may have replaced it with
  one that only reaches that server.
* *Proxy and TLS as runner settings* – the JDK reads the standard system properties already, for the FHIR server's
  client and for the downloads alike; a second set of settings could disagree with them.
* *SHA-512 `integrity`* – packages.fhir.org lists only `shasum`.

## Consequences
* A suite can run the CI build of an IG (`package-id: hl7.at.fhir.core.r4#current`) or a branch of it
  (`#current$my-branch`).
* A run with validation downloads the whole dependency tree before it starts: for AT Core 2.0.0 two more packages
  (6 MB), once; afterwards the cache serves them. A package the registry does not have stops the run with exit code
  2, naming the package that depends on it.
* The validator still resolves what the runner passed as written, and the packages a non-package suite names in
  `igs`.
* `PackageSuites.resolve` has a second parameter, and `PackagesConfig` a third component (`ciBuild`; the factory
  `PackagesConfig.cache(dir)` covers the common case). Both are internal or configuration API, see CHANGELOG.

# ADR-0020: Published images – the runner with and without the validator, and thin wrappers for GitHub and GitLab

## Context
[ADR-0015](0015-container-image.md) gave the runner a container image, built in CI and not published, and left "where,
under which name and with which tags" open. Now other projects want to run their TestScripts in their own GitHub and
GitLab pipelines with one step: *this configuration, against that server, fail the job if a test fails*. For that the
image has to be pullable, versioned, and usable without a checkout of this repository. ADR-0015 kept the validator out
of the image; a pipeline that wants profile validation would have to download 200 MB in every job.

## Decision
* **Two images from one Dockerfile**, published to the GitHub Container Registry as
  `ghcr.io/mratzenb/fhir-testrunner` (public):
  * `X.Y.Z` – the runner only, as in ADR-0015. Configuration, suites, results, an own validator jar and the package
    cache are mounted.
  * `X.Y.Z-validator` – the same, plus the HL7 validator (`validator_cli.jar`) at `/opt/validator`, `VALIDATOR_JAR`
    pointing to it, so the configurations of this repository validate without a mount. The jar is downloaded when the
    image is built, from the release named by `ARG VALIDATOR_VERSION` and **verified against `ARG VALIDATOR_SHA256`**;
    the validator's Apache-2.0 license text is delivered next to it. The pin is the one of `build.yml` (7.0.2).
  * Dockerfile targets: `base` (shared), `validator`, and `slim`, the last stage, so a plain `docker build` still
    builds the runner only.
* **Tags** (`.github/workflows/image.yml`): a git tag `vX.Y.Z` gives `X.Y.Z`, `X.Y` and `latest`; for 0.x versions the
  major-only tag is not made; a push to `main` gives `edge`; every build also `sha-<commit>`. The validator image has the
  same tags with the suffix `-validator`. Pull requests build and test both images for amd64 and push nothing. Published
  images are `linux/amd64` and `linux/arm64` (a JRE image: cheap to build for both; the jar is the same).
  The **git tag names the image**, not the POM version; the POM stays `0.1.0-SNAPSHOT` until a release is decided.
* **A launcher, `testrunner`, on the `PATH`.** It is the entry point and starts the runner through the base image's
  certificate entry point. A pipeline that replaces the entry point (GitLab CI needs `entrypoint: [""]`) calls
  `testrunner …` and still gets `USE_SYSTEM_CA_CERTS` and `/certificates`.
* **Thin wrappers, versioned with the image, no logic of their own:**
  * a composite GitHub Action, `action.yml` in the repository root (`uses: mratzenb/FHIR-TestRunner@vX.Y.Z`): it
    runs the image of the same version with `docker run`, keeps the package cache, uploads the results and sets
    `exit-code` and `results-path`;
  * a GitLab CI template, `ci/testrunner.gitlab-ci.yml`, with a hidden job `.testrunner` to `extends:`.
* **Java 25 LTS** stays the base (`eclipse-temurin:25.0.5_8-jre-noble`). Dependabot ignores major versions of
  `eclipse-temurin`; the build targets `release 25`, so a newer Java is a decision of its own. (Dependabot had raised
  both `FROM` lines to Java 26; this reverts that.)
* **The license files travel with the artifacts.** `META-INF/LICENSE` is in every jar (a resource of the root POM).
  The executable jar also holds `META-INF/THIRD-PARTY-NOTICES.txt` – every library it ships with license, project page
  and the Maven Central folder of its sources, written at build time by `license-maven-plugin:add-third-party` – and
  `META-INF/licenses/` with the texts of Apache-2.0, EPL-2.0 and Unicode-3.0. Both images carry them in `/licenses`;
  the validator image adds `HL7-VALIDATOR.txt` next to the validator's own `LICENSE.txt`. The libraries stay unchanged
  jars of their own, with the notice files they carry. This is what the licenses of what we ship ask for: the MPL
  (Saxon-HE) to tell recipients how to get the source, the EPL (Logback, Jakarta Annotations) the same and a copy of
  the license, the Apache license a copy of the license. Dual-licensed libraries are used under their EPL option, and
  the notices say so.
* **License: Mozilla Public License 2.0** (`LICENSE`, `<licenses>` in the POM, the image labels). The validator image
  carries `MPL-2.0 AND Apache-2.0`. The MPL is a file-level copyleft: changed files stay under it, a larger work that
  combines them with other code can have other terms. The dependencies of the executable jar are Apache-2.0, MIT, BSD,
  EPL-2.0 (Logback is also LGPL, `jakarta.annotation` also GPL with the classpath exception) and MPL-2.0 (Saxon); the
  SBOM lists them.

## Alternatives
* *Only the slim image, the validator downloaded in the pipeline* – 200 MB and a network dependency in every job, a
  validator version nobody pinned. Kept as the slim image: it is right where the validator is mounted or not needed.
* *Only an image with the validator* – 200 MB more for everyone who validates nothing, or validates with another
  version.
* *The validator version as a tag dimension* (`X.Y.Z-validator-7.0.2`) – more tags to keep for little gain: the
  validator is pinned per runner release, and raising it is a new runner release.
* *A Docker container action* (`runs: docker`) – no cache step and no way to choose the user; a composite action
  around `docker run` can do both.
* *The action in a repository of its own* – the action and the image would have two versions to keep in step. In one
  repository the tag `vX.Y.Z` is both.
* *Docker Hub or the GitLab registry as well* – GHCR is public and anonymous to pull from GitLab. Add a mirror when
  someone needs one.

## Consequences
* The validator version is in two places: `VALIDATOR_VERSION` in `build.yml` (the nightly `RealValidatorIT`, now also
  in every pull request) and the `ARG`s of the Dockerfile (version and checksum). Raise both, see
  [Build and test](../wiki/build-and-test.md#raising-the-validator-version).
* A new validator is a new runner release. The image cannot say "latest validator", on purpose.
* The validator image is 200 MB larger than the slim one (635 MB against 431 MB).
* The publishing workflow runs `mvn verify` before it pushes, and smoke-tests both images – the validator image
  against a HAPI FHIR server, including the negative control, which must fail.
* The GitHub Action is tested in this repository's CI (`uses: ./` against a HAPI service). The GitLab template cannot
  be run there; what it does is tried with the same container invocation, and its first use on a real GitLab runner
  is still to be checked, see [CI integration](../wiki/ci-integration.md#gitlab-ci).
* `license-maven-plugin` is a build dependency (version property in the root POM, Dependabot group `maven-plugins`).
  It fails the build for a library without license information; a license name nobody merged appears in the notices
  as the library's POM spells it.
* The MPL's notice (its Exhibit A) is not in every source file; `LICENSE` in the root is the location the MPL allows
  when a notice in each file is not wanted. Adding it to the files is a decision of its own.
* ADR-0015 is amended: the image *is* published, and there *is* an image with the validator.

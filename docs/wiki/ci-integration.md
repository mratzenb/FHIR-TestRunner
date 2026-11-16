# Running the runner in a GitHub or GitLab pipeline

[Home](README.md) › CI integration

The runner is published as a container image, so a pipeline of another project can run its TestScripts with one step:
*this configuration, against that server, fail the job if a test fails*. Decisions and alternatives:
[ADR-0020](../adr/0020-published-images.md).

## The images

`ghcr.io/mratzenb/fhir-testrunner`, public, for `linux/amd64` and `linux/arm64`:

| Tag | Holds | Use it when |
|---|---|---|
| `X.Y.Z` | the runner on a Java 25 JRE | the configuration does not validate profiles (`--novalidator`), or you bring a validator jar of your own |
| `X.Y.Z-validator` | the same, plus the HL7 validator at `/opt/validator/validator_cli.jar` (`VALIDATOR_JAR` points to it) | the configurations of this repository, or yours with `validator-jar: ${VALIDATOR_JAR}`; nothing else to download |

`X.Y` and `latest` follow the newest release of their line, `edge` the `main` branch, `sha-<commit>` one build; each also
with the suffix `-validator`. **Pin `X.Y.Z`** (or the digest, `@sha256:…`) in a pipeline: `latest` changes under you.
The validator is pinned per runner release (now 7.0.2); a new validator comes with a new runner release.

Until the first release tag exists, `edge` and `edge-validator` are the only tags (and the Action is used as `@main`).

What every image expects (the details of the mounts are in [Build and test › Container image](build-and-test.md#container-image)):

* **The project folder at `/workspace`**, which is the working directory: the configuration, the suites and the
  results. Relative paths in a configuration start there.
* **Any user id**: `--user "$(id -u):$(id -g)"` makes the results belong to the job.
* **The FHIR server by an address the container can reach.** `localhost` inside a container is the container. With
  `docker run --network host` (Linux) it is the runner machine again; that is what the GitHub Action does.
* **Secrets and settings as environment variables**: every `TESTRUNNER_*` variable is a property of the runner
  (`TESTRUNNER_FHIRSERVER_BASEURL=…`, [configuration.md](../configuration.md#running)), and a
  configuration can read `${OAUTH_CLIENT_SECRET}`. Keep secrets in the secret store of the CI system, never in a file.
* **The package cache** (`~/.fhir/packages`, `/home/runner/.fhir/packages` in the image) is worth keeping between
  jobs: the validator and `package-id` suites download FHIR packages into it.
* **Company certificates**: mount them at `/certificates` and set `USE_SYSTEM_CA_CERTS=1`.
* **Exit codes**: 0 all passed, 1 a TestScript failed or errored, 2 invalid configuration, 3 every TestScript skipped.
* **Results** in `<output-dir>/run_<timestamp>/`: `junit.xml`, `summary.txt`, `result.json`, `report.html`,
  `testreports/`, `testrunner.log`, `communication/` (credential headers masked).

## GitHub Actions

The Action `mratzenb/FHIR-TestRunner` ([`action.yml`](../../action.yml)) is a wrapper around `docker run`. It needs a
Linux runner with Docker (`ubuntu-latest`). `@v0.1.0` runs the image `0.1.0`, the Action and the image share the version.

```yaml
name: fhir-tests
on: [pull_request]

jobs:
  testscripts:
    runs-on: ubuntu-latest
    services:
      hapi:                                     # the FHIR server under test; use your own if you have one
        image: hapiproject/hapi:v8.12.0-1
        ports: ["8080:8080"]
        env:
          hapi.fhir.enforce_referential_integrity_on_write: "false"
          hapi.fhir.enforce_referential_integrity_on_delete: "false"
          hapi.fhir.allow_multiple_delete: "true"
    steps:
      - uses: actions/checkout@v7
      - name: Wait for the server               # HAPI's image has no shell for a health check
        run: curl --silent --fail --output /dev/null --retry 60 --retry-delay 3 --retry-all-errors http://localhost:8080/fhir/metadata
      - uses: mratzenb/FHIR-TestRunner@v0.1.0
        with:
          config: config/dev-r4.yml             # relative to the checkout
          validator: true                       # the image with the HL7 validator
```

The step fails when the runner's exit code is not 0. It writes `summary.txt` into the job summary, uploads the results
folder as the artifact `testrunner-results` (also after a failure) and keeps the package cache. Show the test results
in the pull request with a JUnit reporter of your choice on `${{ steps.<id>.outputs.results-path }}/junit.xml`.

| Input | Default | |
|---|---|---|
| `config` | – (required) | the runner configuration, relative to `working-directory` |
| `working-directory` | `.` | the folder mounted at `/workspace` |
| `validator` | `false` | `true`: the `-validator` image |
| `version` | the version of the Action (`@v0.1.0`: `0.1.0`; a branch: `edge`) | the image tag, without `-validator`. A commit sha as the reference needs `version` |
| `image` | | the complete image reference: a mirror, or an image you built; replaces `version` and `validator` |
| `testscript`, `test` | | only these TestScripts / tests (globs, [`--testscript` and `--test`](../configuration.md#selecting-what-runs)) |
| `args` | | more runner arguments, separated by blanks (no quoting), e.g. `--check --novalidator` |
| `forward-env` | | names of environment variables handed to the container (`TESTRUNNER_*` always are) |
| `network` | `host` | the Docker network of the container |
| `docker-args` | | more arguments for `docker run`, separated by blanks |
| `results-dir` | `testrunner-results` | where the reports go, relative to `working-directory`; replaces `reporting.output-dir` of the configuration |
| `upload-results` / `artifact-name` | `true` / `testrunner-results` | the artifact; give each use in a job a name of its own |
| `cache-packages` | `true` | keep `~/.fhir/packages`; the key follows the configuration file |
| `fail-on-error` | `true` | `false`: only report the exit code |

Outputs: `exit-code` and `results-path` (the folder this run wrote).

A secret, and a run that must fail (the negative control), as examples:

```yaml
      - uses: mratzenb/FHIR-TestRunner@v0.1.0
        env:
          OAUTH_CLIENT_SECRET: ${{ secrets.FHIR_CLIENT_SECRET }}
        with:
          config: config/at-core.yml
          forward-env: OAUTH_CLIENT_SECRET
      - id: control
        uses: mratzenb/FHIR-TestRunner@v0.1.0
        with:
          config: config/negative-control.yml
          validator: true
          fail-on-error: false
          artifact-name: negative-control
      - run: test "${{ steps.control.outputs.exit-code }}" = 1
```

**Without the Action** – for a runner without Docker access in the steps, or a `container:` job – use the image
directly:

```yaml
      - run: |
          docker run --rm --network host --user "$(id -u):$(id -g)" -v "$PWD:/workspace" \
            ghcr.io/mratzenb/fhir-testrunner:0.1.0-validator --config=config/dev-r4.yml
```

As the container of a job (`container: ghcr.io/mratzenb/fhir-testrunner:0.1.0-validator`) the workspace belongs to
GitHub's own user, so the image's `runner` (10001) may not be able to write the results: give the job
`options: --user root`, and call `testrunner --config=…` in a step (the image's entry point is not used there).

## GitLab CI

[`ci/testrunner.gitlab-ci.yml`](../../ci/testrunner.gitlab-ci.yml) has a hidden job `.testrunner`. Include the file of a
tag, and extend the job:

```yaml
include:
  - remote: https://raw.githubusercontent.com/mratzenb/FHIR-TestRunner/v0.1.0/ci/testrunner.gitlab-ci.yml

at-core:
  extends: .testrunner
  services:                                     # the FHIR server under test; use your own if you have one
    - name: hapiproject/hapi:v8.12.0-1
      alias: hapi
      command:                                  # the settings of docker-compose.yml, as arguments
        - --hapi.fhir.enforce_referential_integrity_on_write=false
        - --hapi.fhir.enforce_referential_integrity_on_delete=false
        - --hapi.fhir.allow_multiple_delete=true
  variables:
    FHIR_TESTRUNNER_VERSION: "0.1.0-validator"  # the image tag
    FHIR_TESTRUNNER_CONFIG: config/dev-r4.yml   # relative to the project folder
    FHIR_TESTRUNNER_WAIT_FOR: hapi:8080         # wait until the server accepts connections
    TESTRUNNER_FHIRSERVER_BASEURL: http://hapi:8080/fhir
```

The job collects `testrunner-results/` as artifacts (also when it fails), shows `junit.xml` in the pipeline and merge
request views, and keeps the FHIR packages in the project's cache.

| Variable | Default | |
|---|---|---|
| `FHIR_TESTRUNNER_IMAGE` | `ghcr.io/mratzenb/fhir-testrunner` | the image without the tag |
| `FHIR_TESTRUNNER_VERSION` | `latest` | the tag, `0.1.0` or `0.1.0-validator`: **pin it** |
| `FHIR_TESTRUNNER_CONFIG` | – (required) | the runner configuration |
| `FHIR_TESTRUNNER_ARGS` | | more runner arguments, e.g. `--testscript=*update* --novalidator`; not quoted, so no spaces inside one |
| `FHIR_TESTRUNNER_WAIT_FOR` | | `host:port` to wait for, up to two minutes, before the runner starts |

They are not called `TESTRUNNER_*` on purpose: the runner reads every variable of that name as one of its properties.
Those you set on the job (`TESTRUNNER_FHIRSERVER_BASEURL`, `TESTRUNNER_VALIDATION_MODE`, …) configure the runner.
Secrets belong in masked CI/CD variables of the project, e.g. `OAUTH_CLIENT_SECRET` for a configuration that reads
`${OAUTH_CLIENT_SECRET}`.

Things the template decides for you, and how to undo it:

* **`entrypoint: [""]`**: GitLab starts the image with the job's script as arguments of its entry point, which would
  be the runner itself. The script calls `testrunner`, a launcher in the image, which still honors
  `USE_SYSTEM_CA_CERTS`.
* **The job runs as root** (`image:docker:user: "0"`): the Docker executor clones the project as root, so the image's
  user 10001 could not write the results. The job container is thrown away. If your runner's project folder is
  writable for 10001, override it: `image: {docker: {user: "10001"}}`. The keyword needs a GitLab and runner that
  know `image:docker:user`.
* **The package cache is in the project folder** (`.fhir-cache/`), because GitLab caches only folders of the project:
  the validator's JVM gets `-Duser.home` there, and `packages.cache-dir` follows it.
* **Exit code 3** (every TestScript skipped) fails the job like the others. To warn instead:
  `allow_failure: {exit_codes: 3}`.

Without the template, the job is:

```yaml
at-core:
  image:
    name: ghcr.io/mratzenb/fhir-testrunner:0.1.0-validator
    entrypoint: [""]
    docker: {user: "0"}
  script:
    - testrunner --config=config/dev-r4.yml
  artifacts:
    when: always
    paths: [testrunner-results/]
    reports:
      junit: testrunner-results/run_*/junit.xml
```

## Problems

| Symptom | Cause | Fix |
|---|---|---|
| `Cannot create the result folder …: AccessDeniedException` or `NoSuchFileException`, exit code 2 | the container user may not write the mounted folder | `docker run --user "$(id -u):$(id -g)"`; in a GitHub container job `options: --user root`; in GitLab keep `docker: {user: "0"}` |
| the runner cannot connect to `localhost:8080` | `localhost` is the container | GitHub Action: `network: host` (the default, Linux); `docker run --network host`; or use the service name (`http://hapi:8080/fhir`) with `TESTRUNNER_FHIRSERVER_BASEURL` |
| the first request fails with connection refused | the FHIR server is still starting | wait for it: a `curl --retry` step (GitHub), `FHIR_TESTRUNNER_WAIT_FOR` (GitLab) |
| `Cannot tell the image version from the action reference '<sha>'` | the Action is pinned to a commit | give `version: 0.1.0` (or `image:`) |
| `denied` / `manifest unknown` pulling the image | the package is private (a new package is created private), or the tag does not exist | make the package public once (Package settings › Change visibility); check the tag |
| profile validation: the validator jar is not found | the slim image, with `validator-jar` pointing to a file that is not there | use the `-validator` image, or mount a jar |
| a green run that validated nothing | `--novalidator`, or a configuration with `validation.enabled: false` | run the negative control ([validation.md](../validation.md#the-negative-control)) in the same pipeline: it must **fail** |

## Releasing (maintainers)

1. `main` is green, including `real-validator` ([Build and test › Continuous integration](build-and-test.md#continuous-integration)).
2. Tag it: `git tag v0.1.0 && git push origin v0.1.0`. [`image.yml`](../../.github/workflows/image.yml) runs
   `mvn verify`, builds both images, smoke-tests them (`ci/smoke-test-image.sh`: AT Core with validation against HAPI,
   the negative control must fail, the Action itself), and pushes `0.1.0`, `0.1`, `latest` and the `-validator` set.
   The tag, not the POM version, names the images. The tag is also the version of the Action and of the GitLab
   template: `@v0.1.0`, `…/v0.1.0/ci/testrunner.gitlab-ci.yml`.
3. The first push creates the package private: *Package settings › Change visibility › Public*, once.
4. Every push to `main` publishes `edge` and `edge-validator`.

Raising the validator, HAPI FHIR or Java for the images: [Build and test](build-and-test.md#container-image).

## What is tested, what is not

The pull request build builds both images and runs the smoke test and the Action against them (amd64). The first
publication, the arm64 images and the Action as `uses: mratzenb/FHIR-TestRunner@<tag>` from another repository run
for the first time on a release. The GitLab template is not run on GitLab by CI: the same script was run with `docker`
against HAPI FHIR with the entry point replaced, as user 0, with the variables GitLab sets – the first use on a real
GitLab runner is the test of the executor-specific parts (the user, the cache, the junit report).

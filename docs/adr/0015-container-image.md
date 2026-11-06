# ADR-0015: Container image – the runner only, everything else mounted

## Context
The Python proof of concept ships a Dockerfile and a compose file with HAPI FHIR; the Java runner had neither. A CI
job of an IG, or a tester without Java 25, wants to run the runner with one command, and next to a FHIR server that
is started for the test (feature F4 of [next steps](../wiki/next-steps.md#features)).

## Decision
* **A Dockerfile in `testrunner-app`** on `eclipse-temurin:25.0.5_8-jre-noble` (pinned to the patch release), built
  from the executable jar of `mvn package`. Not `spring-boot:build-image`: buildpacks pull their builder images from
  the internet when the build runs, which a Maven build behind the Artifactory mirror cannot rely on, and a
  Dockerfile is plain to read and to change.
* **Layers:** the jar is split with Spring Boot's `jarmode=tools extract --layers`. The 93 MB of libraries are one
  layer, the runner's own classes another of a few hundred KB, so a new build of the runner changes only that.
* **The image holds the runner only.** Configuration, suites, results, the validator jar (200 MB, its own release
  cycle) and the FHIR package cache are mounted. The working directory is `/workspace`, so a project folder mounted
  there works with the configurations as they are (`config/dev-r4.yml`, relative `base-dir` and `output-dir`).
* **Not root.** User `runner` (10001); its home and `/workspace` are writable for any user id, so
  `docker run --user $(id -u)` writes results owned by the caller, and `~/.fhir/packages` works for every user.
* **Company certificates** through the base image's entry point: `USE_SYSTEM_CA_CERTS=1` and certificates mounted at
  `/certificates` – no rebuild for a FHIR server behind a company CA.
* **`docker-compose.yml`** at the project root: the runner and HAPI FHIR `v8.12.0-1` (the release of `HapiServerIT`)
  with the same lenient settings as there. HAPI's image has no shell for a health check, so a `curl` service waits
  until `/fhir/metadata` answers; the runner starts after it.
* **CI builds the image and checks the AT Core suite with it** (`--check`, no server needed). The image is not
  published. Where, under which name and with which tags is a decision of its own, once someone needs a published
  image.

## Alternatives
* *`spring-boot:build-image` (buildpacks)* – no Dockerfile, but a network dependency at build time, a large builder
  image, and its options are less known than a Dockerfile.
* *The validator in the image* – 200 MB more and an image per validator release; the validator version belongs to the
  user, see [validation.md](../validation.md).
* *A distroless or Alpine base* – smaller, but the base image's certificate entry point needs a shell, and Temurin is
  the JRE the build is tested with.

## Consequences
* `mvn -B -DskipTests package && docker build -t testrunner testrunner-app` builds the image (431 MB, of which the JRE
  and the libraries are most).
* The image follows the jar: building it needs `mvn package` first. A Dockerfile that runs Maven itself would need the
  Artifactory settings inside the build.
* Raising the HAPI release means three places: `hapi-fhir.version`, `HapiServerIT.IMAGE` and `docker-compose.yml`.

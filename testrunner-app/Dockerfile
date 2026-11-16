# Container images of the FHIR TestScript Runner: the executable jar on a Java 25 JRE (ADR-0015, ADR-0020).
#
#   mvn -B -DskipTests package                                      # builds target/testrunner-app-<version>-exec.jar
#   docker build -t testrunner testrunner-app                       # the runner only (target "slim", the default)
#   docker build -t testrunner:validator --target validator testrunner-app   # the runner and the HL7 validator
#   docker run --rm -v "$PWD:/workspace" testrunner --config=config/dev-r4.yml --novalidator
#
# The slim image holds the runner only. The configuration, the suites, the results, the validator jar and the FHIR
# package cache are mounted: docs/wiki/build-and-test.md#container-image. The validator image adds a pinned
# validator_cli.jar (VALIDATOR_VERSION, checksum VALIDATOR_SHA256) at /opt/validator and sets VALIDATOR_JAR to it.
# Raise the validator together with VALIDATOR_VERSION in .github/workflows/build.yml.

# the exec jar split into its layers: a new build of the runner changes only the small application layer
FROM eclipse-temurin:25.0.5_8-jre-noble AS layers
WORKDIR /layers
COPY target/testrunner-app-*-exec.jar testrunner.jar
RUN java -Djarmode=tools -jar testrunner.jar extract --layers --destination extracted

FROM eclipse-temurin:25.0.5_8-jre-noble AS base
LABEL org.opencontainers.image.title="FHIR TestScript Runner" \
      org.opencontainers.image.description="Executes FHIR TestScripts (R4, R5) against a FHIR server" \
      org.opencontainers.image.source="https://github.com/mratzenb/FHIR-TestRunner" \
      org.opencontainers.image.licenses="BSD-3-Clause"
# not root; the home and the working directory are writable for any user id (docker run --user), so the FHIR package
# cache in ~/.fhir/packages and relative result folders work whoever the container runs as
RUN groupadd --gid 10001 runner \
    && useradd --uid 10001 --gid runner --home-dir /home/runner --create-home --shell /usr/sbin/nologin runner \
    && mkdir -p /home/runner/.fhir/packages /workspace \
    && chmod 1777 /home/runner /home/runner/.fhir /home/runner/.fhir/packages /workspace
ENV HOME=/home/runner
WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
COPY --chmod=0755 docker/testrunner /usr/local/bin/testrunner
USER runner
# relative paths of a configuration (suites, output-dir, validator-jar) start here: mount the project folder
WORKDIR /workspace
# a launcher script, so that a pipeline which replaces the entry point can still call "testrunner"; it goes through the
# base image's entry point, see docker/testrunner
ENTRYPOINT ["/usr/local/bin/testrunner"]

# the runner and the HL7 validator, for a pipeline that should need nothing else: docker build --target validator
FROM base AS validator
ARG VALIDATOR_VERSION=7.0.2
ARG VALIDATOR_SHA256=e3e50f06b806437821b7aef8189a1b790415cb96c5036e0812ed62981452b377
# a folder that ADD creates gets the mode of --chmod, so create it first; files added from a URL are readable by root
# only, and the validator runs as the user of the container
USER root
RUN mkdir /opt/validator
USER runner
ADD --chmod=0644 --checksum=sha256:${VALIDATOR_SHA256} \
    https://github.com/hapifhir/org.hl7.fhir.core/releases/download/${VALIDATOR_VERSION}/validator_cli.jar \
    /opt/validator/validator_cli.jar
# the validator is Apache-2.0 licensed; a binary redistribution has to carry the license text
ADD --chmod=0644 https://raw.githubusercontent.com/hapifhir/org.hl7.fhir.core/${VALIDATOR_VERSION}/LICENSE.txt \
    /opt/validator/LICENSE.txt
ENV VALIDATOR_JAR=/opt/validator/validator_cli.jar
LABEL org.opencontainers.image.title="FHIR TestScript Runner with the HL7 validator" \
      org.opencontainers.image.description="Executes FHIR TestScripts (R4, R5) against a FHIR server; includes the HL7 validator ${VALIDATOR_VERSION} for profile validation" \
      org.opencontainers.image.licenses="BSD-3-Clause AND Apache-2.0" \
      at.hl7.testing.runner.validator.version="${VALIDATOR_VERSION}"

# the default target: the runner only
FROM base AS slim

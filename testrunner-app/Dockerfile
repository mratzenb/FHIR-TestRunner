# Container image of the FHIR TestScript Runner: the executable jar on a Java 25 JRE (ADR-0015).
#
#   mvn -B -DskipTests package                  # builds target/testrunner-app-<version>-exec.jar
#   docker build -t testrunner testrunner-app   # this file; the context is this folder, see .dockerignore
#   docker run --rm -v "$PWD:/workspace" testrunner --config=config/dev-r4.yml --novalidator
#
# The image holds the runner only. The configuration, the suites, the results, the validator jar and the FHIR package
# cache are mounted: docs/wiki/build-and-test.md#container-image.

# the exec jar split into its layers: a new build of the runner changes only the small application layer
FROM eclipse-temurin:25.0.5_8-jre-noble AS layers
WORKDIR /layers
COPY target/testrunner-app-*-exec.jar testrunner.jar
RUN java -Djarmode=tools -jar testrunner.jar extract --layers --destination extracted

FROM eclipse-temurin:25.0.5_8-jre-noble
LABEL org.opencontainers.image.title="FHIR TestScript Runner" \
      org.opencontainers.image.description="Executes FHIR TestScripts (R4, R5) against a FHIR server" \
      org.opencontainers.image.source="https://github.com/mratzenb/testrunner"
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
USER runner
# relative paths of a configuration (suites, output-dir, validator-jar) start here: mount the project folder
WORKDIR /workspace
# the base image's entry point adds the certificates mounted at /certificates to the trust store if
# USE_SYSTEM_CA_CERTS is set, e.g. for a FHIR server whose certificate a company CA signed
ENTRYPOINT ["/__cacert_entrypoint.sh", "java", "-jar", "/app/testrunner.jar"]

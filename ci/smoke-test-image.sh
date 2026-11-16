#!/bin/sh
# Smoke test of a built runner image (ADR-0020), in CI before an image is pushed and by hand:
#
#   mvn -B -DskipTests package && docker build -t testrunner testrunner-app
#   ci/smoke-test-image.sh testrunner slim
#   docker build -t testrunner:validator --target validator testrunner-app
#   ci/smoke-test-image.sh testrunner:validator validator
#
# Both variants: the image checks the AT Core suite as a user id that is neither root nor the owner of the files (the
# project folder is a Docker volume, filled from this repository), and also when a pipeline replaces the entry point. The slim image must not hold a validator. The validator image runs
# the AT Core R4 suite with profile validation against a HAPI FHIR server it starts (release of docker-compose.yml;
# needs the internet for the validator's packages), which must pass, and the negative control, which must FAIL: that
# proves the validator ran.
set -eu

image=${1:?usage: smoke-test-image.sh <image> slim|validator}
variant=${2:?usage: smoke-test-image.sh <image> slim|validator}
case "$variant" in slim | validator) ;; *) echo "variant: slim or validator, not $variant" >&2; exit 2 ;; esac

repo=$(cd "$(dirname "$0")/.." && pwd)
user="12345:12345"
volume="testrunner-smoke-workspace-$$"
net="testrunner-smoke-$$"
hapi="testrunner-smoke-hapi-$$"
hapi_image="hapiproject/hapi:$(sed -n 's/^ *image: hapiproject\/hapi:\(.*\)$/\1/p' "$repo/docker-compose.yml")"

cleanup() {
    docker rm -f "$hapi" >/dev/null 2>&1 || true
    docker network rm "$net" >/dev/null 2>&1 || true
    docker volume rm "$volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# a volume, not a bind mount: the bind mounts of Docker on macOS do not keep the owner of a file a container user creates
docker volume create "$volume" >/dev/null
tar -C "$repo" -cf - config testdata | docker run --rm -i --user root -v "$volume:/workspace" --entrypoint "" "$image" \
    sh -c 'tar -xf - -C /workspace && chmod -R a+rwX /workspace'

# expect <exit code> <description> <command…>: the command must end with that exit code; its output is shown if not
expect() {
    want=$1 what=$2
    shift 2
    log=$(mktemp)
    set +e
    "$@" >"$log" 2>&1
    got=$?
    set -e
    if [ "$got" -ne "$want" ]; then
        cat "$log"
        rm -f "$log"
        echo "FAILED: $what: exit code $got, expected $want" >&2
        exit 1
    fi
    echo "ok: $what (exit code $got)"
    grep -E 'Summary' "$log" || true
    rm -f "$log"
}

mounted="-v $volume:/workspace"
# shellcheck disable=SC2086 # $mounted is two words on purpose
expect 0 "check the AT Core suite as user $user" \
    docker run --rm --user "$user" $mounted "$image" --config=config/dev-r4.yml --novalidator --check
# shellcheck disable=SC2086
expect 0 "check with the entry point replaced (GitLab CI), through the testrunner launcher" \
    docker run --rm --user "$user" $mounted --entrypoint "" "$image" \
    sh -c 'testrunner --config=config/dev-r4.yml --novalidator --check'

if [ "$variant" = slim ]; then
    # shellcheck disable=SC2016 # the variable is the container's
    expect 0 "the slim image holds no validator" \
        docker run --rm --entrypoint "" "$image" sh -c 'test ! -e /opt/validator/validator_cli.jar && test -z "${VALIDATOR_JAR:-}"'
    exit 0
fi

# shellcheck disable=SC2016 # the variable is the container's
expect 0 "the validator image has the jar and its license" \
    docker run --rm --user "$user" --entrypoint "" "$image" \
    sh -c 'test -r "$VALIDATOR_JAR" && test -r /opt/validator/LICENSE.txt'

docker network create "$net" >/dev/null
docker run -d --name "$hapi" --network "$net" -p 127.0.0.1::8080 \
    -e hapi.fhir.fhir_version=R4 \
    -e hapi.fhir.enforce_referential_integrity_on_write=false \
    -e hapi.fhir.enforce_referential_integrity_on_delete=false \
    -e hapi.fhir.allow_multiple_delete=true \
    "$hapi_image" >/dev/null
port=$(docker port "$hapi" 8080/tcp | head -n 1 | sed 's/.*://')
echo "waiting for HAPI FHIR ($hapi_image) on port $port"
tries=0
until curl -fsS -o /dev/null "http://127.0.0.1:$port/fhir/metadata" 2>/dev/null; do
    tries=$((tries + 1))
    if [ "$tries" -gt 90 ]; then
        docker logs "$hapi" | tail -n 30
        echo "FAILED: HAPI FHIR did not answer within 3 minutes" >&2
        exit 1
    fi
    sleep 2
done

# shellcheck disable=SC2086
expect 0 "AT Core R4 with profile validation passes" \
    docker run --rm --user "$user" $mounted --network "$net" -e "TESTRUNNER_FHIRSERVER_BASEURL=http://$hapi:8080/fhir" \
    "$image" --config=config/dev-r4.yml
# shellcheck disable=SC2086
expect 1 "the negative control fails (so the validator ran)" \
    docker run --rm --user "$user" $mounted --network "$net" -e "TESTRUNNER_FHIRSERVER_BASEURL=http://$hapi:8080/fhir" \
    "$image" --config=config/negative-control.yml

#!/bin/bash
# Copyright 2020-2025 NetCracker Technology Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Shared settings and helpers for the consul app benchmark scripts.
# The scripts that source this file use its variables.
# shellcheck disable=SC2034

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$BENCH_DIR/../.." && pwd)"

# Git Bash rewrites container paths such as /tmp/bench into Windows paths without this.
export MSYS_NO_PATHCONV=1
export COMPOSE_PROJECT_NAME=consul-app-benchmark

# The container engine CLI: docker or podman. Both take the same commands and flags that the scripts use.
CONTAINER_ENGINE="${CONTAINER_ENGINE:-docker}"

# The Consul HTTP API as seen from inside the Consul container, where consul_curl runs.
CONSUL_HTTP="http://127.0.0.1:8500"
CONSUL_CONTAINER=consul-app-bench-consul
APP_CONTAINER=consul-app-bench-app
NAMESPACE=local
MICROSERVICE_NAME=qubership-nifi

# Prints a host path in the form the container engine CLI accepts; on Git Bash that is a Windows path.
host_path() {
    if command -v cygpath > /dev/null 2>&1; then
        cygpath -m "$1"
    else
        echo "$1"
    fi
}

log() {
    printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2
}

die() {
    log "ERROR: $*"
    exit 1
}

# Prints the base image reference pinned in the repository Dockerfile.
base_image() {
    local version sha
    version=$(sed -n "s/^ARG BASE_IMAGE_VERSION='\(.*\)'$/\1/p" "$REPO_ROOT/Dockerfile")
    sha=$(sed -n "s/^ARG BASE_IMAGE_VERSION_SHA256='\(.*\)'$/\1/p" "$REPO_ROOT/Dockerfile")
    [ -n "$version" ] && [ -n "$sha" ] || die "Cannot read BASE_IMAGE_VERSION from $REPO_ROOT/Dockerfile"
    echo "ghcr.io/netcracker/qubership-java-base:${version}@${sha}"
}

check_framework() {
    case "$1" in
        spring | quarkus) ;;
        *) die "Unknown framework '$1', expected spring or quarkus" ;;
    esac
}

# Spring expects host:port, Quarkus expects a URL with a scheme.
consul_url_for() {
    if [ "$1" = quarkus ]; then
        echo "http://consul:8500"
    else
        echo "consul:8500"
    fi
}

# Extended regular expression that matches the startup line each framework logs.
startup_line_regex() {
    if [ "$1" = quarkus ]; then
        echo 'started in [0-9.]+s'
    else
        echo 'Started NifiPropertiesLookup in [0-9.]+ seconds'
    fi
}

# Reads a profile file, drops comments and blank lines, and prints the options on one line.
read_opts() {
    [ -f "$1" ] || die "Profile file not found: $1"
    sed -e 's/#.*$//' -e '/^[[:space:]]*$/d' "$1" | tr '\n' ' ' | sed -e 's/[[:space:]]\+/ /g' -e 's/ $//'
}

# Runs curl inside the Consul container and passes stdin through. The host needs no published port, because
# Podman on WSL does not forward one to Windows in every setup.
consul_curl() {
    local token_header=()
    if [ -n "${CONSUL_ACL_TOKEN:-}" ]; then
        token_header=(--header "X-Consul-Token: $CONSUL_ACL_TOKEN")
    fi
    engine exec -i "$CONSUL_CONTAINER" curl -sS --fail "${token_header[@]}" "$@"
}

wait_for_consul() {
    for _ in $(seq 1 60); do
        if consul_curl "$CONSUL_HTTP/v1/status/leader" 2>/dev/null | grep -q ':'; then
            return 0
        fi
        sleep 1
    done
    die "Consul did not elect a leader within 60 seconds"
}

# Runs the container engine CLI.
engine() {
    "$CONTAINER_ENGINE" "$@"
}

# podman compose runs an external provider, docker-compose or podman-compose, against the Podman service.
compose() {
    engine compose -f "$(host_path "$BENCH_DIR/docker-compose.yml")" "$@"
}

# Runs a command inside the app container.
app_exec() {
    engine exec "$APP_CONTAINER" "$@"
}

app_running() {
    [ "$(engine inspect -f '{{.State.Running}}' "$APP_CONTAINER" 2>/dev/null)" = true ]
}

# Key layout shared by seed-consul.sh, mutate-consul.sh, and the functional check.
LOGGER_COUNT=170
LOGGER_MUTATED_COUNT=50
LEVELS=(INFO DEBUG WARN)
APP_ROOT="config/${NAMESPACE}/application"
SERVICE_ROOT="config/${NAMESPACE}/${MICROSERVICE_NAME}"

# Logger name as it appears in logback.xml.
logger_name() {
    printf 'org.bench.module%02d.pkg%03d' $(( ($1 - 1) / 10 + 1 )) "$1"
}

# Every fifth logger key uses the slash-separated form, as .github/workflows/sh/nifi-lib.sh writes it.
logger_key() {
    local name
    name=$(logger_name "$1")
    if (( $1 % 5 == 0 )); then
        echo "${APP_ROOT}/logger/${name//./\/}"
    else
        echo "${APP_ROOT}/logger.${name}"
    fi
}

# Level of logger number $1 after change round $2. Round 0 is the seeded state.
# Each round moves every mutated logger to a different level than the round before.
logger_level() {
    echo "${LEVELS[$(( ($1 + $2) % 3 ))]}"
}

# Key of watched unrelated property number $1: 1-300 in the application root, 301-400 in the service root.
unrelated_watched_key() {
    if (( $1 <= 300 )); then
        printf '%s/bench.unrelated.prop-%03d' "$APP_ROOT" "$1"
    else
        printf '%s/bench.unrelated.prop-%03d' "$SERVICE_ROOT" "$1"
    fi
}

# Watched unrelated properties changed by each round: 35 in the application root, 15 in the service root.
MUTATED_UNRELATED_IDS=({1..35} {301..315})

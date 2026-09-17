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

# Runs the benchmark scenario for one framework and one JVM profile:
#   1. starts Consul and seeds 1000 keys;
#   2. cold-starts the app COLD_RUNS times and records startup and completion times;
#   3. runs the app for SOAK_MINUTES, changing 50 loggers and 50 unrelated properties every ROUND_INTERVAL seconds,
#      and checks after each round that logback.xml holds the new logger levels;
#   4. stops the app, collects its logs and exit statistics, and writes summary.md and summary.json.
#
# Usage: run-benchmark.sh <spring|quarkus> <profile>
#   <profile> names profiles/<framework>/<profile>.opts
#
# Environment: COLD_RUNS (5), SOAK_MINUTES (10), ROUND_INTERVAL (30), SAMPLE_INTERVAL (10),
#              SKIP_BUILD (false), CONTAINER_ENGINE (docker).

set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

framework="${1:?Usage: run-benchmark.sh <spring|quarkus> <profile>}"
profile="${2:?Usage: run-benchmark.sh <spring|quarkus> <profile>}"
check_framework "$framework"

COLD_RUNS="${COLD_RUNS:-5}"
SOAK_MINUTES="${SOAK_MINUTES:-10}"
ROUND_INTERVAL="${ROUND_INTERVAL:-30}"
SAMPLE_INTERVAL="${SAMPLE_INTERVAL:-10}"
SKIP_BUILD="${SKIP_BUILD:-false}"
STARTUP_TIMEOUT=180
CHECK_TIMEOUT=25
NIFI_CONF=/tmp/nifi-home/conf

image="consul-app-benchmark:${framework}"
profile_file="$BENCH_DIR/profiles/${framework}/${profile}.opts"
results="$BENCH_DIR/results/${framework}-${profile}-$(date +%Y%m%d-%H%M%S)"
failures="$results/failures.txt"

export FRAMEWORK="$framework"
CONSUL_URL="$(consul_url_for "$framework")"
export CONSUL_URL
JAVA_OPTS="$(read_opts "$BENCH_DIR/profiles/measurement.opts") $(read_opts "$profile_file")"
export JAVA_OPTS

fail() {
    log "FAILURE: $*"
    echo "$*" >> "$failures"
}

stage_artifacts() {
    local target="$BENCH_DIR/build/$framework" jar
    rm -rf "$target"
    mkdir -p "$target"
    if [ "$framework" = spring ]; then
        local candidate
        jar=""
        for candidate in "$REPO_ROOT"/qubership-consul/qubership-consul-application/target/qubership-consul-application-*.jar; do
            case "$candidate" in
                *-sources.jar | *-javadoc.jar) continue ;;
            esac
            [ -f "$candidate" ] || continue
            # The newest jar wins, so a jar left over from an older project version is not picked.
            if [ -z "$jar" ] || [ "$candidate" -nt "$jar" ]; then
                jar="$candidate"
            fi
        done
        [ -n "$jar" ] || die "Spring app jar not found; build qubership-consul/qubership-consul-application first"
        cp "$jar" "$target/app.jar"
        basename "$jar" > "$results/artifact.txt"
    else
        local app="$REPO_ROOT/qubership-nifi-quarkus-consul/qubership-nifi-quarkus-consul-application/target/quarkus-app"
        [ -f "$app/quarkus-run.jar" ] || die "Quarkus app not found; build qubership-nifi-quarkus-consul-application first"
        cp -r "$app" "$target/quarkus-app"
        (cd "$app/app" && ls) > "$results/artifact.txt"
    fi
}

build_image() {
    stage_artifacts
    log "Building $image"
    engine build -q -t "$image" --build-arg BASE_IMAGE="$(base_image)" --build-arg FRAMEWORK="$framework" \
        "$(host_path "$BENCH_DIR")" > /dev/null
}

# Prints the CPUs and memory available to containers. docker info and podman info name these fields differently.
engine_resources() {
    if [ "$CONTAINER_ENGINE" = podman ]; then
        echo "engine_cpus=$(engine info --format '{{.Host.CPUs}}')"
        echo "engine_memory_bytes=$(engine info --format '{{.Host.MemTotal}}')"
    else
        echo "engine_cpus=$(engine info --format '{{.NCPU}}')"
        echo "engine_memory_bytes=$(engine info --format '{{.MemTotal}}')"
    fi
}

start_app() {
    compose --profile app up -d --no-deps --force-recreate consul-app > /dev/null 2>&1
}

# Stops the app with SIGTERM, so that the JVM prints its exit statistics, and collects the output into $1.
# The base image entrypoint returns the JVM exit code, which is 143 after SIGTERM.
stop_app() {
    local dest="$1"
    mkdir -p "$dest"
    engine stop -t 60 "$APP_CONTAINER" > /dev/null
    engine cp "$APP_CONTAINER:/tmp/bench/." "$(host_path "$dest")/" > /dev/null 2>&1 || true
    engine logs "$APP_CONTAINER" > "$dest/app.log" 2>&1
    engine inspect -f '{"exitCode": {{.State.ExitCode}}, "oomKilled": {{.State.OOMKilled}}}' "$APP_CONTAINER" \
        > "$dest/state.json"
    engine rm "$APP_CONTAINER" > /dev/null
}

# Waits for the entrypoint to record the completion time. Returns 1 if the app stops first or takes too long.
wait_for_completion() {
    local deadline=$(( SECONDS + STARTUP_TIMEOUT ))
    while (( SECONDS < deadline )); do
        if app_exec test -e /tmp/bench/exec-time.txt 2>/dev/null; then
            return 0
        fi
        app_running || return 1
        sleep 0.5
    done
    return 1
}

# Prints the level logback.xml holds for the logger named $2, read from the file content in $1.
level_in_logback() {
    grep -o "<logger [^>]*name=\"$2\"[^>]*>" <<< "$1" | grep -o 'level="[A-Z]*"' | cut -d'"' -f2 || true
}

# Checks that logback.xml holds the levels of change round $1 for three sampled loggers.
check_logger_levels() {
    local round="$1" content i expected actual deadline=$(( SECONDS + CHECK_TIMEOUT ))
    while true; do
        content=$(app_exec cat "$NIFI_CONF/logback.xml" 2>/dev/null || true)
        local mismatch=""
        for i in 1 25 "$LOGGER_MUTATED_COUNT"; do
            expected=$(logger_level "$i" "$round")
            actual=$(level_in_logback "$content" "$(logger_name "$i")")
            [ "$actual" = "$expected" ] || mismatch="$(logger_name "$i") is '$actual', expected '$expected'"
        done
        [ -z "$mismatch" ] && return 0
        if (( SECONDS >= deadline )); then
            echo "$mismatch"
            return 1
        fi
        sleep 1
    done
}

# Checks that the files written at startup hold seeded values.
check_initial_config() {
    local props custom
    props=$(app_exec cat "$NIFI_CONF/nifi.properties" 2>/dev/null || true)
    custom=$(app_exec cat "$NIFI_CONF/custom.properties" 2>/dev/null || true)
    grep -qx 'nifi.queue.swap.threshold=25000' <<< "$props" \
        || fail "nifi.properties does not hold nifi.queue.swap.threshold=25000"
    grep -qx 'nifi.cluster.base-node-count=3' <<< "$custom" \
        || fail "custom.properties does not hold nifi.cluster.base-node-count=3"
    local mismatch
    mismatch=$(check_logger_levels 0) || fail "logback.xml after startup: $mismatch"
}

cold_start() {
    local run="$1" dest="$results/cold-$1"
    log "Cold start $run/$COLD_RUNS"
    start_app
    if ! wait_for_completion; then
        fail "cold start $run: the app exited or did not write the completion file within ${STARTUP_TIMEOUT}s"
        stop_app "$dest"
        return
    fi
    (( run == 1 )) && check_initial_config
    # shellcheck disable=SC2016
    app_exec sh -c 'awk "/^VmHWM:/ {print \$2}" /proc/$(cat /tmp/bench/app.pid)/status' \
        > "$dest.hwm" 2>/dev/null || true
    stop_app "$dest"
    mv "$dest.hwm" "$dest/rss_hwm_kb.txt"
}

soak() {
    local dest="$results/soak" rounds=$(( SOAK_MINUTES * 60 / ROUND_INTERVAL )) round started mismatch sampler_pid
    log "Soak: $rounds change rounds, ${ROUND_INTERVAL}s apart"
    mkdir -p "$dest"
    start_app
    if ! wait_for_completion; then
        fail "soak: the app exited or did not write the completion file within ${STARTUP_TIMEOUT}s"
        stop_app "$dest"
        return
    fi
    "$BENCH_DIR/sample.sh" "$dest/samples.csv" "$SAMPLE_INTERVAL" &
    sampler_pid=$!

    for round in $(seq 1 "$rounds"); do
        started=$SECONDS
        if ! app_running; then
            fail "soak round $round: the app is not running"
            break
        fi
        "$BENCH_DIR/mutate-consul.sh" "$round"
        if mismatch=$(check_logger_levels "$round"); then
            log "Round $round/$rounds: logback.xml updated in $(( SECONDS - started ))s"
            echo "$round $(( SECONDS - started ))" >> "$dest/rounds.txt"
        else
            fail "soak round $round: logback.xml was not updated within ${CHECK_TIMEOUT}s: $mismatch"
        fi
        sleep $(( ROUND_INTERVAL - (SECONDS - started) > 0 ? ROUND_INTERVAL - (SECONDS - started) : 0 ))
    done

    # shellcheck disable=SC2016
    app_exec sh -c 'grep -E "^(VmRSS|VmHWM|Threads):" /proc/$(cat /tmp/bench/app.pid)/status' \
        > "$dest/final-status.txt" 2>/dev/null || true
    # shellcheck disable=SC2016
    app_exec sh -c 'awk "{print \$14 + \$15}" /proc/$(cat /tmp/bench/app.pid)/stat' \
        > "$dest/final-cpu-ticks.txt" 2>/dev/null || true
    kill "$sampler_pid" 2> /dev/null || true
    wait "$sampler_pid" 2> /dev/null || true
    stop_app "$dest"
}

# Records failures the JVM reports in its output or through its exit state.
check_jvm_failures() {
    local dir state
    for dir in "$results"/cold-* "$results/soak"; do
        [ -d "$dir" ] || continue
        if grep -qE 'OutOfMemoryError|CodeCache is full|Terminating due to java.lang' "$dir/app.log" 2>/dev/null; then
            fail "$(basename "$dir"): $(grep -m 1 -E 'OutOfMemoryError|CodeCache is full|Terminating due' "$dir/app.log")"
        fi
        state=$(cat "$dir/state.json" 2>/dev/null || echo '{}')
        # 143 is the exit code of a JVM stopped by SIGTERM, which is how stop_app ends every run.
        if [ "$(jq -r '.oomKilled' <<< "$state")" = true ] \
            || ! [[ "$(jq -r '.exitCode' <<< "$state")" =~ ^(0|130|143)$ ]]; then
            fail "$(basename "$dir"): container state $state"
        fi
    done
}

main() {
    mkdir -p "$results"
    : > "$failures"
    echo "$JAVA_OPTS" > "$results/java-opts.txt"
    cp "$profile_file" "$results/profile.opts"

    if [ "$SKIP_BUILD" != true ] || ! engine image inspect "$image" > /dev/null 2>&1; then
        build_image
    fi
    # The profile options are split into words on purpose.
    # shellcheck disable=SC2046
    engine run --rm --entrypoint java "$image" $(read_opts "$profile_file") -XX:+PrintFlagsFinal -version \
        > "$results/flags.txt" 2>&1
    {
        echo "framework=$framework"
        echo "profile=$profile"
        echo "base_image=$(base_image)"
        echo "commit=$(git -C "$(host_path "$REPO_ROOT")" rev-parse --short HEAD)"
        echo "container_engine=$CONTAINER_ENGINE $(engine version --format '{{.Server.Version}}')"
        engine_resources
        echo "cold_runs=$COLD_RUNS soak_minutes=$SOAK_MINUTES round_interval=$ROUND_INTERVAL"
    } > "$results/environment.txt"

    log "Results: $results"
    compose --profile app down -v > /dev/null 2>&1 || true
    compose up -d --wait consul > /dev/null 2>&1
    wait_for_consul
    "$BENCH_DIR/seed-consul.sh"

    local run
    for run in $(seq 1 "$COLD_RUNS"); do
        cold_start "$run"
    done
    soak
    check_jvm_failures
    compose --profile app down -v > /dev/null 2>&1 || true

    "$BENCH_DIR/report.sh" "$results"
    if [ -s "$failures" ]; then
        log "Run FAILED, see $failures"
        exit 1
    fi
    log "Run passed"
}

main

#!/bin/bash
# Copyright 2020-2026 NetCracker Technology Corporation
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

# Hooks for the consul-unreachable scenario: NiFi starts once with a working Consul, then starts again when Consul
# cannot be reached. With a host that does not resolve, the Consul integration application terminates the startup;
# with a Consul that does not respond, it waits for the response and the startup hangs.
# Sourced by run.sh and collect-evidence.sh after lib.sh.
# shellcheck disable=SC2034

CONSUL_CONTAINER=ts-consul

# The Spring application takes CONSUL_URL as <host>:<port>, the Quarkus one as a URL with the protocol.
case "$CONSUL_FRAMEWORK" in
    spring)
        CONSUL_URL_PREFIX=
        CONSUL_APP_JAR=qubership-nifi-consul-application.jar
        # NiFi 1.x has the Spring application only, under another name, and ignores NIFI_CONSUL_INT_FRAMEWORK.
        [ "$NIFI_MAJOR_VERSION" == "2" ] || CONSUL_APP_JAR=qubership-consul-application.jar
        ;;
    quarkus)
        [ "$NIFI_MAJOR_VERSION" == "2" ] ||
            die "CONSUL_FRAMEWORK=quarkus needs NiFi 2.x; NiFi 1.x has the Spring Consul integration only."
        CONSUL_URL_PREFIX=http://
        CONSUL_APP_JAR=quarkus-run.jar
        ;;
    *) die "CONSUL_FRAMEWORK must be spring or quarkus, got '$CONSUL_FRAMEWORK'." ;;
esac
# Read by docker-compose.yaml of the scenario.
export NIFI_CONSUL_URL="${CONSUL_URL_PREFIX}consul:8500"
WRONG_CONSUL_URL="${WRONG_CONSUL_URL:-${CONSUL_URL_PREFIX}consul-wrong:8500}"

case "$CONSUL_FAILURE" in
    # nifi-scripts/start.sh exits with 1 when the Consul integration application terminates.
    wrong-host) SCENARIO_EXPECTED_EXIT_CODE=1 ;;
    # The startup does not end, so run.sh does not wait for an exit.
    no-response) SCENARIO_WAIT_FOR_EXIT=false ;;
    *) die "CONSUL_FAILURE must be wrong-host or no-response, got '$CONSUL_FAILURE'." ;;
esac

scenario_break() {
    if [ "$CONSUL_FAILURE" == "wrong-host" ]; then
        log "Starting NiFi again with CONSUL_URL=$WRONG_CONSUL_URL..."
        NIFI_CONSUL_URL="$WRONG_CONSUL_URL" recreate_nifi
    else
        log "Pausing Consul and starting NiFi again..."
        docker pause "$CONSUL_CONTAINER" >/dev/null
        recreate_nifi
        log "Letting the NiFi startup wait for Consul for $HANG_SECONDS seconds..."
        sleep "$HANG_SECONDS"
        ! is_nifi_exited || log "WARNING: NiFi exited with code $(nifi_exit_code) instead of waiting for Consul."
    fi
}

# Prints the stack of the main thread of the Consul integration application, which waits for Consul.
consul_app_main_thread() {
    local pid since
    pid=$(docker exec "$NIFI_CONTAINER" ps -eo pid,args | awk -v jar="$CONSUL_APP_JAR" 'index($0, jar) && !/awk/ { print $1 }')
    [ -n "$pid" ] || { echo "The Consul integration application is not running."; return 0; }
    if docker exec "$NIFI_CONTAINER" sh -c 'test -x "$JAVA_HOME/bin/jcmd"'; then
        docker exec -u appuser "$NIFI_CONTAINER" sh -c "\$JAVA_HOME/bin/jcmd $pid Thread.print"
    else
        # The NiFi 1.x image has no jcmd. On SIGQUIT the JVM prints its thread dump to the container output.
        since=$(date +%s)
        docker exec "$NIFI_CONTAINER" kill -3 "$pid"
        sleep 2
        docker logs --since "$since" "$NIFI_CONTAINER" 2>&1
    fi | awk '/^"main"/ { found = 1 } found && /^$/ { exit } found'
}

scenario_evidence() {
    local bundleDir="$1"
    {
        echo "## Consul address of the NiFi container"
        docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$NIFI_CONTAINER" |
            grep -E '^(CONSUL_URL|CONSUL_ENABLED|NIFI_CONSUL_INT_FRAMEWORK)=' || true
        echo
        echo "## Consul container"
        docker inspect -f 'status={{.State.Status}} paused={{.State.Paused}}' "$CONSUL_CONTAINER" || true
        echo
        if is_nifi_running; then
            echo "## Main thread of the Consul integration application"
            consul_app_main_thread
            echo
        fi
        echo "## Startup errors"
        # One grep, so that overlapping context lines are printed once.
        grep -E -B 2 -A 12 'APPLICATION FAILED TO START|ERROR' "$bundleDir/container.log" || true
    } >"$bundleDir/scenario-consul.txt"
}

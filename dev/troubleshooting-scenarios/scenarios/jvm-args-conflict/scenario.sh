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

# Hooks for the jvm-args-conflict scenario: NIFI_ADDITIONAL_JVM_ARGS and X_JAVA_ARGS select two different garbage
# collectors, so the start script rejects them and NiFi does not start.
# Sourced by run.sh and collect-evidence.sh after lib.sh.
# shellcheck disable=SC2034

# nifi-scripts/start.sh exits with 3 when a JVM rejects the collector arguments.
SCENARIO_EXPECTED_EXIT_CODE=3

scenario_break() {
    log "Starting NiFi again with NIFI_ADDITIONAL_JVM_ARGS='$JVM_ARGS_ADDITIONAL' and X_JAVA_ARGS='$JVM_ARGS_X_JAVA'..."
    NIFI_ADDITIONAL_JVM_ARGS="$JVM_ARGS_ADDITIONAL" X_JAVA_ARGS="$JVM_ARGS_X_JAVA" recreate_nifi
}

scenario_evidence() {
    local bundleDir="$1"
    {
        echo "## JVM argument variables of the NiFi container"
        docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$NIFI_CONTAINER" |
            grep -E '^(NIFI_ADDITIONAL_JVM_ARGS|X_JAVA_ARGS)=' || true
        echo
        echo "## java.arg entries of conf/bootstrap.conf"
        docker cp -q "$NIFI_CONTAINER:$NIFI_HOME_IN_CONTAINER/conf/bootstrap.conf" - | tar -xO | grep -E '^java\.arg\.' ||
            true
        echo
        echo "## Startup errors"
        grep -E -A 5 'Invalid JVM arguments' "$bundleDir/container.log" || true
    } >"$bundleDir/scenario-jvm-args.txt"
}

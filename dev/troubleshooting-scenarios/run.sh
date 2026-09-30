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

# Starts NiFi with the settings of one scenario, reproduces the issue, and collects an evidence bundle.
# The containers are left as the scenario ends: NiFi keeps running after a runtime scenario and has exited or hangs
# after a startup failure scenario. cleanup.sh removes them.
#
# Usage: run.sh <scenario>
#
# The scenario hooks are called in this order, and each one is optional:
#   scenario_check_prereqs  fail fast when the host is not ready for the scenario
#   scenario_prepare        runs after Consul starts and before the NiFi container is created
#   scenario_configure      writes Consul keys; NiFi reads them at startup
#   scenario_load           runs after the flow is uploaded; drives the flow into the broken state
#   scenario_break          for a startup failure: changes the setup and starts NiFi again; run.sh then waits
#                           for the NiFi container to exit, unless scenario.sh sets SCENARIO_WAIT_FOR_EXIT=false
#   scenario_evidence       adds scenario-specific files to the evidence bundle (called by collect-evidence.sh)

set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

require_commands docker curl jq timeout
load_scenario "${1:-}"

run_hook scenario_check_prereqs

if docker ps -a --format '{{.Names}}' | grep -qx "$NIFI_CONTAINER"; then
    die "Container $NIFI_CONTAINER already exists. Run cleanup.sh before starting another scenario."
fi

log "Preparing work directory $WORK_DIR"
mkdir -p "$WORK_DIR"
# Evidence bundles of earlier runs are kept.
find "$WORK_DIR" -mindepth 1 -maxdepth 1 ! -name evidence -exec rm -rf {} + ||
    die "Cannot clean $WORK_DIR. Run cleanup.sh, which removes files that containers wrote as other users."
cd "$WORK_DIR"
# nifi-lib.sh writes passwords, docker.env, and the volume directories into the current directory.
# shellcheck source=../../.github/workflows/sh/nifi-lib.sh
. "$GIT_BASE_DIR/.github/workflows/sh/nifi-lib.sh"
setup_env_before_tests "tls" "false" >/dev/null
{
    echo "GIT_BASE_DIR=$GIT_BASE_DIR"
    echo "NIFI_SENSITIVE_KEY=$NIFI_SENSITIVE_KEY"
} >>./docker.env
save_started_scenario

log "Starting Consul and generating certificates..."
compose up -d consul nifi-toolkit
toolkitExitCode=$(docker wait ts-nifi-toolkit)
[ "$toolkitExitCode" == "0" ] || die "Certificate generation failed, see 'docker logs ts-nifi-toolkit'."

run_hook scenario_prepare
configure_log_level "$FILE_SYSTEM_REPOSITORY_LOGGER" DEBUG "$CONSUL_TOKEN" "$CONSUL_URL" local >/dev/null
run_hook scenario_configure

log "Starting NiFi ($(compose config --images | grep -v -e consul -e toolkit))..."
compose up -d --wait --wait-timeout 600 --no-deps nifi ||
    die "NiFi did not become healthy, see 'docker logs $NIFI_CONTAINER'."
mark_nifi_up
log "NiFi is up: https://localhost:8080/nifi"
log "Client certificate: $WORK_DIR/temp-vol/tls-cert/nifi/CN=admin_OU=NIFI.p12"
grant_admin_policies

if [ -f "$SCENARIO_DIR/flow.json" ]; then
    log "Uploading flow $SCENARIO_DIR/flow.json..."
    FLOW_GROUP_ID=$(upload_flow "$SCENARIO_DIR/flow.json" "$SCENARIO")
    echo "$FLOW_GROUP_ID" >./flow-group-id
    log "Process group $SCENARIO created: $FLOW_GROUP_ID"
fi

run_hook scenario_load

if declare -F scenario_break >/dev/null; then
    scenario_break
    if [ "${SCENARIO_WAIT_FOR_EXIT:-true}" == "true" ]; then
        log "Waiting up to $NIFI_EXIT_TIMEOUT seconds for NiFi to exit..."
        # A NiFi that does not exit is evidence too, so the bundle is collected either way.
        if exitCode=$(wait_nifi_exit "$NIFI_EXIT_TIMEOUT"); then
            log "NiFi exited with code $exitCode."
            if [ -n "${SCENARIO_EXPECTED_EXIT_CODE:-}" ] && [ "$exitCode" != "$SCENARIO_EXPECTED_EXIT_CODE" ]; then
                log "WARNING: scenario $SCENARIO expects exit code $SCENARIO_EXPECTED_EXIT_CODE."
            fi
        else
            log "WARNING: NiFi did not exit in $NIFI_EXIT_TIMEOUT seconds, see 'docker logs $NIFI_CONTAINER'."
        fi
    fi
    "$TS_DIR/collect-evidence.sh"
    log "Scenario $SCENARIO reproduced. Run collect-evidence.sh for another snapshot, cleanup.sh to remove it."
else
    "$TS_DIR/collect-evidence.sh"
    log "Scenario $SCENARIO reproduced. NiFi keeps running; run collect-evidence.sh for another snapshot, cleanup.sh to stop."
fi

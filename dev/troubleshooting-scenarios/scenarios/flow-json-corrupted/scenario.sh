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

# Hooks for the flow-json-corrupted scenario: NiFi starts once and saves flow.json.gz, then the file is damaged while
# NiFi is stopped, and the next start fails to read the flow.
# Sourced by run.sh and collect-evidence.sh after lib.sh.
# shellcheck disable=SC2034

# NiFi 2.x exits with 1. In NiFi 1.x, RunNiFi reports "NiFi never started" and nifi-scripts/start.sh exits with 0.
# With PENDING_MIGRATION=flow-json the outcome depends on the image: from 2.9.0 the start script exits with 1, before
# 2.9.0 NiFi starts with an empty flow and keeps running, so no exit code is expected.
if [ "$NIFI_MAJOR_VERSION" == "1" ]; then
    SCENARIO_EXPECTED_EXIT_CODE=0
elif [ "$PENDING_MIGRATION" != "flow-json" ]; then
    SCENARIO_EXPECTED_EXIT_CODE=1
fi
# The persistent_conf/conf directory of NiFi, as the helper container sees it.
FLOW_CONF_DIR=/work/temp-vol/nifi/per-conf/conf

case "$CORRUPTION" in
    json | truncated) ;;
    *) die "CORRUPTION must be json or truncated, got '$CORRUPTION'." ;;
esac
case "$PENDING_MIGRATION" in
    none | flow-json | ojdbc-location) ;;
    *) die "PENDING_MIGRATION must be none, flow-json, or ojdbc-location, got '$PENDING_MIGRATION'." ;;
esac
if [ "$NIFI_MAJOR_VERSION" == "1" ] && [ "$PENDING_MIGRATION" != "none" ]; then
    die "PENDING_MIGRATION must be none for NiFi 1.x, which has no flow.json.gz migrations, got '$PENDING_MIGRATION'."
fi

scenario_break() {
    # On the first start that finds a flow.json.gz, nifi-scripts/update_flow_json.sh and, from 2.8.6,
    # update_flow_json_ojdbc_location.sh migrate the file once and leave a marker file each. A restart before the
    # corruption completes both migrations, as on any instance started more than once, so that NiFi itself meets the
    # damaged file. PENDING_MIGRATION=flow-json skips the restart, so that the damaged file meets update_flow_json.sh;
    # PENDING_MIGRATION=ojdbc-location removes the marker of the second script, as a restore from the archive does.
    # NiFi 1.x has no such migrations.
    if [ "$NIFI_MAJOR_VERSION" == "2" ] && [ "$PENDING_MIGRATION" != "flow-json" ]; then
        log "Restarting NiFi, so that the start scripts complete their one-time flow.json.gz migrations..."
        compose stop nifi
        compose up -d --wait --wait-timeout 600 --no-deps nifi ||
            die "NiFi did not become healthy after the restart, see 'docker logs $NIFI_CONTAINER'."
    fi
    log "Stopping NiFi, so that no running NiFi rewrites flow.json.gz after the corruption..."
    compose stop nifi
    if [ "$PENDING_MIGRATION" == "ojdbc-location" ]; then
        log "Removing update_ojdbc_location.applied, so that the next start runs the Oracle driver location migration..."
        helper_run "rm -f $FLOW_CONF_DIR/update_ojdbc_location.applied"
    fi
    log "Corrupting flow.json.gz ($CORRUPTION)..."
    if [ "$CORRUPTION" == "json" ]; then
        # Removing the first comma between two object entries leaves a valid gzip file that is not valid JSON.
        helper_run "cd $FLOW_CONF_DIR && gzip -dc flow.json.gz | sed 's/,\"/ \"/' | gzip >flow.json.gz.new &&
            cat flow.json.gz.new >flow.json.gz && rm flow.json.gz.new"
    else
        helper_run "cd $FLOW_CONF_DIR && head -c \$((\$(wc -c <flow.json.gz) / 2)) flow.json.gz >flow.json.gz.new &&
            cat flow.json.gz.new >flow.json.gz && rm flow.json.gz.new"
    fi
    log "Starting NiFi again..."
    recreate_nifi
}

scenario_evidence() {
    local bundleDir="$1"
    {
        echo "## persistent_conf/conf and its archive of earlier flow versions"
        helper_run "ls -l $FLOW_CONF_DIR $FLOW_CONF_DIR/archive"
        echo
        echo "## Integrity of flow.json.gz"
        # A failed update_flow_json.sh leaves the flow unpacked as flow.json, with no flow.json.gz.
        helper_run "if [ ! -f $FLOW_CONF_DIR/flow.json.gz ]; then echo 'gzip: missing'
            elif gzip -t $FLOW_CONF_DIR/flow.json.gz 2>&1; then echo 'gzip: OK'; else echo 'gzip: damaged'; fi"
        echo
        echo "## Errors of the start scripts"
        # Start script lines only, "[<timestamp>] [ERROR]": NiFi 1.x writes its own "[<timestamp>][ERROR]" here too.
        grep -E '^\[[^]]*\] \[ERROR\]' "$bundleDir/container.log" || true
        echo
        echo "## NiFi startup failure and its causes"
        # NiFi 2.x writes "Failed to start Server", NiFi 1.x "Failed to start web server" and "NiFi never started".
        # NiFi 1.x also logs a DeprecationException stack trace on every start, which is not part of the failure.
        grep -E 'Failed to start (Server|web server)|NiFi never started|^[A-Za-z.]+(Exception|Error)|^Caused by' \
            "$bundleDir/nifi-app.log" | grep -v '^org.apache.nifi.deprecation.log.DeprecationException' || true
    } >"$bundleDir/scenario-flow-json.txt"
}

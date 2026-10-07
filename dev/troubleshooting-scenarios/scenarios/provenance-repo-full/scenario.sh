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

# Hooks for the provenance-repo-full scenario: nifi.provenance.repository.max.storage.size is larger than the file
# system of the provenance repository, or leaves too little room on it, so provenance events with large attributes fill
# the file system before cleanup frees enough.
# Sourced by run.sh and collect-evidence.sh after lib.sh.
# shellcheck disable=SC2034

PROVENANCE_REPO_PATH="$NIFI_HOME_IN_CONTAINER/provenance_repository"
# Log text of a failed write to the full file system.
DISK_FULL_PATTERN='No space left on device'
FLOW_PROCESSORS=("Generate random content" "Copy content to attribute" "Modify attribute")

# Prints the usage of the provenance repository file system for progress messages, for example "40 MiB of 256 MiB (15%)".
provenance_repo_usage_line() {
    docker exec "$NIFI_CONTAINER" df -Pk "$PROVENANCE_REPO_PATH" |
        awk 'NR == 2 { printf "%d MiB of %d MiB (%d%%)", $3 / 1024, $2 / 1024, $3 * 100 / $2 }'
}

disk_full_error_count() {
    local count
    count=$(docker exec "$NIFI_CONTAINER" sh -c \
        "cat '$NIFI_HOME_IN_CONTAINER'/logs/nifi-app*.log | grep -c '$DISK_FULL_PATTERN'") || true
    # grep -c prints 0 when nothing matches; empty output means docker exec failed.
    [ -n "$count" ] || die "Cannot read nifi-app.log in $NIFI_CONTAINER."
    echo "$count"
}

scenario_configure() {
    consul_put nifi.provenance.repository.max.storage.size "$PROVENANCE_MAX_STORAGE_SIZE"
}

scenario_load() {
    local groupId name deadline errors i
    local -a processorIds=()
    groupId=$(cat "$WORK_DIR/flow-group-id")
    for name in "${FLOW_PROCESSORS[@]}"; do
        processorIds+=("$(processor_id "$groupId" "$name")")
    done

    log "Generating FlowFiles with a 10 KB random attribute until the provenance repository is full..."
    # Downstream first, so that FlowFiles do not queue up before their consumer runs.
    for ((i = ${#processorIds[@]} - 1; i >= 0; i--)); do
        start_processor "${processorIds[i]}"
    done
    # The deadline covers a flow that stalls, or a max.storage.size that cleanup keeps below the file system size.
    deadline=$((SECONDS + 600))
    # The count is assigned on its own line, so that a failed docker exec stops run.sh instead of ending the loop.
    errors=$(disk_full_error_count)
    while [ "$errors" -eq 0 ]; do
        [ "$SECONDS" -lt "$deadline" ] ||
            die "No '$DISK_FULL_PATTERN' error in nifi-app.log in 600 seconds. Check PROVENANCE_REPO_SIZE and \
PROVENANCE_MAX_STORAGE_SIZE."
        sleep 5
        log "Provenance repository usage: $(provenance_repo_usage_line)"
        errors=$(disk_full_error_count)
    done
    log "The provenance repository is full. Keeping the flow running for $AFTER_FULL_SECONDS seconds..."
    sleep "$AFTER_FULL_SECONDS"
    for i in "${!processorIds[@]}"; do
        stop_processor "${processorIds[i]}" || log "WARNING: failed to stop ${FLOW_PROCESSORS[i]}."
    done
    log "Flow stopped. Repository usage: $(provenance_repo_usage_line)"
}

scenario_evidence() {
    local bundleDir="$1"
    {
        echo "## df of the provenance repository file system"
        docker exec "$NIFI_CONTAINER" df -h "$PROVENANCE_REPO_PATH"
        echo
        echo "## du of the provenance repository and its directories"
        # Two calls: du counts a directory once, so it would skip the children of a directory given before them.
        docker exec "$NIFI_CONTAINER" du -sh "$PROVENANCE_REPO_PATH"
        docker exec "$NIFI_CONTAINER" sh -c "du -sh '$PROVENANCE_REPO_PATH'/*/*"
        echo
        echo "## File counts"
        echo "event_files=$(docker exec "$NIFI_CONTAINER" sh -c \
            "find '$PROVENANCE_REPO_PATH' -maxdepth 2 -type f -name '*.prov*' | wc -l")"
        echo
        echo "## Provenance repository properties"
        docker exec "$NIFI_CONTAINER" grep -E '^nifi\.provenance\.repository\.' \
            "$NIFI_HOME_IN_CONTAINER/conf/nifi.properties"
        echo
        echo "## Provenance repository messages, counted by level, logger, and text"
        # Every failed session commit logs a stack trace, so the messages are counted rather than listed.
        # NiFi 2.x ends the fields before the message with nifiPgPath, NiFi 1.x with spanId.
        grep -E '^\[[^]]*\]\[(WARN|ERROR|INFO) *\].*\[class=[^]]*provenance' "$bundleDir/nifi-app.log" |
            sed -E 's/^\[[^]]*\]\[([A-Z]+) *\].*\[class=([^] ]*) *\].*\[(nifiPgPath|spanId)=[^]]*\] */\1 \2 | /; t; d' |
            sed -E 's/[0-9]+/N/g' | sort | uniq -c | sort -rn || true
        echo
        echo "## Message counts"
        echo "no_space_left=$(grep -c "$DISK_FULL_PATTERN" "$bundleDir/nifi-app.log" || true)"
        echo "failed_event_writes=$(grep -c 'Failed to write events to the Event Store' "$bundleDir/nifi-app.log" ||
            true)"
        echo "failed_compressions=$(grep -c 'Failed to compress .* on rollover' "$bundleDir/nifi-app.log" || true)"
        echo "rollovers_after_failed_write=$(grep -c 'due to WRITER_IS_DIRTY' "$bundleDir/nifi-app.log" || true)"
    } >"$bundleDir/scenario-provenance-repo.txt"
}

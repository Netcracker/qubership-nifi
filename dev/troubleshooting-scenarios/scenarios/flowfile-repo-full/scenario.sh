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

# Hooks for the flowfile-repo-full scenario: queued FlowFiles with large attributes fill the file system of the
# FlowFile repository, so NiFi cannot record new FlowFiles.
# Sourced by run.sh and collect-evidence.sh after lib.sh.
# shellcheck disable=SC2034

FLOWFILE_REPO_PATH="$NIFI_HOME_IN_CONTAINER/flowfile_repository"
# Log text of a failed write to the full file system.
DISK_FULL_PATTERN='No space left on device'

# Prints the usage of the FlowFile repository file system for progress messages, for example "40 MiB of 64 MiB (62%)".
flowfile_repo_usage_line() {
    docker exec "$NIFI_CONTAINER" df -Pk "$FLOWFILE_REPO_PATH" |
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

scenario_load() {
    local groupId generatorId queueId deadline errors
    groupId=$(cat "$WORK_DIR/flow-group-id")
    generatorId=$(processor_id "$groupId" "Generate large attributes")
    queueId=$(connection_id "$groupId" "queued flowfiles")

    log "Queueing FlowFiles with 20 KB of attributes each until the FlowFile repository is full..."
    start_processor "$generatorId"
    # The generator adds 2 MB of attributes per second; the deadline covers a generator that stalls before the
    # repository fills up.
    deadline=$((SECONDS + 600))
    # The count is assigned on its own line, so that a failed docker exec stops run.sh instead of ending the loop.
    errors=$(disk_full_error_count)
    while [ "$errors" -eq 0 ]; do
        [ "$SECONDS" -lt "$deadline" ] ||
            die "No '$DISK_FULL_PATTERN' error in nifi-app.log in 600 seconds. Check the FlowFile repository size."
        sleep 5
        log "Queued: $(connection_queued_count "$queueId") FlowFiles; repository usage: $(flowfile_repo_usage_line)"
        errors=$(disk_full_error_count)
    done
    log "The FlowFile repository is full. Keeping the generator running for $AFTER_FULL_SECONDS seconds..."
    sleep "$AFTER_FULL_SECONDS"
    stop_processor "$generatorId" || log "WARNING: failed to stop the generator."
    log "Generator stopped. Repository usage: $(flowfile_repo_usage_line)"
}

scenario_evidence() {
    local bundleDir="$1"
    {
        echo "## df of the FlowFile repository file system"
        docker exec "$NIFI_CONTAINER" df -h "$FLOWFILE_REPO_PATH"
        echo
        echo "## du of the FlowFile repository and its directories"
        # Two calls: du counts a directory once, so it would skip the children of a directory given before them.
        docker exec "$NIFI_CONTAINER" du -sh "$FLOWFILE_REPO_PATH"
        docker exec "$NIFI_CONTAINER" sh -c "du -sh '$FLOWFILE_REPO_PATH'/*"
        echo
        echo "## File counts"
        echo "journal_files=$(docker exec "$NIFI_CONTAINER" sh -c \
            "find '$FLOWFILE_REPO_PATH/journals' -type f 2>/dev/null | wc -l")"
        echo "swap_files=$(docker exec "$NIFI_CONTAINER" sh -c \
            "find '$FLOWFILE_REPO_PATH/swap' -type f 2>/dev/null | wc -l")"
        echo
        echo "## FlowFile repository and swap properties"
        docker exec "$NIFI_CONTAINER" grep -E '^nifi\.(flowfile\.repository|swap|queue\.swap)\.' \
            "$NIFI_HOME_IN_CONTAINER/conf/nifi.properties"
        echo
        echo "## FlowFile repository errors"
        grep -E 'FlowFile Repository|FlowFileRepository|SwapManager|checkpoint|No space left' "$bundleDir/nifi-app.log" |
            grep -E 'WARN|ERROR|Exception|No space' | tail -n 200 || true
        echo
        echo "## Message counts"
        echo "no_space_left=$(grep -c "$DISK_FULL_PATTERN" "$bundleDir/nifi-app.log" || true)"
    } >"$bundleDir/scenario-flowfile-repo.txt"
}

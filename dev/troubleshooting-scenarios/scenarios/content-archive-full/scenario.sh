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

# Hooks for the content-archive-full scenario: queued content takes more of the content repository than
# nifi.content.repository.archive.max.usage.percentage allows, so archive cleanup runs and cannot free enough.
# Sourced by run.sh and collect-evidence.sh after lib.sh.
# shellcheck disable=SC2034

scenario_load() {
    local groupId generatorId fillerId queueId size target queued deadline
    groupId=$(cat "$WORK_DIR/flow-group-id")
    generatorId=$(processor_id "$groupId" "Generate held content")
    fillerId=$(processor_id "$groupId" "Archive filler")
    queueId=$(connection_id "$groupId" "held content")
    size=$(content_repo_usage | cut -d' ' -f1)
    target=$((size * TARGET_PERCENT / 100))

    log "Running the archive filler for $ARCHIVE_FILLER_SECONDS seconds, so the archive has content..."
    start_processor "$fillerId"
    sleep "$ARCHIVE_FILLER_SECONDS"
    stop_processor "$fillerId"
    sleep 5
    log "Archived claim files: $(content_repo_archive_file_count); repository usage: $(content_repo_usage_line)"

    log "Queueing content until it takes $TARGET_PERCENT% of the $size-byte repository ($target bytes)..."
    start_processor "$generatorId"
    queued=0
    # The generator writes 50 MB per second; the deadline covers a stalled generator, for example on a full tmpfs.
    deadline=$((SECONDS + 600))
    while [ "$queued" -lt "$target" ]; do
        [ "$SECONDS" -lt "$deadline" ] ||
            die "The held content queue did not reach $target bytes in 600 seconds. Check nifi-app.log for write errors."
        sleep 2
        queued=$(connection_queued_bytes "$queueId")
        log "Queued: $queued bytes; repository usage: $(content_repo_usage_line)"
    done
    stop_processor "$generatorId"
    log "Target reached. Archived claim files: $(content_repo_archive_file_count)"

    log "Running the archive filler again for $AFTER_TARGET_SECONDS seconds..."
    start_processor "$fillerId"
    sleep "$AFTER_TARGET_SECONDS"
    stop_processor "$fillerId"
    log "Archive filler stopped. Repository usage: $(content_repo_usage_line)"
}

scenario_evidence() {
    local bundleDir="$1"
    {
        echo "## Archive cleanup runs and the writes that wait for them"
        grep -E 'bytes free for Container|Unable to write flowfile content|Archive cleanup completed|Successfully deleted' \
            "$bundleDir/nifi-app.log" | tail -n 200 || true
        echo
        echo "## Message counts"
        echo "need_to_free=$(grep -c 'so need to free' "$bundleDir/nifi-app.log" || true)"
        echo "write_waiting_for_archive_cleanup=$(grep -c 'Unable to write flowfile content' "$bundleDir/nifi-app.log" || true)"
    } >"$bundleDir/scenario-archive-cleanup.txt"
}

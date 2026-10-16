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

# Hooks for the content-archive-slow scenario: the content repository is on NFS and holds many archived claim
# files, so each archive cleanup run takes long. STORAGE=local runs the same load on a Docker volume as a baseline.
# Sourced by run.sh and collect-evidence.sh after lib.sh.
# shellcheck disable=SC2034

case "$STORAGE" in
    nfs | local) SCENARIO_COMPOSE_FILES=("docker-compose.$STORAGE.yaml") ;;
    *) die "STORAGE must be nfs or local, got '$STORAGE'." ;;
esac

# Archive cleanup DEBUG message that reports the durations of the phases of one cleanup run:
# "Oldest Archive Date for Container default is <date>; delete expired = <n> ms, sort remaining = <n> ms,
# delete oldest = <n> ms, cleanup = <n> ms". The delete expired phase walks every archive directory.
CLEANUP_TIMING_PATTERN='delete expired = [0-9]+ ms, sort remaining = [0-9]+ ms, delete oldest = [0-9]+ ms, cleanup = [0-9]+ ms'

scenario_check_prereqs() {
    if [ "$STORAGE" == "nfs" ]; then
        [ -n "$NFS_ADDR" ] && [ -n "$NFS_EXPORT" ] ||
            die "STORAGE=nfs requires NFS_ADDR and NFS_EXPORT, for example NFS_ADDR=127.0.0.1 NFS_EXPORT=/srv/nifi-content.
Set STORAGE=local to run the baseline on a Docker volume instead."
    fi
}

# Checks that NiFi (uid 10001) can write to the content repository storage before NiFi starts,
# so a wrong export or squash setting fails here rather than as a NiFi startup error.
scenario_prepare() {
    log "Checking that uid 10001 can write to the $STORAGE content repository storage..."
    compose run --rm --no-deps --entrypoint sh nifi -c \
        "touch '$CONTENT_REPO_PATH/.write-check' && rm '$CONTENT_REPO_PATH/.write-check'" ||
        die "NiFi cannot write to the content repository storage. For NFS, the exported directory must be writable \
by uid 10001: run 'sudo chown 10001:0 <exported directory>' on the NFS server, and map clients to uid 10001 \
with all_squash,anonuid=10001,anongid=0 in /etc/exports."
}

scenario_configure() {
    consul_put nifi.content.repository.archive.max.retention.period "$RETENTION"
}

scenario_load() {
    local groupId generatorId dropId deadline
    groupId=$(cat "$WORK_DIR/flow-group-id")
    generatorId=$(processor_id "$groupId" "Generate claims")
    dropId=$(processor_id "$groupId" "Drop")
    # NiFi 1.x writes the FlowFiles of one session into one claim file. One FlowFile per session, 10 times as often,
    # keeps the rate and gives every FlowFile a claim file of its own, as NiFi 2.x does with the flow as uploaded.
    if [ "$NIFI_MAJOR_VERSION" == "1" ]; then
        update_processor "$generatorId" '{"config": {"schedulingPeriod": "10 millis", "properties": {"Batch Size": "1"}}}'
    fi

    log "Writing and dropping content for $LOAD_DURATION seconds on $STORAGE storage..."
    start_processor "$dropId"
    start_processor "$generatorId"
    deadline=$((SECONDS + LOAD_DURATION))
    while [ "$SECONDS" -lt "$deadline" ]; do
        sleep 30
        log "Archived claim files: $(content_repo_archive_file_count); last cleanup: $(last_cleanup_timing)"
    done
    stop_processor "$generatorId"
    log "Load finished. The Drop processor keeps running; archive cleanup continues in the background."
}

last_cleanup_timing() {
    nifi_app_log | grep -E "$CLEANUP_TIMING_PATTERN" | tail -n 1 |
        grep -oE "$CLEANUP_TIMING_PATTERN" || echo "none yet"
}

# Takes thread dumps until one catches the archive cleanup thread in a run, for up to 90 seconds, and prints
# that thread's stack. A run starts once a minute, so a single dump usually finds the thread waiting.
cleanup_thread_in_run() {
    local deadline=$((SECONDS + 90))
    local stack
    while [ "$SECONDS" -lt "$deadline" ]; do
        stack=$(docker exec "$NIFI_CONTAINER" sh -c \
            "bin/nifi.sh diagnostics --verbose /tmp/cleanup-dump.txt >/dev/null 2>&1 &&
                awk '/^\"Cleanup Archive for default\"/ { found = 1 } found && /^\$/ { exit } found' /tmp/cleanup-dump.txt")
        if echo "$stack" | head -n 1 | grep -q RUNNABLE; then
            echo "$stack"
            return 0
        fi
        sleep 5
    done
    echo "No thread dump in 90 seconds caught the Cleanup Archive for default thread in a run. Last stack:"
    echo "$stack"
}

scenario_evidence() {
    local bundleDir="$1"
    local listStart listEnd fileCount
    cleanup_thread_in_run >"$bundleDir/scenario-cleanup-thread.txt"
    grep -E "$CLEANUP_TIMING_PATTERN" "$bundleDir/nifi-app.log" >"$bundleDir/scenario-cleanup-timings.log" || true
    listStart=$(date +%s%N)
    fileCount=$(docker exec "$NIFI_CONTAINER" sh -c "find '$CONTENT_REPO_PATH' -type f 2>/dev/null | wc -l")
    listEnd=$(date +%s%N)
    {
        echo "storage=$STORAGE"
        # A run takes the sum of its phases; the delete expired phase is also reported on its own.
        grep -oE "$CLEANUP_TIMING_PATTERN" "$bundleDir/scenario-cleanup-timings.log" | awk -F'[=,]' '
            {
                expired = $2 + 0
                total = expired + $4 + $6 + $8
                sum += total; if (total > max) max = total
                expiredSum += expired; if (expired > expiredMax) expiredMax = expired
                count++
            }
            END {
                printf "cleanup_runs=%d\n", count
                printf "cleanup_max_ms=%d\n", max
                printf "cleanup_avg_ms=%d\n", count ? sum / count : 0
                printf "delete_expired_max_ms=%d\n", expiredMax
                printf "delete_expired_avg_ms=%d\n", count ? expiredSum / count : 0
            }'
        echo "files_listed=$fileCount"
        echo "list_all_files_ms=$(((listEnd - listStart) / 1000000))"
    } >"$bundleDir/scenario-cleanup-summary.txt"
    cat "$bundleDir/scenario-cleanup-summary.txt"
}

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

# Collects an evidence bundle from the started scenario into .work/evidence/<scenario>/<timestamp>/.
# The file names are the same for every scenario; README.md lists them. When NiFi has not come up, as after a startup
# failure that exited or hangs, the steps that need a running NiFi are skipped and listed in manifest.txt.
#
# Usage: collect-evidence.sh

set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

load_started_scenario
NIFI_STATE=$(docker inspect -f '{{.State.Status}}' "$NIFI_CONTAINER" 2>/dev/null) ||
    die "Container $NIFI_CONTAINER does not exist."
[ "$NIFI_STATE" == "running" ] || [ "$NIFI_STATE" == "exited" ] ||
    die "Container $NIFI_CONTAINER is $NIFI_STATE; wait until it is running or has exited."
NIFI_HEALTH=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$NIFI_CONTAINER")
# NiFi has come up when run.sh saw this container become healthy (mark_nifi_up). A NiFi that turned unhealthy later
# is still asked; a container that scenario_break created again is not, since its startup failed or hangs.
if [ "$NIFI_STATE" == "running" ] &&
    [ "$(docker inspect -f '{{.Id}}' "$NIFI_CONTAINER")" == "$(cat "$WORK_DIR/nifi-up-container" 2>/dev/null)" ]; then
    NIFI_UP=true
else
    NIFI_UP=false
fi
cd "$WORK_DIR"

BUNDLE_DIR="$EVIDENCE_ROOT/$SCENARIO/$(date '+%Y%m%d-%H%M%S')"
mkdir -p "$BUNDLE_DIR"
log "Collecting evidence into $BUNDLE_DIR"

# Runs a collection step and records a failure in the bundle instead of stopping, so one broken step
# (for example, a REST call to a NiFi that no longer responds) does not cost the rest of the evidence.
collect() {
    local file="$1"
    shift
    if ! "$@" >"$BUNDLE_DIR/$file" 2>&1; then
        echo "Failed to collect $file" >>"$BUNDLE_DIR/collection-errors.txt"
        log "WARNING: failed to collect $file"
    fi
}

write_manifest() {
    local name
    echo "scenario=$SCENARIO"
    echo "collected_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "nifi_image=$(docker inspect -f '{{.Config.Image}}' "$NIFI_CONTAINER")"
    echo "nifi_image_id=$(docker inspect -f '{{.Image}}' "$NIFI_CONTAINER")"
    echo "nifi_image_version=$(docker inspect -f '{{index .Config.Labels "org.opencontainers.image.version"}}' \
        "$NIFI_CONTAINER")"
    echo "nifi_started_at=$(docker inspect -f '{{.State.StartedAt}}' "$NIFI_CONTAINER")"
    echo "nifi_state=$NIFI_STATE"
    echo "nifi_health=$NIFI_HEALTH"
    echo "nifi_restart_count=$(docker inspect -f '{{.RestartCount}}' "$NIFI_CONTAINER")"
    [ -z "${SCENARIO_EXPECTED_EXIT_CODE:-}" ] || echo "expected_exit_code=$SCENARIO_EXPECTED_EXIT_CODE"
    if [ "$NIFI_STATE" == "exited" ]; then
        echo "nifi_exit_code=$(nifi_exit_code)"
        echo "nifi_finished_at=$(docker inspect -f '{{.State.FinishedAt}}' "$NIFI_CONTAINER")"
    fi
    if [ "$NIFI_UP" == "true" ]; then
        echo "nifi_version=$(nifi_api GET /flow/about | jq -r '.about.version')"
        echo "content_repository_mount=$(docker exec "$NIFI_CONTAINER" awk -v path="$CONTENT_REPO_PATH" \
            '$2 == path { print $1, $3, $4 }' /proc/mounts)"
    else
        echo "skipped=${RUNNING_ONLY_FILES[*]} (NiFi has not come up: $NIFI_STATE, health $NIFI_HEALTH)"
    fi
    scenario_param_names | while read -r name; do
        echo "param.$name=${!name}"
    done
}

write_storage() {
    local usage size used
    usage=$(content_repo_usage)
    read -r size used <<<"$usage"
    echo "## df of the content repository file system"
    docker exec "$NIFI_CONTAINER" df -h "$CONTENT_REPO_PATH"
    echo "used_percent=$((used * 100 / size))"
    echo
    echo "## du of the content repository and of its archive directories"
    docker exec "$NIFI_CONTAINER" du -sh "$CONTENT_REPO_PATH"
    # find runs du in several batches for the 1024 archive directories, so the sizes are summed here.
    echo "archive directories: $(docker exec "$NIFI_CONTAINER" sh -c \
        "find '$CONTENT_REPO_PATH' -type d -name archive -exec du -sk {} + 2>/dev/null" |
        awk '{ sum += $1 } END { printf "%.1f MiB", sum / 1024 }')"
    echo
    echo "## file counts"
    echo "claim_files_total=$(docker exec "$NIFI_CONTAINER" sh -c "find '$CONTENT_REPO_PATH' -type f 2>/dev/null | wc -l")"
    echo "claim_files_archived=$(content_repo_archive_file_count)"
    echo "partition_directories=$(docker exec "$NIFI_CONTAINER" sh -c \
        "find '$CONTENT_REPO_PATH' -mindepth 1 -maxdepth 1 -type d | wc -l")"
}

# NiFi also writes its application log to stdout. Its lines start with "[<timestamp>][<level>", while the startup
# scripts (nifi-scripts/logging_api.sh) write "[<timestamp>] [<level>]", so only the application log lines are dropped.
# When NiFi has not come up, the container output is kept whole: the NiFi lines are few, and the stack trace that
# ended the startup continues on lines without a timestamp.
container_output() {
    if [ "$NIFI_UP" == "true" ]; then
        docker logs "$NIFI_CONTAINER" 2>&1 | grep -vE '^\[[^]]*\]\[' || true
    else
        docker logs "$NIFI_CONTAINER" 2>&1
    fi
}

# Prints one NiFi log from the copy of the logs directory, including the files logback has rolled over, oldest first.
# Usage: copied_log PREFIX, for example nifi-app or nifi-bootstrap.
copied_log() {
    local prefix="$1"
    local rolled
    if [ ! -f "$LOGS_COPY/$prefix.log" ]; then
        echo "$prefix.log does not exist: NiFi did not start far enough to write it."
        return 0
    fi
    mapfile -t rolled < <(find "$LOGS_COPY" -maxdepth 1 -name "${prefix}_*.log" | sort)
    cat "${rolled[@]}" "$LOGS_COPY/$prefix.log"
}

# Content repository, warning, and error lines, without the DEBUG lines FileSystemRepository writes for every claim.
filter_app_log() {
    grep -iE 'archive|FileSystemRepository|ContentRepository|WARN|ERROR' "$BUNDLE_DIR/nifi-app.log" |
        grep -vE '\[method=(create|write|close|cleanupResources|archive|visitFile|isWaitRequired|getActiveResourceClaims) *\]' ||
        true
}

write_diagnostics() {
    docker exec "$NIFI_CONTAINER" "$NIFI_HOME_IN_CONTAINER/bin/nifi.sh" diagnostics --verbose /tmp/diagnostics.txt \
        >/dev/null
    docker exec "$NIFI_CONTAINER" cat /tmp/diagnostics.txt
}

# Files that need a NiFi that has come up: its REST API, or its repositories in the container.
RUNNING_ONLY_FILES=(storage.txt nifi.properties.content.txt system-diagnostics.json root-status.json bulletins.json
    diagnostics.txt)

# docker cp works on a stopped container too, so the logs are read from a copy in both states.
LOGS_COPY="$WORK_DIR/logs-copy"
rm -rf "$LOGS_COPY"
if ! docker cp -q "$NIFI_CONTAINER:$NIFI_HOME_IN_CONTAINER/logs" "$LOGS_COPY"; then
    log "WARNING: failed to copy the NiFi logs directory"
    mkdir -p "$LOGS_COPY"
fi

collect manifest.txt write_manifest
collect nifi-app.log copied_log nifi-app
collect nifi-bootstrap.log copied_log nifi-bootstrap
collect container.log container_output
collect nifi-app.filtered.log filter_app_log
if [ "$NIFI_UP" == "true" ]; then
    collect storage.txt write_storage
    collect nifi.properties.content.txt docker exec "$NIFI_CONTAINER" \
        grep -E '^nifi\.content\.' "$NIFI_HOME_IN_CONTAINER/conf/nifi.properties"
    collect system-diagnostics.json nifi_api GET /system-diagnostics
    collect root-status.json nifi_api GET '/flow/process-groups/root/status?recursive=true'
    collect bulletins.json nifi_api GET /flow/bulletin-board
    collect diagnostics.txt write_diagnostics
fi
# Called in a condition, so a failed command in the hook does not stop the collection of the rest.
if ! run_hook scenario_evidence "$BUNDLE_DIR"; then
    echo "Failed to collect scenario-* files" >>"$BUNDLE_DIR/collection-errors.txt"
    log "WARNING: failed to collect scenario-* files"
fi
rm -rf "$LOGS_COPY"
# With DEBUG level for FileSystemRepository the log grows by hundreds of megabytes per hour.
gzip "$BUNDLE_DIR/nifi-app.log"

echo
echo "Evidence bundle: $BUNDLE_DIR"
grep -E '^(nifi_version|content_repository_mount|nifi_exit_code|expected_exit_code)=' "$BUNDLE_DIR/manifest.txt" || true
[ ! -f "$BUNDLE_DIR/storage.txt" ] || grep -E '^(used_percent|claim_files_)' "$BUNDLE_DIR/storage.txt" || true
echo "Lines in nifi-app.filtered.log: $(wc -l <"$BUNDLE_DIR/nifi-app.filtered.log")"
[ ! -f "$BUNDLE_DIR/collection-errors.txt" ] || cat "$BUNDLE_DIR/collection-errors.txt"

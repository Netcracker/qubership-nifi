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

# Collects an evidence bundle from the running scenario into .work/evidence/<scenario>/<timestamp>/.
# The file names are the same for every scenario; README.md lists them.
#
# Usage: collect-evidence.sh

set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

load_started_scenario
is_nifi_running || die "Container $NIFI_CONTAINER is not running."
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
    echo "nifi_started_at=$(docker inspect -f '{{.State.StartedAt}}' "$NIFI_CONTAINER")"
    echo "nifi_version=$(nifi_api GET /flow/about | jq -r '.about.version')"
    echo "content_repository_mount=$(docker exec "$NIFI_CONTAINER" awk -v path="$CONTENT_REPO_PATH" \
        '$2 == path { print $1, $3, $4 }' /proc/mounts)"
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
container_output() {
    docker logs "$NIFI_CONTAINER" 2>&1 | grep -vE '^\[[^]]*\]\[' || true
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

collect manifest.txt write_manifest
collect storage.txt write_storage
collect nifi.properties.content.txt docker exec "$NIFI_CONTAINER" \
    grep -E '^nifi\.content\.' "$NIFI_HOME_IN_CONTAINER/conf/nifi.properties"
collect nifi-app.log nifi_app_log
collect container.log container_output
collect nifi-app.filtered.log filter_app_log
collect system-diagnostics.json nifi_api GET /system-diagnostics
collect root-status.json nifi_api GET '/flow/process-groups/root/status?recursive=true'
collect bulletins.json nifi_api GET /flow/bulletin-board
collect diagnostics.txt write_diagnostics
run_hook scenario_evidence "$BUNDLE_DIR"
# With DEBUG level for FileSystemRepository the log grows by hundreds of megabytes per hour.
gzip "$BUNDLE_DIR/nifi-app.log"

echo
echo "Evidence bundle: $BUNDLE_DIR"
grep -E '^(nifi_version|content_repository_mount)=' "$BUNDLE_DIR/manifest.txt" || true
grep -E '^(used_percent|claim_files_)' "$BUNDLE_DIR/storage.txt" || true
echo "Lines in nifi-app.filtered.log: $(wc -l <"$BUNDLE_DIR/nifi-app.filtered.log")"
[ ! -f "$BUNDLE_DIR/collection-errors.txt" ] || cat "$BUNDLE_DIR/collection-errors.txt"

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

# Shared settings and helpers for the troubleshooting scenario scripts.
# The scripts that source this file, and the scenario.sh files they load, use its variables.
# shellcheck disable=SC2034

TS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GIT_BASE_DIR="$(cd "$TS_DIR/../.." && pwd)"
export GIT_BASE_DIR
WORK_DIR="$TS_DIR/.work"
EVIDENCE_ROOT="$WORK_DIR/evidence"
COMPOSE_PROJECT="nifi-ts"
NIFI_CONTAINER="ts-nifi"
NIFI_HOME_IN_CONTAINER="/opt/nifi/nifi-current"
CONTENT_REPO_PATH="$NIFI_HOME_IN_CONTAINER/persistent_data/content_repository"
CONSUL_URL="http://localhost:8500"
CONSUL_APP_ROOT="config/local/application"
FILE_SYSTEM_REPOSITORY_LOGGER="org.apache.nifi.controller.repository.FileSystemRepository"
# Image for helper containers that need root, such as removing files NiFi wrote; the same Consul image the compose uses.
HELPER_IMAGE="hashicorp/consul:1.22@sha256:a230dcea0bb107bd7958a912d1429fb7f9d399637de7ffb814b34412b9e8c543"

# Seconds run.sh waits for NiFi to exit after scenario_break.
NIFI_EXIT_TIMEOUT="${NIFI_EXIT_TIMEOUT:-600}"

# Compose override files of the loaded scenario, relative to its directory. scenario.sh may change the list.
SCENARIO_COMPOSE_FILES=(docker-compose.yaml)

log() {
    echo "[$(date '+%H:%M:%S')] $*"
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

require_commands() {
    local cmd
    for cmd in "$@"; do
        command -v "$cmd" >/dev/null 2>&1 || die "'$cmd' is required but was not found in PATH."
    done
}

list_scenarios() {
    local dir
    for dir in "$TS_DIR"/scenarios/*/; do
        basename "$dir"
    done
}

# Sources scenarios/<name>/scenario.env and scenario.sh, and sets SCENARIO and SCENARIO_DIR.
# Values already set in the environment take precedence over the scenario.env defaults.
load_scenario() {
    SCENARIO="$1"
    [ -n "$SCENARIO" ] || die "Scenario name is required. Available scenarios: $(list_scenarios | tr '\n' ' ')"
    SCENARIO_DIR="$TS_DIR/scenarios/$SCENARIO"
    [ -f "$SCENARIO_DIR/scenario.sh" ] ||
        die "Unknown scenario '$SCENARIO'. Available scenarios: $(list_scenarios | tr '\n' ' ')"
    if [ -f "$SCENARIO_DIR/scenario.env" ]; then
        # Exported, so that the compose files can use the values too.
        set -a
        # shellcheck source=/dev/null
        . "$SCENARIO_DIR/scenario.env"
        set +a
    fi
    # shellcheck source=/dev/null
    . "$SCENARIO_DIR/scenario.sh"
}

# Prints the scenario.env variable names of the loaded scenario.
scenario_param_names() {
    [ ! -f "$SCENARIO_DIR/scenario.env" ] ||
        sed -n 's/^\([A-Z_][A-Z0-9_]*\)=.*/\1/p' "$SCENARIO_DIR/scenario.env"
}

# Saves the scenario name and its resolved parameters in the work directory, for load_started_scenario.
save_started_scenario() {
    local name
    echo "$SCENARIO" >"$WORK_DIR/scenario"
    scenario_param_names | while read -r name; do
        printf '%s=%q\n' "$name" "${!name}"
    done >"$WORK_DIR/scenario-params.env"
}

# Loads the scenario that run.sh started, with the parameters it ran with.
load_started_scenario() {
    [ -f "$WORK_DIR/scenario" ] || die "No scenario has been started. Run run.sh <scenario> first."
    if [ -f "$WORK_DIR/scenario-params.env" ]; then
        set -a
        # shellcheck source=/dev/null
        . "$WORK_DIR/scenario-params.env"
        set +a
    fi
    load_scenario "$(cat "$WORK_DIR/scenario")"
}

# Calls a scenario hook if the scenario defines it.
run_hook() {
    local hook="$1"
    shift
    if declare -F "$hook" >/dev/null; then
        "$hook" "$@"
    fi
}

compose() {
    local args=(-p "$COMPOSE_PROJECT" -f "$TS_DIR/docker-compose.base.yaml")
    local file
    for file in "${SCENARIO_COMPOSE_FILES[@]}"; do
        # A scenario without an override file keeps the storage of the base compose file.
        [ ! -f "$SCENARIO_DIR/$file" ] || args+=(-f "$SCENARIO_DIR/$file")
    done
    docker compose "${args[@]}" --env-file "$WORK_DIR/docker.env" "$@"
}

is_nifi_running() {
    [ "$(docker inspect -f '{{.State.Running}}' "$NIFI_CONTAINER" 2>/dev/null)" == "true" ]
}

is_nifi_exited() {
    [ "$(docker inspect -f '{{.State.Status}}' "$NIFI_CONTAINER" 2>/dev/null)" == "exited" ]
}

nifi_exit_code() {
    docker inspect -f '{{.State.ExitCode}}' "$NIFI_CONTAINER"
}

# Creates the NiFi container again and starts it without waiting for it to become healthy, for scenario_break.
# Variables exported by the caller take precedence over .work/docker.env in the compose files.
# The anonymous volumes of the image, such as logs and conf, are created anew, as for a new pod in Kubernetes;
# the persistent_conf bind mount keeps flow.json.gz and the sensitive key hash of the first start.
recreate_nifi() {
    compose up -d --no-deps --force-recreate --renew-anon-volumes nifi
}

# Waits for the NiFi container to stop and prints its exit code. Fails when it is still running after the timeout.
# Usage: wait_nifi_exit TIMEOUT_SECONDS
wait_nifi_exit() {
    timeout "$1" docker wait "$NIFI_CONTAINER"
}

# Records that the current NiFi container has become healthy. collect-evidence.sh calls the REST API only for this
# container: one that scenario_break created again has not come up, even when Docker later reports it unhealthy.
mark_nifi_up() {
    docker inspect -f '{{.Id}}' "$NIFI_CONTAINER" >"$WORK_DIR/nifi-up-container"
}

# Runs a shell command as root in a helper container with the work directory mounted at /work,
# for changing files that NiFi wrote as another user.
helper_run() {
    docker run --rm -v "$WORK_DIR:/work" --entrypoint sh "$HELPER_IMAGE" -c "$1"
}

# Calls the NiFi REST API from inside the NiFi container, with the admin client certificate.
# The request body, if any, is read from stdin. Usage: nifi_api METHOD PATH [extra curl arguments...]
nifi_api() {
    local method="$1"
    local path="$2"
    shift 2
    docker exec -i "$NIFI_CONTAINER" sh -c \
        'curl -sS --fail-with-body --max-time 60 -X "$0" \
            --cert "/tmp/tls-certs/CN=admin_OU=NIFI.p12:$(cat "/tmp/tls-certs/CN=admin_OU=NIFI.password")" \
            --cert-type P12 --cacert /tmp/tls-certs/nifi-cert.pem "$@"' \
        "$method" "https://nifi:8080/nifi-api$path" "$@"
}

nifi_api_json() {
    local method="$1"
    local path="$2"
    nifi_api "$method" "$path" -H 'Content-Type: application/json' --data-binary @-
}

# Writes a key under the NiFi application root in Consul. Keys named like nifi.properties entries override them.
consul_put() {
    local key="$1"
    local value="$2"
    local respCode
    respCode=$(curl -sS -X PUT --data "$value" -o /dev/null -w '%{response_code}' \
        -H "X-Consul-Token: $CONSUL_TOKEN" "$CONSUL_URL/v1/kv/$CONSUL_APP_ROOT/$key")
    [ "$respCode" == "200" ] || die "Failed to write Consul key $CONSUL_APP_ROOT/$key, response code = $respCode."
    log "Consul: $key = $value"
}

# Waits until a command succeeds. Usage: wait_until DESCRIPTION TIMEOUT_SECONDS COMMAND [ARGS...]
wait_until() {
    local description="$1"
    local timeout="$2"
    shift 2
    local deadline=$((SECONDS + timeout))
    until "$@"; do
        [ "$SECONDS" -lt "$deadline" ] || die "Timed out after $timeout seconds waiting for: $description."
        sleep 2
    done
}

# Uploads a flow definition as a new process group under the root group and prints the group id.
upload_flow() {
    local flowFile="$1"
    local groupName="$2"
    local rootId
    rootId=$(nifi_api GET /flow/process-groups/root | jq -r '.processGroupFlow.id')
    nifi_api POST "/process-groups/$rootId/process-groups/upload" \
        -F "groupName=$groupName" -F positionX=0 -F positionY=0 -F "clientId=troubleshooting-scenarios" \
        -F "file=@-;filename=flow.json" <"$flowFile" | jq -r '.id'
}

processor_id() {
    local groupId="$1"
    local name="$2"
    local id
    id=$(nifi_api GET "/process-groups/$groupId/processors" |
        jq -r --arg name "$name" '.processors[] | select(.component.name == $name) | .id')
    [ -n "$id" ] || die "Processor '$name' was not found in process group $groupId."
    echo "$id"
}

connection_id() {
    local groupId="$1"
    local name="$2"
    local id
    id=$(nifi_api GET "/process-groups/$groupId/connections" |
        jq -r --arg name "$name" '.connections[] | select(.component.name == $name) | .id')
    [ -n "$id" ] || die "Connection '$name' was not found in process group $groupId."
    echo "$id"
}

# Sets the run status of a processor: RUNNING, STOPPED, or RUN_ONCE.
set_processor_state() {
    local id="$1"
    local state="$2"
    local version
    version=$(nifi_api GET "/processors/$id" | jq -r '.revision.version')
    jq -n --argjson version "$version" --arg state "$state" \
        '{revision: {version: $version, clientId: "troubleshooting-scenarios"}, state: $state}' |
        nifi_api_json PUT "/processors/$id/run-status" >/dev/null
}

start_processor() {
    set_processor_state "$1" RUNNING
}

stop_processor() {
    set_processor_state "$1" STOPPED
}

connection_queued_bytes() {
    nifi_api GET "/flow/connections/$1/status" | jq -r '.connectionStatus.aggregateSnapshot.bytesQueued'
}

connection_queued_count() {
    nifi_api GET "/flow/connections/$1/status" | jq -r '.connectionStatus.aggregateSnapshot.flowFilesQueued'
}

# Prints "<size bytes> <used bytes>" of the file system that holds the content repository.
content_repo_usage() {
    docker exec "$NIFI_CONTAINER" df -Pk "$CONTENT_REPO_PATH" | awk 'NR == 2 { print $2 * 1024, $3 * 1024 }'
}

# Prints the NiFi application log, including the files logback has rolled over (hourly in this image), oldest first.
nifi_app_log() {
    docker exec "$NIFI_CONTAINER" sh -c \
        "cd '$NIFI_HOME_IN_CONTAINER/logs' && cat \$(ls nifi-app_*.log 2>/dev/null | sort) nifi-app.log"
}

# Prints the number of files in the archive directories of the content repository.
# Files that archive cleanup deletes during the walk make find report errors, which are dropped.
content_repo_archive_file_count() {
    docker exec "$NIFI_CONTAINER" sh -c "find '$CONTENT_REPO_PATH' -path '*/archive/*' -type f 2>/dev/null | wc -l"
}

# Prints the content repository usage for progress messages, for example "612 MiB of 1024 MiB (59%)".
content_repo_usage_line() {
    local size used
    read -r size used <<<"$(content_repo_usage)"
    echo "$((used / 1048576)) MiB of $((size / 1048576)) MiB ($((used * 100 / size))%)"
}

# Grants the admin user the policies the scenario scripts need: view and modify the root process group,
# operate it, view and modify its data, and view system diagnostics. The initial admin has none of them
# on a new instance. The same policies are set by .github/collections/Setup_Access_Policies.postman_collection.json.
grant_admin_policies() {
    local rootId userId policy action resource
    rootId=$(nifi_api GET /flow/process-groups/root | jq -r '.processGroupFlow.id')
    userId=$(nifi_api GET /tenants/users |
        jq -r '.users[] | select(.component.identity == "CN=admin, OU=NIFI") | .id')
    [ -n "$userId" ] || die "User 'CN=admin, OU=NIFI' was not found in NiFi."
    for policy in "read /process-groups/$rootId" "write /process-groups/$rootId" \
        "write /operation/process-groups/$rootId" "read /data/process-groups/$rootId" \
        "write /data/process-groups/$rootId" "read /system"; do
        read -r action resource <<<"$policy"
        jq -n --arg action "$action" --arg resource "$resource" --arg userId "$userId" \
            '{revision: {version: 0, clientId: "troubleshooting-scenarios"},
            component: {action: $action, resource: $resource, users: [{id: $userId}], userGroups: []}}' |
            nifi_api_json POST /policies >/dev/null
    done
    log "Granted the admin user access to the root process group and system diagnostics."
}

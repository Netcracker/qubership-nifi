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

# Stops the scenario containers and removes their volumes and the .work directory.
# Evidence bundles are kept unless --all is given.
#
# Usage: cleanup.sh [--all]

set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

if [ -f "$WORK_DIR/scenario" ] && [ -f "$WORK_DIR/docker.env" ]; then
    load_started_scenario
    log "Removing containers and volumes of scenario $SCENARIO..."
    compose down -v --remove-orphans
else
    log "No scenario state found; removing project $COMPOSE_PROJECT containers by label."
    docker ps -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" | xargs -r docker rm -f
    docker volume ls -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" | xargs -r docker volume rm
fi

# The toolkit and NiFi write files as other users, so they are removed from a root container.
if [ -d "$WORK_DIR" ]; then
    if [ "${1:-}" == "--all" ]; then
        removeCmd="find /work -mindepth 1 -maxdepth 1 -exec rm -rf {} +"
    else
        removeCmd="find /work -mindepth 1 -maxdepth 1 ! -name evidence -exec rm -rf {} +"
    fi
    helper_run "$removeCmd"
    log "Removed $WORK_DIR contents$([ "${1:-}" == "--all" ] || echo ' except evidence bundles')."
fi
log "Cleanup finished."

#!/bin/bash -e
# Copyright 2020-2025 NetCracker Technology Corporation
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

# shellcheck source=./logging_api.sh

. /opt/nifi/scripts/logging_api.sh

handle_error(){
    error "$1" >&2
    exit 1
}

create_bugfix_marker(){
    echo "$(date +%Y-%m-%dT%H:%M:%S) - $1" > "$flow_conf_path/set_algorithm.applied"
}

flow_conf_path="${NIFI_HOME}/persistent_conf/conf"

# Fix already applied. Skip.
if [ -f "$flow_conf_path/set_algorithm.applied" ]; then
    info "Algorithm NIFI_PBKDF2_AES_GCM_256 has already been installed."
    exit 0
fi

# flow.json.gz missing. Skip.
if [ ! -f "$flow_conf_path/flow.json.gz" ]; then
    info "$flow_conf_path/flow.json.gz not found. The algorithm will be installed - NIFI_PBKDF2_AES_GCM_256."
    "${NIFI_HOME}"/bin/nifi.sh set-sensitive-properties-algorithm NIFI_PBKDF2_AES_GCM_256 || handle_error "Cannot set algorithm NIFI_PBKDF2_AES_GCM_256"
    create_bugfix_marker "flow.json.gz not found"
    exit 0
fi

info "Installation of the Algorithm - NIFI_PBKDF2_AES_GCM_256"

"${NIFI_HOME}"/bin/nifi.sh set-sensitive-properties-algorithm NIFI_PBKDF2_AES_GCM_256 || handle_error "Cannot set algorithm NIFI_PBKDF2_AES_GCM_256"
info "Saving marker for setting algorithm..."
create_bugfix_marker "Set algorithm NIFI_PBKDF2_AES_GCM_256"

info "Algorithm installation complete."
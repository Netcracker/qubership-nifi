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

# shellcheck source=/dev/null
scripts_dir='/opt/nifi/scripts'
[ -f "${scripts_dir}/common.sh" ] && . "${scripts_dir}/common.sh"

. /opt/nifi/scripts/logging_api.sh

handle_error(){
    error "$1" >&2
    exit 1
}

flow_conf_path="${NIFI_HOME}/persistent_conf/conf"

# flow.json.gz missing. Skip.
if [ ! -f "$flow_conf_path/flow.json.gz" ]; then
    info "$flow_conf_path/flow.json.gz not found. Use default algorithm - NIFI_PBKDF2_AES_GCM_256."
    exit 0
fi

# Fix already applied. Skip.
if [ -f "$flow_conf_path/set_algorithm.applied" ]; then
    info "Algorithm fix already applied. Use default algorithm - NIFI_PBKDF2_AES_GCM_256."
    exit 0
fi

info "Upgrade scenario: flow.json.gz exists, but fix marker not set. Setting old algorithm - PBEWITHMD5AND256BITAES-CBC-OPENSSL..."

prop_replace 'nifi.sensitive.props.algorithm' 'PBEWITHMD5AND256BITAES-CBC-OPENSSL'

info "Done setting algorithm."
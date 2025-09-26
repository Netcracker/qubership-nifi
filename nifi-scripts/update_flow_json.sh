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

. /opt/nifi/scripts/logging_api.sh

handle_error(){
    error "$1" >&2
    exit 1
}

create_bugfix_marker(){
    echo "$(date +%Y-%m-%dT%H:%M:%S) - $1" > "$flow_conf_path/update_flow_json.applied"
}

flow_conf_path="${NIFI_HOME}/persistent_conf/conf"

# Fix already applied. Skip.
if [ -f "$flow_conf_path/update_flow_json.applied" ]; then
    info "Nars in flow.json.gz has already been updated."
    exit 0
fi

# flow.json.gz missing. Skip.
#if [ ! -f "$flow_conf_path/flow.json.gz" ]; then
#    info "$flow_conf_path/flow.json.gz not found. No replacement of information about the nar is required."
#    exit 0
#fi

info "Updating information about nar files"

info "Create backup file for flow.json.gz"
cp "$flow_conf_path/flow.json.gz" "$flow_conf_path/flow.json.gz_bk"

info "Unzip flow.json.gz"
gzip -d "$flow_conf_path/flow.json.gz"

mkdir -p /tmp
info "Replace nar information in flow.json.gz"
configFile=$(cat /opt/nifi/scripts/narMappingConfig.json)
tmp=$(mktemp)
jq --argjson file "$configFile" 'walk(if type == "object" and .type != null and $file[.type] != null then if $file[.type].newArtifact != null and $file[.type].newGroup != null then .bundle.artifact = $file[.type].newArtifact | .bundle.group = $file[.type].newGroup | .type = $file[.type].newType elif $file[.type].newArtifact != null then .bundle.artifact = $file[.type].newArtifact | .type = $file[.type].newType else .type = $file[.type].newType end else . end )' "$flow_conf_path/flow.json" >"$tmp" || handle_error "Error while replacing artifact and type in flow - $expflow"
mv "$tmp" "$flow_conf_path/flow.json"

tmp2=$(mktemp)
jq 'walk(if type == "object" and .type != null and .type == "org.apache.nifi.processors.jolt.JoltTransformJSON" then .properties |= with_entries(if .key == "jolt-spec" then .key = "Jolt Specification" elif .key == "jolt-transform" then .key = "Jolt Transform" elif .key == "pretty_print" then .key = "Pretty Print" else .key |= . end ) else . end)' "$flow_conf_path/flow.json" >"$tmp2" || handle_error "Error while executing replacement properties for JoltTransformJSON processor in flow - $expflow"
mv "$tmp2" "$expflow"


info "Updating flow.json complete"

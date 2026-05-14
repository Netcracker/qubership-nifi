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
. /opt/nifi/scripts/logging_api.sh
mkdir -p /tmp/tmp-nifi

scripts_dir='/opt/nifi/scripts'

delete_temp_files() {
    rm -rf /tmp/tmp-nifi/consulValue.json
    rm -rf /tmp/tmp-nifi/deleteValue.json
}

handle_error() {
    error "$1" >&2
    delete_temp_files
    exit 1
}

# set default value for NIFI_ARCHIVE_CONF_MAX_LIST = 50, if not set
if [ -z "$NIFI_ARCHIVE_CONF_MAX_LIST" ]; then
    NIFI_ARCHIVE_CONF_MAX_LIST=50
fi

# listing archived configuration versions
if [[ -d "${NIFI_HOME}"/persistent_conf/conf/archive ]]; then
    if [[ -z "$(find "${NIFI_HOME}"/persistent_conf/conf/archive -name '*.json.gz' | head -1)" ]]; then
        info "Configuration files are missing in the archive directory. Skipping listing of archived configurations."
    else
        info "Listing the latest $NIFI_ARCHIVE_CONF_MAX_LIST archived configuration versions:"
        for filename in $(find "${NIFI_HOME}"/persistent_conf/conf/archive -name '*.json.gz' | \
                sort -r | head -n "$NIFI_ARCHIVE_CONF_MAX_LIST" | xargs stat -c%n/%s); do
            fileSize="${filename##*/}"
            fullFileName="${filename%/*}"
            name="${fullFileName##*/}"
            info "Archive name: $name; size: $fileSize"
        done
    fi
else
    info "Directory with archived versions does not exist. Skipping listing of archived configurations."
fi

info "Start process of restore NiFi configuration."

secretId=""

if [ -z "$CONSUL_ACL_TOKEN" ]; then
    [ -f "${scripts_dir}/restore_nifi_configurations_add_funct.sh" ] && . "${scripts_dir}/restore_nifi_configurations_add_funct.sh"
else
    info "The CONSUL_ACL_TOKEN variable will be used for accessing Consul"
    secretId="${CONSUL_ACL_TOKEN}"
fi

info "Getting nifi-restore-version from Consul"
res=$(curl -sS --write-out "%{http_code}" -o /tmp/tmp-nifi/consulValue.json --header "X-Consul-Token: ${secretId}" "$CONSUL_URL/v1/kv/config/$NAMESPACE/$MICROSERVICE_NAME/nifi-restore-version") || handle_error 'Cannot get nifi-restore-version from Consul'
if [ "$res" != "200" ]; then
    if [ "$res" == "404" ]; then
        info "Property 'nifi-restore-version' is not set, configuration restoration is not required. NiFi will start with the current flow.json.gz configuration."
        delete_temp_files
        exit 0
    fi
    error "Failed to get nifi-restore-version value from Consul. Error message = $(cat /tmp/tmp-nifi/consulValue.json)"
    handle_error "Failed to get nifi-restore-version value from Consul. Response status code = $res"
fi
fileName=$(</tmp/tmp-nifi/consulValue.json jq -r '.[].Value | @base64d')

info "nifi-restore-version - ${fileName}"

if [ ! -f "${NIFI_HOME}/persistent_conf/conf/archive/${fileName}" ]; then
    warn "The specified flow.json.gz version - ${fileName} is missing in the ${NIFI_HOME}/persistent_conf/conf/archive directory. Select a different version and restart NiFi."
    delete_temp_files
    exit 0
fi

gzip -dc "${NIFI_HOME}/persistent_conf/conf/archive/${fileName}" | jq -r '.rootGroup | .. | .connections? | .[]? | .destination.instanceIdentifier' >listInstanceIdentifier.txt
if <./listInstanceIdentifier.txt grep -q -F -e 'temp-funnel'; then
    warn "The selected flow.json.gz - ${fileName} contains temporary funnels, its use can lead to incorrect behavior of NiFi. Select a different version and restart the configuration recovery process."
    delete_temp_files
    exit 0
fi

info "Create backup of current version flow.json.gz"
printf -v dfmt '%(%Y%m%dT%H%M%S+0000)T' -1
mv "${NIFI_HOME}/persistent_conf/conf/flow.json.gz" "${NIFI_HOME}/persistent_conf/conf/archive/${dfmt}_flow.json.gz"

cp "${NIFI_HOME}/persistent_conf/conf/archive/${fileName}" "${NIFI_HOME}/persistent_conf/conf/flow.json.gz"

info "Deleting nifi-restore-version from Consul"
res=$(curl -sS --write-out "%{http_code}" --request DELETE -o /tmp/tmp-nifi/deleteValue.json "$CONSUL_URL"/v1/kv/config/"$NAMESPACE"/"$MICROSERVICE_NAME"/nifi-restore-version?token="$secretId")
if [ "$res" != "200" ]; then
    warn "Removing the property 'nifi-restore-version' from the consul was not completed. You must manually remove the properties. Error message = $(cat /tmp/tmp-nifi/deleteValue.json)"
fi

delete_temp_files
info "Restore for NiFi Configuration finished."

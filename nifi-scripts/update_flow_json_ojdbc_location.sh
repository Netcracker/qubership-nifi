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

# Sets "Database Driver Location(s)" on each DBCPConnectionPool and HikariCPConnectionPool in flow.json.gz
# that uses an Oracle driver and has no driver location set. The Oracle JDBC driver is not in the NiFi lib directory, so such a pool
# cannot load the driver without it.
# This operation is one-time only. It's skipped, if the script succeeded at least once previously.

# shellcheck source=/dev/null
# shellcheck disable=SC2154
. /opt/nifi/scripts/logging_api.sh

handle_error(){
    error "$1" >&2
    exit 1
}

create_bugfix_marker(){
    echo "$(date +%Y-%m-%dT%H:%M:%S) - $1" > "$flow_conf_path/update_ojdbc_location.applied"
}

flow_conf_path="${NIFI_HOME}/persistent_conf/conf"

# flow.json.gz missing. Skip.
if [ ! -f "$flow_conf_path/flow.json.gz" ]; then
    info "$flow_conf_path/flow.json.gz not found. No changes needed."
    exit 0
fi

# Fix already applied. Skip.
if [ -f "$flow_conf_path/update_ojdbc_location.applied" ]; then
    info "OJDBC location fix in flow.json.gz has already been updated. No changes needed."
    exit 0
fi

# NiFi Expression Language value, written to flow.json.gz as is.
ojdbc_location="\${OJDBC_DRIVER_LOCATION:replaceEmpty(\${NIFI_HOME:append('/nifi-config-template')})}"

# NiFi 2.7 renamed the driver location key to "Database Driver Locations" in both pools, and the driver class key
# of HikariCPConnectionPool to "Database Driver Class Name". old_driver_keys holds the keys before 2.7.
# flow.json.gz saved by an earlier version keeps the old keys until NiFi loads it, so both keys are checked.
# A pool whose bundle version is 2.7 or later gets the new key, and any other pool gets the old one.
jq_is_target_pool='def old_driver_keys: {
    "org.apache.nifi.dbcp.DBCPConnectionPool":
        {"class": "Database Driver Class Name", "locations": "database-driver-locations"},
    "org.apache.nifi.dbcp.HikariCPConnectionPool":
        {"class": "hikaricp-driver-classname", "locations": "hikaricp-driver-locations"}
};
def is_target_pool:
    type == "object"
    and (.type | type) == "string"
    and old_driver_keys[.type] != null
    and (old_driver_keys[.type] as $old
        | ((.properties["Database Driver Class Name"] // .properties[$old.class])
            | IN("oracle.jdbc.OracleDriver", "oracle.jdbc.driver.OracleDriver"))
        and ((.properties["Database Driver Locations"] // "") == "")
        and ((.properties[$old.locations] // "") == ""));
def uses_new_driver_locations_key:
    ((.bundle.version // "") | [splits("[.-]")] | .[0:2] | map(tonumber? // 0)) >= [2, 7];'

pool_count=$(gzip -dc "$flow_conf_path/flow.json.gz" | jq "$jq_is_target_pool"' [.. | select(is_target_pool)] | length') \
    || handle_error "Error while searching for Oracle connection pools without driver location in flow.json.gz"

if [ "$pool_count" -eq 0 ]; then
    create_bugfix_marker "File flow.json.gz does not need OJDBC Driver location fix."
    info "No Oracle connection pools without driver location found in flow.json.gz. No changes needed."
    exit 0
fi

info "Setting driver location for $pool_count Oracle connection pool(s) in flow.json.gz..."

info "Create backup file for flow.json.gz"
cp "$flow_conf_path/flow.json.gz" "$flow_conf_path/flow.json.gz_bk_ojdbc_location"

info "Unzip flow.json.gz"
gzip -d "$flow_conf_path/flow.json.gz"

# The key matches the property names of the bundle version the pool records. NiFi renames an old key when it
# loads the flow, as it does for the other properties of that pool.
tmp=$(mktemp)
jq --arg loc "$ojdbc_location" "$jq_is_target_pool"' walk(if is_target_pool then (if uses_new_driver_locations_key then "Database Driver Locations" else old_driver_keys[.type].locations end) as $key | .properties[$key] = $loc else . end)' "$flow_conf_path/flow.json" >"$tmp" || handle_error "Error while setting driver location for Oracle connection pools in flow.json.gz"
mv "$tmp" "$flow_conf_path/flow.json"

gzip "$flow_conf_path/flow.json"

create_bugfix_marker "File flow.json.gz updated with OJDBC Driver location fix"
info "Updating driver location for Oracle connection pools in flow.json.gz complete"

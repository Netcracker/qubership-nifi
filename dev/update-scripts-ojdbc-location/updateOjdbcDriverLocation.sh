#!/bin/bash
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

# Sets "Database Driver Location(s)" on each DBCPConnectionPool and HikariCPConnectionPool in the NiFi exports
# under a directory, where the pool uses an Oracle driver and has no driver location set. Works on both flow exports
# and controller service exports. The Oracle JDBC driver is not in the NiFi lib directory, so such a pool cannot load
# the driver without it.
# nifi-scripts/update_flow_json_ojdbc_location.sh applies the same change to flow.json.gz at NiFi startup.

handle_error() {
    echo "$1" >&2
    exit 1
}

pathToFlow=$1

if [ -z "$pathToFlow" ]; then
    echo "The first argument - 'pathToFlow' is not set. The default value - './export' will be set."
    pathToFlow="./export"
fi

if [ ! -d "$pathToFlow" ]; then
    handle_error "Error: The specified directory '$pathToFlow' does not exist."
fi

# NiFi Expression Language value, written to the exports as is.
ojdbcLocation="\${OJDBC_DRIVER_LOCATION:replaceEmpty(\${NIFI_HOME:append('/nifi-config-template')})}"

# The definitions match nifi-scripts/update_flow_json_ojdbc_location.sh; keep the two in sync.
# NiFi 2.7 renamed the driver location key to "Database Driver Locations" in both pools, and the driver class key
# of HikariCPConnectionPool to "Database Driver Class Name". old_driver_keys holds the keys before 2.7.
# A pool whose bundle version is 2.7 or later gets the new key, and any other pool gets the old one.
# walk visits the pool object of both export formats: "component" in a controller service export, and each entry of
# "controllerServices" in a flow export, including nested process groups.
jqIsTargetPool='def old_driver_keys: {
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

declare -a exportFlow

echo "Start update of Oracle driver location in $pathToFlow"
mapfile -t exportFlow < <(find "$pathToFlow" -type f -name "*.json" | sort)

for file in "${exportFlow[@]}"; do
    poolCount=$(jq "$jqIsTargetPool"' [.. | select(is_target_pool)] | length' "$file") \
        || handle_error "Error while searching for Oracle connection pools without driver location in $file"
    if [ "$poolCount" -eq 0 ]; then
        continue
    fi
    echo "Setting driver location for $poolCount Oracle connection pool(s) in $file"
    tmp=$(mktemp)
    jq --arg loc "$ojdbcLocation" "$jqIsTargetPool"' walk(if is_target_pool then (if uses_new_driver_locations_key then "Database Driver Locations" else old_driver_keys[.type].locations end) as $key | .properties[$key] = $loc else . end)' "$file" >"$tmp" \
        || { rm -f "$tmp"; handle_error "Error while setting driver location for Oracle connection pools in $file"; }
    if [ "$DEBUG_MODE" = "true" ]; then
        echo "DEBUG: diff between $file and $tmp"
        diff "$file" "$tmp"
    fi
    mv "$tmp" "$file"
done

echo "Finish update of Oracle driver location"

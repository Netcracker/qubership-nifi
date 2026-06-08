#!/bin/bash
# shellcheck disable=SC2154

handle_error() {
    echo "$1" >&2
    delete_tmp_files
    exit 1
}

delete_tmp_files() {
    rm -f ./flow-about.json ./target-cs.json
}

pathToFlow=$1

#declare array
declare -a exportFlow

if [ -z "$pathToFlow" ]; then
    echo "The first argument - 'pathToFlow' is not set. The default value - './export' will be set."
    pathToFlow="./export"
fi

if [ ! -d "$pathToFlow" ]; then
    echo "Error: The specified directory does not exist."
    exit 1
fi

#Checking the target version of NiFi
respCode=$(eval curl -sS -w '%{response_code}' -o ./flow-about.json "$NIFI_CERT" "$NIFI_TARGET_URL/nifi-api/flow/about")
if [[ "$respCode" != "200" ]]; then
    echo "Failed to GET /nifi-api/flow/about. Response code = $respCode. Error message:"
    cat ./flow-about.json
    handle_error "Failed to get target NiFi version"
fi

targetVer=$(<./flow-about.json jq -r '.about.version') || handle_error "Error determining version of target NiFi. API response: $(cat ./flow-about.json)"
echo "Target NiFi version - $targetVer"
majorVersion=$(echo "$targetVer" | sed -E 's/([0-9]+)\.([0-9]+)\.([0-9]+)/\1/')

#External controller service references might not resolve correctly only on NiFi 2.x.
#On 1.x there is nothing to update, so skip:
if ((majorVersion != 2)); then
    echo "Target NiFi major version is $majorVersion (not 2). Skipping external controller services update."
    delete_tmp_files
    exit 0
fi

#Fetch top-level controller services once and build a name -> id map.
#uiOnly=true keeps the payload small (no descriptors / referencing components).
respCode=$(eval curl -sS -w '%{response_code}' -o ./target-cs.json "$NIFI_CERT" "$NIFI_TARGET_URL/nifi-api/flow/process-groups/root/controller-services?uiOnly=true")
if [[ "$respCode" != "200" ]]; then
    echo "Failed to GET root controller services. Response code = $respCode. Error message:"
    cat ./target-cs.json
    handle_error "Failed to get target NiFi controller services"
fi

#Duplicate names: from_entries keeps the last one.
nameToId=$(jq -c '[.controllerServices[] | {key: .component.name, value: .id}] | from_entries' ./target-cs.json) \
    || handle_error "Error building controller service name to id map"

echo "Start external controller services update process"
mapfile -t exportFlow < <(find "$pathToFlow" -type f -name "*.json" | sort)

for file in "${exportFlow[@]}"; do
    #Process only exports that declare a non-empty externalControllerServices map:
    if ! jq -e '(.externalControllerServices // {}) | length > 0' "$file" >/dev/null 2>&1; then
        continue
    fi
    echo "Processing external controller services in flow - $file"

    #Warn for every external controller service whose name has no match in target NiFi:
    while IFS= read -r missingName; do
        [ -n "$missingName" ] && echo "WARNING: No controller service named '$missingName' found in target NiFi;" \
            "leaving its id unchanged in $file"
    done < <(jq -r --argjson nameToId "$nameToId" \
        '.externalControllerServices // {} | to_entries[] | select($nameToId[.value.name] == null) | .value.name' "$file")

    tmp=$(mktemp)
    #Build old->new id map from this file's external controller service names, then apply
    #narrowly scoped edits only - externalControllerServices entries and component properties.
    jq --argjson nameToId "$nameToId" '
      ([ (.externalControllerServices // {}) | to_entries[]
         | select($nameToId[.value.name] != null)
         | {key: .key, value: $nameToId[.value.name]} ] | from_entries) as $idMap
      | if ($idMap | length) == 0 then .
        else
          # (a) externalControllerServices: rename the map key + its identifier
          .externalControllerServices |= with_entries(
            ($idMap[.key]) as $new
            | if $new != null then .key = $new | .value.identifier = $new else . end
          )
          # (b) every component "properties" object: replace values that are idMap keys
          | walk(
              if type == "object" and (.properties | type) == "object"
              then .properties |= map_values(
                     if type == "string" and ($idMap[.] != null) then $idMap[.] else . end)
              else . end
            )
        end' "$file" > "$tmp" || handle_error "Error while updating external controller services in flow - $file"

    if [ "$DEBUG_MODE" = "true" ]; then
        echo "DEBUG: diff between $file and $tmp"
        diff "$file" "$tmp"
    fi
    mv "$tmp" "$file"
done

delete_tmp_files
echo "Finish external controller services update process"

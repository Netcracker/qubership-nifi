#!/bin/bash

handle_error() {
    echo "$1" >&2
    delete_tmp_file
    exit 1
}

delete_tmp_file() {
    rm -f ./proc_type_resp.json
}

pathToFlow=$1
pathToUpdateNiFiConfig=$2

#declare array
declare -a listForUpdate
declare -a exportFlow

if [ -z "$pathToFlow" ]; then
    echo "The first argument - 'pathToFlow' is not set. The default value - './export' will be set."
    pathToFlow="./export"
fi

if [ -z "$pathToUpdateNiFiConfig" ]; then
    echo "The second argument - 'pathToUpdateNiFiConfig' is not set. The default value - './upgradeConfig_2_7.json' will be set."
    pathToUpdateNiFiConfig="./upgradeConfig_2_7.json"
fi

if [ ! -d "$pathToFlow" ]; then
    echo "Error: The specified directory does not exist."
    exit 1
fi

if [ ! -f "$pathToUpdateNiFiConfig" ]; then
    echo "Error: The specified configuration file does not exist."
    exit 1
fi

echo "Start update flow process"
mapfile -t exportFlow < <(find "$pathToFlow" -type f -name "*.json" | sort)

for file in "${exportFlow[@]}"; do
    #Get type and Apache NiFi version from export:
    groupAndVersion=$(jq -r 'select(has("component")) | .component | select (has("controllerServiceApis")) | .bundle.group + "_" + .bundle.version' "$file") || handle_error "Error getting version from export - $file"
    group=$(echo "$groupAndVersion" | sed -E 's/([^_]+)_([^_]+)/\1/')
    version=$(echo "$groupAndVersion" | sed -E 's/([^_]+)_([^_]+)/\2/')
    if [ "$group" == "org.apache.nifi" ]; then
        #org.apache.nifi versions have format <majorVersion>.<minorVersion>.<patchVersion>:
        majorVersion=$(echo "$version" | sed -E 's/([0-9]+).([0-9]+).([0-9]+)/\1/')
        minorVersion=$(echo "$version" | sed -E 's/([0-9]+).([0-9]+).([0-9]+)/\2/')
        echo "Found controller service with org.apache.nifi group. Major.minor version: $majorVersion.$minorVersion"
        if ((majorVersion == 1 || majorVersion == 2 && minorVersion < 7)); then
            listForUpdate+=("$file")
            echo "Flow - $file needs to be updated"
        fi
    fi
done

echo "Flow for update: " "${listForUpdate[@]}"

#Checking that the target version of NiFi is different from the one from which the export was made
respCode=$(eval curl -sS -w '%{response_code}' -o ./proc_type_resp.json "$NIFI_CERT" "$NIFI_TARGET_URL/nifi-api/flow/processor-types")
if [[ "$respCode" != "200" ]]; then
    echo "Failed to get types of processors that this NiFi supports. Response code = $respCode. Error message:"
    cat ./proc_type_resp.json
    handle_error "Failed to define NiFi target version"
fi

targetVer=$(<./proc_type_resp.json jq -r '.processorTypes[] | select(.type == "org.apache.nifi.processors.attributes.UpdateAttribute") | .bundle.version') || handle_error "Error determining version of target NiFi"

echo "Target NiFi version - $targetVer"
majorVersion=$(echo "$targetVer" | sed -E 's/([0-9]+).([0-9]+).([0-9]+)/\1/')
minorVersion=$(echo "$targetVer" | sed -E 's/([0-9]+).([0-9]+).([0-9]+)/\2/')

#If target NiFi version is >= 2.7, then run the script on the flow update:
if ((majorVersion == 2 && minorVersion >= 7)); then
    . upgradeExports_2_7.sh
fi

echo "Finish update flow process"

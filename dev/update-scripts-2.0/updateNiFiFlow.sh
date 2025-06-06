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
    echo "The first argument - 'pathToFlow' is not set. The default value - '/export' will be set."
    pathToFlow="./export"
fi

if [ -z "$pathToUpdateNiFiConfig" ]; then
    echo "The second argument - 'pathToUpdateNiFiConfig' is not set. The default value - 'updateNiFiVerNarConfig.json' will be set."
    pathToUpdateNiFiConfig="./updateNiFiVerNarConfig.json"
fi

if [ ! -d "$pathToFlow" ]; then
    echo "Error: The specified directory does not exist."
    exit 1
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
    #Checking that there are no processors with NiFi version 2.x.x in the flow
    needUpdate=$(jq 'all(.. | objects | select(has("bundle")) | .bundle | objects; (has("group") and has("version") | not) or (.group != "org.apache.nifi" or (.version | test("^2\\.[0-9]+\\.[0-9]+$") | not)))' "$file") || handle_error "Error checking version in exported flow - $file"
    if [ "$needUpdate" == "true" ]; then
        listForUpdate+=("$file")
        echo "Flow - $file needs to be updated"
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

#If the NiFi version is 2.x.x, then run the script on the flow update
if [[ "$targetVer" =~ ^2\.[0-9]+\.[0-9]+$ ]]; then
    . ./increaseNiFiVersionUpdate.sh
fi

echo "Finish update flow process"

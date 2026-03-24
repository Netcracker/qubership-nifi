#!/bin/bash

handle_error() {
    echo "$1" >&2
    delete_tmp_file
    exit 1
}

delete_tmp_file() {
    rm -f ./flow-about.json
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
    echo "The second argument - 'pathToUpdateNiFiConfig' is not set. The default value - './updateNiFiVerNarConfig.json' will be set."
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

#Checking the target version of NiFi
respCode=$(eval curl -sS -w '%{response_code}' -o ./flow-about.json "$NIFI_CERT" "$NIFI_TARGET_URL/nifi-api/flow/about")
if [[ "$respCode" != "200" ]]; then
    echo "Failed to GET /nifi-api/flow/about. Response code = $respCode. Error message:"
    cat ./flow-about.json
    handle_error "Failed to get target NiFi version"
fi

targetVer=$(<./flow-about.json jq -r '.about.version') || handle_error "Error determining version of target NiFi. API response: $(cat ./flow-about.json)"

echo "Target NiFi version - $targetVer"

#If the NiFi version is 2.x.x, then run the script on the flow update
if [[ "$targetVer" =~ ^2\.[0-9]+\.[0-9]+$ ]]; then
    . ./increaseNiFiVersionUpdate.sh
fi
delete_tmp_file

echo "Finish update flow process"

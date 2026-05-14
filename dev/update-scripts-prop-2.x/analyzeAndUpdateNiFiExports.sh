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

#declare array
declare -a listForUpdate_2_5
declare -a listForUpdate_2_6
declare -a listForUpdate_2_7
declare -a exportFlow

if [ -z "$pathToFlow" ]; then
    echo "The first argument - 'pathToFlow' is not set. The default value - './export' will be set."
    pathToFlow="./export"
fi

if [ ! -d "$pathToFlow" ]; then
    echo "Error: The specified directory does not exist."
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
        if ((majorVersion == 1 || (majorVersion == 2 && minorVersion < 5))); then
            listForUpdate_2_5+=("$file")
            echo "Flow - $file needs to be updated, if target version >= 2.5"
        fi
        if ((majorVersion == 1 || (majorVersion == 2 && minorVersion < 6))); then
            listForUpdate_2_6+=("$file")
            echo "Flow - $file needs to be updated, if target version >= 2.6"
        fi
        if ((majorVersion == 1 || (majorVersion == 2 && minorVersion < 7))); then
            listForUpdate_2_7+=("$file")
            echo "Flow - $file needs to be updated, if target version >= 2.7"
        fi
    fi
done

echo "Flow for update 2.5: " "${listForUpdate_2_5[@]}"
echo "Flow for update 2.6: " "${listForUpdate_2_6[@]}"
echo "Flow for update 2.7: " "${listForUpdate_2_7[@]}"

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
minorVersion=$(echo "$targetVer" | sed -E 's/([0-9]+)\.([0-9]+)\.([0-9]+)/\2/')

# shellcheck disable=SC2034
#If target NiFi version is >= 2.5, then run the script on the flow update:
if ((majorVersion == 2 && minorVersion >= 5)); then
    listForUpdate=("${listForUpdate_2_5[@]}")
    . upgradeExports_2_x.sh ./upgradeConfig_2_5.json
fi

# shellcheck disable=SC2034
#If target NiFi version is >= 2.6, then run the script on the flow update:
if ((majorVersion == 2 && minorVersion >= 6)); then
    listForUpdate=("${listForUpdate_2_6[@]}")
    . upgradeExports_2_x.sh ./upgradeConfig_2_6.json
fi

# shellcheck disable=SC2034
#If target NiFi version is >= 2.7, then run the script on the flow update:
if ((majorVersion == 2 && minorVersion >= 7)); then
    listForUpdate=("${listForUpdate_2_7[@]}")
    . upgradeExports_2_x.sh ./upgradeConfig_2_7.json
fi

delete_tmp_file

echo "Finish update flow process"

#!/bin/bash
# shellcheck disable=SC2154

# updateFlowExports.sh prepares NiFi versioned flow exports for import into a target
# NiFi instance. It supports three independent updates, each selectable via a flag:
#
#   --external-cs   Resolve externalControllerServices references by matching controller
#                   service names in the target NiFi root process group.
#   --versions      Bump every component bundle version to the version installed in the
#                   target NiFi.
#   --properties    Rename or remove component properties (and their propertyDescriptors)
#                   renamed by NiFi 2.x property migration, using the bundled mapping configs.
#
# When no flag is supplied, all three updates run.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

handle_error() {
    echo "$1" >&2
    delete_tmp_files
    exit 1
}

delete_tmp_files() {
    rm -f ./flow-about.json ./target-cs.json ./proc-types.json ./cs-types.json ./rt-types.json
    #remove temp files used during conversion:
    [ -n "$tmp" ] && [ -f "$tmp" ] && rm -f "$tmp"
    [ -n "$tmp2" ] && [ -f "$tmp2" ] && rm -f "$tmp2"
}

# Replaces the original export with the transformed file, printing a diff first when DEBUG_MODE=true.
replace_with_debug() {
    local newFile=$1 origFile=$2
    if [ "$DEBUG_MODE" = "true" ]; then
        echo "DEBUG: diff between original and modified $origFile"
        tmp2=$(mktemp)
        jq '.' "$origFile" > "$tmp2"
        diff "$tmp2" "$newFile"
        rm -f "$tmp2"
    fi
    mv "$newFile" "$origFile"
}

parse_args() {
    doExternalCs=false
    doVersions=false
    doProperties=false
    pathToFlow=""
    for arg in "$@"; do
        case "$arg" in
            --external-cs) doExternalCs=true ;;
            --versions) doVersions=true ;;
            --properties) doProperties=true ;;
            --*) echo "Error: unknown flag '$arg'." >&2; exit 1 ;;
            *)
                if [ -z "$pathToFlow" ]; then
                    pathToFlow="$arg"
                else
                    echo "Error: unexpected argument '$arg'." >&2; exit 1
                fi
                ;;
        esac
    done

    #When no update flag is supplied, run all updates:
    if [ "$doExternalCs" = false ] && [ "$doVersions" = false ] && [ "$doProperties" = false ]; then
        doExternalCs=true
        doVersions=true
        doProperties=true
    fi

    if [ -z "$pathToFlow" ]; then
        echo "The first argument - 'pathToFlow' is not set. The default value - './export' will be set."
        pathToFlow="./export"
    fi

    if [ ! -d "$pathToFlow" ]; then
        echo "Error: The specified directory does not exist."
        exit 1
    fi
}

# Determines the target NiFi version and sets targetMajor / targetMinor.
get_target_version() {
    respCode=$(eval curl -sS -w '%{response_code}' -o ./flow-about.json "$NIFI_CERT" "$NIFI_TARGET_URL/nifi-api/flow/about")
    if [[ "$respCode" != "200" ]]; then
        echo "Failed to GET /nifi-api/flow/about. Response code = $respCode. Error message:"
        cat ./flow-about.json
        handle_error "Failed to get target NiFi version"
    fi

    targetVer=$(<./flow-about.json jq -r '.about.version') \
        || handle_error "Error determining version of target NiFi. API response: $(cat ./flow-about.json)"
    echo "Target NiFi version - $targetVer"
    targetMajor=$(echo "$targetVer" | sed -E 's/([0-9]+)\.([0-9]+)\.([0-9]+).*/\1/')
    targetMinor=$(echo "$targetVer" | sed -E 's/([0-9]+)\.([0-9]+)\.([0-9]+).*/\2/')
}

#-----------------------------------------------------------------------------
# Scenario 1: external controller service references
#-----------------------------------------------------------------------------
update_external_cs() {
    #External controller service references might not resolve correctly only on NiFi 2.x.
    #On 1.x there is nothing to update, so skip:
    if ((targetMajor != 2)); then
        echo "Target NiFi major version is $targetMajor (not 2). Skipping external controller services update."
        return 0
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
    local nameToId
    nameToId=$(jq -c '[.controllerServices[] | {key: .component.name, value: .id}] | from_entries' ./target-cs.json) \
        || handle_error "Error building controller service name to id map"

    echo "Start external controller services update process"
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

        replace_with_debug "$tmp" "$file"
    done
    echo "Finish external controller services update process"
}

#-----------------------------------------------------------------------------
# Scenario 2: component bundle versions
#-----------------------------------------------------------------------------
# Fetches all component types from the target NiFi and builds a
# "group/artifact" -> version map (stored in $bundleMap).
build_target_bundle_map() {
    local endpoints=(
        "processor-types|./proc-types.json"
        "controller-service-types|./cs-types.json"
        "reporting-task-types|./rt-types.json"
    )
    local files=()
    local item path outFile
    for item in "${endpoints[@]}"; do
        path="${item%%|*}"
        outFile="${item##*|}"
        respCode=$(eval curl -sS -w '%{response_code}' -o "$outFile" "$NIFI_CERT" "$NIFI_TARGET_URL/nifi-api/flow/$path")
        if [[ "$respCode" != "200" ]]; then
            echo "Failed to GET /nifi-api/flow/$path. Response code = $respCode. Error message:"
            cat "$outFile"
            handle_error "Failed to get target NiFi $path"
        fi
        files+=("$outFile")
    done

    #Each component type contributes its own .bundle and, for controller services, the bundles of
    #the service APIs it implements (controllerServiceApis[].bundle). The latter carry API nars such
    #as nifi-standard-services-api-nar that no component type lists as its own bundle, yet flows
    #reference them under controllerServiceApis - so they must be in the map to be updated.
    bundleMap=$(jq -s '
        [ .[] | (.processorTypes // []), (.controllerServiceTypes // []), (.reportingTaskTypes // []) ]
        | add
        | [ .[] | (.bundle, ((.controllerServiceApis // [])[].bundle))
                | {key: "\(.group)/\(.artifact)", value: .version} ]
        | from_entries' "${files[@]}") \
        || handle_error "Error building target NiFi bundle version map"
}

update_versions() {
    build_target_bundle_map

    echo "Start component versions update process"
    for file in "${exportFlow[@]}"; do
        echo "Processing component versions in flow - $file"

        #Warn for every bundle reference whose group/artifact the target NiFi does not provide.
        #Only real bundle references count: values under a "bundle" key (component bundle and
        #controllerServiceApis[].bundle) and "identifiesControllerServiceBundle" - not arbitrary
        #objects that merely happen to carry group/artifact/version.
        while IFS= read -r missingBundle; do
            [ -n "$missingBundle" ] && echo "WARNING: Bundle '$missingBundle' is not present in target NiFi;" \
                "leaving its version unchanged in $file"
        done < <(jq -r --argjson bundleMap "$bundleMap" '
            [ .. | objects | (.bundle?, .identifiesControllerServiceBundle?)
                | select(type == "object" and has("group") and has("artifact") and has("version"))
                | select($bundleMap["\(.group)/\(.artifact)"] == null)
                | "\(.group)/\(.artifact)" ] | unique[]' "$file")

        tmp=$(mktemp)
        #Set version only on real bundle objects (value of a "bundle" key, including
        #controllerServiceApis[].bundle, and "identifiesControllerServiceBundle") whose
        #group/artifact is known to the target. Leave any other object untouched.
        jq --argjson bundleMap "$bundleMap" '
            def setver($b):
                if ($b | type) == "object"
                    and ($b | has("group") and has("artifact") and has("version"))
                    and ($bundleMap["\($b.group)/\($b.artifact)"] != null)
                then $b + {version: $bundleMap["\($b.group)/\($b.artifact)"]}
                else $b end;
            def fix:
                if type == "object"
                then map_values(fix)
                    | if has("bundle") then .bundle = setver(.bundle) else . end
                    | if has("identifiesControllerServiceBundle")
                        then .identifiesControllerServiceBundle = setver(.identifiesControllerServiceBundle)
                        else . end
                elif type == "array" then map(fix)
                else . end;
            fix' "$file" > "$tmp" || handle_error "Error while updating component versions in flow - $file"

        replace_with_debug "$tmp" "$file"
    done
    echo "Finish component versions update process"
}

#-----------------------------------------------------------------------------
# Scenario 3: renamed / removed properties
#-----------------------------------------------------------------------------
# Echoes "<major> <minor>" for the oldest org.apache.nifi component bundle in $1,
# or nothing when the export has no org.apache.nifi components.
flow_source_version() {
    local file=$1 ver minMajor="" minMinor="" major minor
    while IFS= read -r ver; do
        [ -z "$ver" ] && continue
        major=$(echo "$ver" | sed -E 's/([0-9]+)\.([0-9]+).*/\1/')
        minor=$(echo "$ver" | sed -E 's/([0-9]+)\.([0-9]+).*/\2/')
        if [ -z "$minMajor" ] || ((major < minMajor || (major == minMajor && minor < minMinor))); then
            minMajor=$major
            minMinor=$minor
        fi
    done < <(jq -r '[.. | objects | select(has("type") and has("bundle"))
        | .bundle | select(.group == "org.apache.nifi") | .version] | unique[]' "$file")
    [ -n "$minMajor" ] && echo "$minMajor $minMinor"
}

# Applies the controller service + processor mapping configs for one upgrade step to $file.
apply_property_step() {
    local file=$1 minorStep=$2
    local csConfig=$SCRIPT_DIR/csPropConfig_2_${minorStep}.json
    local procConfig=$SCRIPT_DIR/procPropConfig_2_${minorStep}.json

    tmp=$(mktemp)
    #Read the mapping configs via --slurpfile rather than --argjson: some configs exceed the
    #command-line argument length limit. --slurpfile wraps each config in a one-element array.
    #Component type FQCNs do not collide between controller services and processors, so the two
    #mappings merge cleanly into a single delta.
    jq --slurpfile cs "$csConfig" --slurpfile proc "$procConfig" '
        ($cs[0] * $proc[0]) as $delta
        | walk(
            if type == "object" and ((.type? | type) == "string") and ($delta[.type] != null)
            then (.type as $t
                # (a) properties: rename the key, or drop it when the mapping value is null
                | (if (.properties? | type) == "object"
                    then .properties |= with_entries(
                        .key as $k
                        | if ($delta[$t] | has($k))
                            then (if $delta[$t][$k] != null then .key = $delta[$t][$k] else empty end)
                            else . end)
                    else . end)
                # (b) propertyDescriptors: mirror (a), keeping the inner "name" in sync with the key
                | (if (.propertyDescriptors? | type) == "object"
                    then .propertyDescriptors |= with_entries(
                        .key as $k
                        | if ($delta[$t] | has($k))
                            then (if $delta[$t][$k] != null
                                    then .key = $delta[$t][$k] | .value.name = $delta[$t][$k]
                                    else empty end)
                            else . end)
                    else . end))
            else . end
        )' "$file" > "$tmp" || handle_error "Error while updating property names (2.$minorStep) in flow - $file"

    replace_with_debug "$tmp" "$file"
}

update_properties() {
    if ((targetMajor != 2 || targetMinor < 5)); then
        echo "Target NiFi version $targetMajor.$targetMinor has no property mappings. Skipping properties update."
        return 0
    fi

    #Ordered upgrade steps, by target minor version:
    local steps=(5 6 7 9)

    echo "Start property names update process"
    for file in "${exportFlow[@]}"; do
        local srcVer srcMajor srcMinor
        srcVer=$(flow_source_version "$file")
        if [ -z "$srcVer" ]; then
            echo "No org.apache.nifi components in $file; skipping properties update"
            continue
        fi
        srcMajor=${srcVer% *}
        srcMinor=${srcVer#* }
        echo "Processing property names in flow - $file (source version $srcMajor.$srcMinor)"

        local step
        for step in "${steps[@]}"; do
            #Apply a step when the export predates it and the target includes it:
            if ((targetMinor >= step)) && ((srcMajor == 1 || (srcMajor == 2 && srcMinor < step))); then
                echo "  Applying 2.$step property mappings to $file"
                apply_property_step "$file" "$step"
            fi
        done
    done
    echo "Finish property names update process"
}

#-----------------------------------------------------------------------------
# main
#-----------------------------------------------------------------------------
main() {
    parse_args "$@"

    #Collect only versioned flow exports. Single-component exports (controller service / reporting
    #task: .component.type / .component.properties, no flowContents) are handled by update-scripts-2.0
    #and update-scripts-prop-2.x, so this script must leave them untouched.
    declare -a exportFlow
    local candidate
    while IFS= read -r candidate; do
        if jq -e 'has("flowContents")' "$candidate" >/dev/null 2>&1; then
            exportFlow+=("$candidate")
        else
            echo "Skipping non-versioned-flow export (no flowContents): $candidate"
        fi
    done < <(find "$pathToFlow" -type f -name "*.json" | sort)

    get_target_version

    [ "$doProperties" = true ] && update_properties
    [ "$doVersions" = true ] && update_versions
    [ "$doExternalCs" = true ] && update_external_cs

    delete_tmp_files
    echo "Finish flow exports update process"
}

main "$@"

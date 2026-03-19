#!/bin/bash
# shellcheck source=/dev/null
# shellcheck disable=SC2154

echo "Start update flow to NiFi 2.7.2 version using configuration - $pathToUpdateNiFiConfig"
configFile=$(cat "$pathToUpdateNiFiConfig")
for expflow in "${listForUpdate[@]}"; do
    echo "Updating property names based on mapping file for flow - $expflow"
    tmp=$(mktemp)
    jq --argjson delta "$configFile" '.component.type as $type |
        if $delta[$type] != null then
            .component.properties |= with_entries(
                if $delta[$type][.key] != null then
                    .key = $delta[$type][.key]
                else
                    .
                end
            )
        else
            .
        end' "$expflow" >"$tmp" || handle_error "Error while updating property names in flow - $expflow"
    if [ "$DEBUG_MODE" = "true" ]; then
        echo "DEBUG: diff between $expflow and $tmp"
        diff "$expflow" "$tmp"
    fi
    mv "$tmp" "$expflow"
done

echo "Finish update flow to NiFi 2.7.2 version"

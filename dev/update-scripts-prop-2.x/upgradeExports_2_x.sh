#!/bin/bash
# shellcheck source=/dev/null
# shellcheck disable=SC2154

configFile=$(cat "$1")
echo "Start updating nifi configurations using configuration file - $1"

for expflow in "${listForUpdate[@]}"; do
    echo "Updating property names based on mapping file for flow - $expflow"
    tmp=$(mktemp)
    jq --argjson delta "$configFile" '.component.type as $type |
        if $delta[$type] != null then
            .component.properties |= with_entries(
                .key as $k |
                if ($delta[$type] | has($k)) then
                    if $delta[$type][$k] != null then
                        .key = $delta[$type][$k]
                    else
                        empty
                    end
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

echo "Finish updating nifi configurations"

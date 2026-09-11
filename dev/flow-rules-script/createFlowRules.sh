#!/bin/bash

handle_error() {
    echo "$1" >&2
    delete_tmp_file
    exit 1
}

delete_tmp_file() {
    rm -f "$TMP_RULE_TYPES" "$TMP_EXISTING" "$TMP_BODY" "$TMP_RESPONSE"
}

TMP_RULE_TYPES="./flow-analysis-rule-types.json"
TMP_EXISTING="./flow-analysis-rules-existing.json"
TMP_BODY="./flow-analysis-rule-body.json"
TMP_RESPONSE="./flow-analysis-rule-response.json"

configPath=$1

NIFI_TARGET_URL="${NIFI_TARGET_URL:-https://localhost:8443}"
NIFI_CERT="${NIFI_CERT:-}"

# call_nifi_api <method> <api-path> <body-file-or-empty> <output-file>
# prints the HTTP response code, writes the response body to <output-file>
call_nifi_api() {
    local method="$1" apiPath="$2" bodyFile="$3" outFile="$4"
    local dataArg=""
    if [ -n "$bodyFile" ]; then
        dataArg="-H 'Content-Type: application/json' --data @$bodyFile"
    fi
    eval curl -sS -w '%{response_code}' -o "$outFile" -X "$method" \
        "$dataArg" "$NIFI_CERT" "$NIFI_TARGET_URL/nifi-api$apiPath"
}

enable_rule() {
    local ruleId="$1" version="$2" name="$3"
    jq -n --argjson version "$version" \
        '{ revision: { version: $version }, disconnectedNodeAcknowledged: true, state: "ENABLED" }' > "$TMP_BODY"

    respCode=$(call_nifi_api PUT "/controller/flow-analysis-rules/$ruleId/run-status" "$TMP_BODY" "$TMP_RESPONSE")
    if [ "$respCode" != "200" ]; then
        echo "Response body:" >&2
        cat "$TMP_RESPONSE" >&2
        handle_error "Error: rule '$name' could not be enabled. Response code = $respCode."
    fi
    echo "  Enabled rule '$name'."
}

wait_for_validation() {
    local ruleId="$1"
    local attempt=0
    local maxAttempts=10
    local sleepSeconds=1

    while :; do
        respCode=$(call_nifi_api GET "/controller/flow-analysis-rules/$ruleId" "" "$TMP_RESPONSE")
        if [ "$respCode" != "200" ]; then
            echo "Response body:" >&2
            cat "$TMP_RESPONSE" >&2
            handle_error "Error: failed to GET /nifi-api/controller/flow-analysis-rules/$ruleId. Response code = $respCode."
        fi
        if [ "$(jq -r '.component.validationStatus // "UNKNOWN"' "$TMP_RESPONSE")" != "VALIDATING" ]; then
            return
        fi
        attempt=$((attempt + 1))
        if [ "$attempt" -ge "$maxAttempts" ]; then
            handle_error "Error: rule id '$ruleId' is still VALIDATING after ${maxAttempts}s, giving up."
        fi
        sleep "$sleepSeconds"
    done
}

#Validate inputs
if [ -z "$configPath" ]; then
    handle_error "Error: path to the configuration file is not set. Usage: bash createFlowRules.sh <pathToConfig>"
fi

if [ ! -f "$configPath" ]; then
    handle_error "Error: configuration file '$configPath' does not exist."
fi

#Read the installed flow analysis rule types (type -> bundle mapping)
respCode=$(call_nifi_api GET "/flow/flow-analysis-rule-types" "" "$TMP_RULE_TYPES")
if [ "$respCode" != "200" ]; then
    echo "Response body:" >&2
    cat "$TMP_RULE_TYPES" >&2
    handle_error "Error: failed to GET /nifi-api/flow/flow-analysis-rule-types. Response code = $respCode."
fi

# Existing rules, to skip the ones already created (match by Name)
respCode=$(call_nifi_api GET "/controller/flow-analysis-rules" "" "$TMP_EXISTING")
if [ "$respCode" != "200" ]; then
    echo "Response body:" >&2
    cat "$TMP_EXISTING" >&2
    handle_error "Error: failed to GET /nifi-api/controller/flow-analysis-rules. Response code = $respCode."
fi

created=0
skipped=0

#Process the config and create the rules
while read -r entry; do
    name=$(echo "$entry" | jq -r '.Name // empty')
    type=$(echo "$entry" | jq -r '.Type // empty')
    policyRaw=$(echo "$entry" | jq -r '.Policy // empty')

    if [ -z "$name" ] || [ -z "$type" ] || [ -z "$policyRaw" ]; then
        handle_error "Error: each config entry must define 'Name', 'Type' and 'Policy'. Offending entry: $entry"
    fi

    case "$(echo "$policyRaw" | tr '[:lower:]' '[:upper:]')" in
        WARN) policy="WARN" ;;
        ENFORCE) policy="ENFORCE" ;;
        *) handle_error "Error: rule '$name' has invalid Policy '$policyRaw'. Expected 'Warn' or 'Enforce'." ;;
    esac

    # Skip if a rule with this Name already exists. The script never updates the properties of an
    # existing rule; if it is DISABLED but VALID, a re-run repairs it by enabling it.
    existingRule=$(jq -c --arg name "$name" \
        '[.flowAnalysisRules[]? | select(.component.name == $name)] | first // empty' "$TMP_EXISTING")
    if [ -n "$existingRule" ]; then
        existingState=$(echo "$existingRule" | jq -r '.component.state // "UNKNOWN"')
        existingValidationStatus=$(echo "$existingRule" | jq -r '.component.validationStatus // "UNKNOWN"')
        echo "Rule named '$name' already exists (state = $existingState," \
            "validationStatus = $existingValidationStatus), skipping."
        if [ "$existingState" = "DISABLED" ] && [ "$existingValidationStatus" = "VALID" ]; then
            existingId=$(echo "$existingRule" | jq -r '.id')
            existingVersion=$(echo "$existingRule" | jq -r '.revision.version')
            enable_rule "$existingId" "$existingVersion" "$name"
        fi
        skipped=$((skipped + 1))
        continue
    fi

    # Resolve the bundle for the rule type
    bundle=$(jq -c --arg type "$type" \
        '[.flowAnalysisRuleTypes[] | select(.type == $type) | .bundle] | first // empty' "$TMP_RULE_TYPES")
    if [ -z "$bundle" ]; then
        handle_error "Error: rule type '$type' (rule '$name') is not installed in the target NiFi."
    fi

    # Build the properties object from the Property array
    properties=$(echo "$entry" | jq -c '[.Property[]? | {(.name): .value}] | add // {}')

    # Create request body
    jq -n \
        --arg type "$type" \
        --arg name "$name" \
        --arg policy "$policy" \
        --argjson bundle "$bundle" \
        --argjson properties "$properties" \
        '{
            revision: { version: 0 },
            disconnectedNodeAcknowledged: true,
            component: {
                type: $type,
                bundle: $bundle,
                name: $name,
                enforcementPolicy: $policy,
                properties: $properties
            }
        }' > "$TMP_BODY"

    respCode=$(call_nifi_api POST "/controller/flow-analysis-rules" "$TMP_BODY" "$TMP_RESPONSE")
    if [ "$respCode" != "201" ]; then
        echo "Response body:" >&2
        cat "$TMP_RESPONSE" >&2
        handle_error "Error: failed to create rule '$name'. Response code = $respCode."
    fi

    ruleId=$(jq -r '.id' "$TMP_RESPONSE")
    echo "Created rule '$name' (id = $ruleId, policy = $policy)."

    # NiFi validates the new rule asynchronously, so the POST response's validationStatus is not
    # reliable; wait_for_validation polls the rule until validation settles.
    wait_for_validation "$ruleId"
    ruleVersion=$(jq -r '.revision.version' "$TMP_RESPONSE")
    validationStatus=$(jq -r '.component.validationStatus // "UNKNOWN"' "$TMP_RESPONSE")

    if [ "$validationStatus" != "VALID" ]; then
        echo "  Warning: rule '$name' is $validationStatus, leaving it DISABLED. Validation errors:" >&2
        jq -r '.component.validationErrors[]? | "    - " + .' "$TMP_RESPONSE" >&2
        created=$((created + 1))
        continue
    fi

    enable_rule "$ruleId" "$ruleVersion" "$name"
    created=$((created + 1))
done < <(jq -c '.[]' "$configPath")

delete_tmp_file

echo "Done. Created: $created, skipped (already existed): $skipped."

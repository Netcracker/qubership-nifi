#!/bin/bash

handle_error() {
    echo "$1" >&2
    delete_tmp_file
    exit 1
}

delete_tmp_file() {
    rm -f "$TMP_RULE_TYPES" "$TMP_EXISTING" "$TMP_BODY" "$TMP_RESPONSE" "$TMP_CONSUL"
}

TMP_RULE_TYPES="./flow-analysis-rule-types.json"
TMP_EXISTING="./flow-analysis-rules-existing.json"
TMP_BODY="./flow-analysis-rule-body.json"
TMP_RESPONSE="./flow-analysis-rule-response.json"
TMP_CONSUL="./flow-analysis-rules-consul.json"

configPath=$1

NIFI_TARGET_URL="${NIFI_TARGET_URL:-https://localhost:8443}"
NIFI_CERT="${NIFI_CERT:-}"
CONSUL_URL="${CONSUL_URL:-}"
NAMESPACE="${NAMESPACE:-local}"
# The leaf must not start with "nifi" or "logger.": the Consul integration of the qubership-nifi
# image copies every key of this folder with the first prefix into the nifi.properties it generates,
# and turns every key with the second into a logback.xml logger. The value here is a multi-line JSON
# array, which fits neither.
CONSUL_KEY="config/$NAMESPACE/qubership-nifi/flow-analysis-rules"

# Path the rules are read from, set by resolve_config.
effectiveConfig=""

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

# Returns the Consul ACL token used to read the configuration. Replace the body to read the token
# from somewhere else, such as a secret store. An empty result sends the request to Consul without
# an X-Consul-Token header, which is what an installation with ACLs disabled needs.
get_consul_token() {
    echo "${CONSUL_ACL_TOKEN:-}"
}

# call_consul_api <base-url> <kv-path> <output-file>
# prints the HTTP response code, writes the response body to <output-file>
call_consul_api() {
    local baseUrl="$1" kvPath="$2" outFile="$3"
    local token
    token=$(get_consul_token)
    # ?raw returns the stored value as it is, rather than base64-encoded inside a JSON envelope.
    local url="$baseUrl/v1/kv/$kvPath?raw"
    if [ -n "$token" ]; then
        # curl reads the header from stdin, so the token stays off its command line, which any user
        # of the machine can list.
        printf 'X-Consul-Token: %s\n' "$token" \
            | curl -sS -w '%{response_code}' -o "$outFile" --header @- "$url"
    else
        curl -sS -w '%{response_code}' -o "$outFile" "$url"
    fi
}

# Sets effectiveConfig to the configuration the rules are read from: the Consul key when CONSUL_URL
# is set and the key exists, and the file given on the command line when CONSUL_URL is empty or
# Consul answers 404. A Consul that cannot be reached, any other response code, and a key whose
# value is not a JSON array each end the run instead.
resolve_config() {
    effectiveConfig="$configPath"

    if [ -z "$CONSUL_URL" ]; then
        echo "CONSUL_URL is not set. Reading the rules from '$configPath'."
        return
    fi

    # CONSUL_URL is documented both as '<host>:<port>' and as '<scheme>://<host>:<port>'.
    local baseUrl="$CONSUL_URL"
    case "$baseUrl" in
        *://*) ;;
        *) baseUrl="http://$baseUrl" ;;
    esac

    local respCode
    respCode=$(call_consul_api "$baseUrl" "$CONSUL_KEY" "$TMP_CONSUL") \
        || handle_error "Error: failed to reach Consul at '$baseUrl'."

    if [ "$respCode" = "404" ]; then
        echo "Consul has no key '$CONSUL_KEY'. Reading the rules from '$configPath'."
        return
    fi
    if [ "$respCode" != "200" ]; then
        echo "Response body:" >&2
        cat "$TMP_CONSUL" >&2
        handle_error "Error: failed to read '$CONSUL_KEY' from Consul. Response code = $respCode."
    fi

    # A key that exists but holds something unusable is a misconfiguration, so it is reported
    # instead of falling back to the file, which would hide it.
    if ! jq -e 'type == "array"' "$TMP_CONSUL" > /dev/null 2>&1; then
        handle_error "Error: the value of '$CONSUL_KEY' in Consul is not a JSON array of rules."
    fi

    echo "Reading the rules from Consul key '$CONSUL_KEY'."
    effectiveConfig="$TMP_CONSUL"
}

# set_rule_state <rule-id> <revision-version> <name> <state>
# leaves the response in $TMP_RESPONSE
set_rule_state() {
    local ruleId="$1" version="$2" name="$3" state="$4"
    jq -n --argjson version "$version" --arg state "$state" \
        '{ revision: { version: $version }, disconnectedNodeAcknowledged: true, state: $state }' > "$TMP_BODY"

    respCode=$(call_nifi_api PUT "/controller/flow-analysis-rules/$ruleId/run-status" "$TMP_BODY" "$TMP_RESPONSE")
    if [ "$respCode" != "200" ]; then
        echo "Response body:" >&2
        cat "$TMP_RESPONSE" >&2
        handle_error "Error: rule '$name' could not be set to $state. Response code = $respCode."
    fi
}

enable_rule() {
    set_rule_state "$1" "$2" "$3" "ENABLED"
    echo "  Enabled rule '$3'."
}

disable_rule() {
    set_rule_state "$1" "$2" "$3" "DISABLED"
    echo "  Disabled rule '$3'."
}

# update_rule <rule-id> <revision-version> <name> <policy> <properties-json>
# sets the enforcement policy and the given properties on a rule that is already DISABLED
#
# NiFi merges the properties map into the rule, so a property the map leaves out keeps its value.
update_rule() {
    local ruleId="$1" version="$2" name="$3" policy="$4" properties="$5"
    jq -n \
        --arg id "$ruleId" \
        --arg name "$name" \
        --arg policy "$policy" \
        --argjson version "$version" \
        --argjson properties "$properties" \
        '{
            revision: { version: $version },
            disconnectedNodeAcknowledged: true,
            component: {
                id: $id,
                name: $name,
                enforcementPolicy: $policy,
                properties: $properties
            }
        }' > "$TMP_BODY"

    respCode=$(call_nifi_api PUT "/controller/flow-analysis-rules/$ruleId" "$TMP_BODY" "$TMP_RESPONSE")
    if [ "$respCode" != "200" ]; then
        echo "Response body:" >&2
        cat "$TMP_RESPONSE" >&2
        handle_error "Error: failed to update rule '$name'. Response code = $respCode."
    fi
    echo "  Updated rule '$name'."
}

# describe_differences <existing-rule-json> <policy> <properties-json>
# prints an indented bullet per configured value that differs from the live rule, and nothing when
# they match
#
# Only the configured properties are compared. The live properties map carries every property
# descriptor of the rule type, including the ones left at their default, so comparing the whole map
# would report a difference on every run.
describe_differences() {
    local existingRule="$1" policy="$2" properties="$3"
    echo "$existingRule" | jq -r \
        --arg policy "$policy" \
        --argjson properties "$properties" \
        '(.component.properties // {}) as $live
        | [ if .component.enforcementPolicy != $policy
            then "  - Policy: \(.component.enforcementPolicy) -> \($policy)"
            else empty end ]
        + [ $properties | to_entries[]
            | select(($live[.key] // null) != .value)
            | "  - \(.key): \($live[.key] // "not set") -> \(.value)" ]
        | .[]'
}

# wait_for_validation <rule-id>
# leaves the last response in $TMP_RESPONSE
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

# wait_for_state <rule-id> <state>
# leaves the last response in $TMP_RESPONSE
wait_for_state() {
    local ruleId="$1" targetState="$2"
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
        if [ "$(jq -r '.component.state // "UNKNOWN"' "$TMP_RESPONSE")" = "$targetState" ]; then
            return
        fi
        attempt=$((attempt + 1))
        if [ "$attempt" -ge "$maxAttempts" ]; then
            handle_error "Error: rule id '$ruleId' did not reach $targetState after ${maxAttempts}s, giving up."
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

#Choose between the Consul key and the configuration file
resolve_config

if ! jq -e 'type == "array"' "$effectiveConfig" > /dev/null 2>&1; then
    handle_error "Error: the configuration file '$configPath' is not a JSON array of rules."
fi
# The loop matches entries by Name against the rules read once before it starts, so a second entry
# with the same Name would create a duplicate rule or update the first one with a stale revision.
if ! jq -e '[.[].Name | select(. != null)] | length == (unique | length)' "$effectiveConfig" > /dev/null; then
    duplicateNames=$(jq -r '[.[].Name | select(. != null)] | group_by(.) | map(select(length > 1) | .[0])
        | join(", ")' "$effectiveConfig")
    handle_error "Error: the configuration lists more than one rule named: $duplicateNames."
fi
# NiFi keeps the current value of a required property when an update sets it to null, so a null
# cannot reset a property, and such an entry would be updated again on every run.
if ! jq -e 'all(.[]; all(.Property[]?; .value != null))' "$effectiveConfig" > /dev/null; then
    nullProperties=$(jq -r '[.[] | .Name as $rule | .Property[]? | select(.value == null)
        | "\(.name) (rule \($rule))"] | join(", ")' "$effectiveConfig")
    handle_error "Error: the configuration sets a property value to null: $nullProperties. Write the property's default value instead."
fi

#Read the installed flow analysis rule types (type -> bundle mapping)
respCode=$(call_nifi_api GET "/flow/flow-analysis-rule-types" "" "$TMP_RULE_TYPES")
if [ "$respCode" != "200" ]; then
    echo "Response body:" >&2
    cat "$TMP_RULE_TYPES" >&2
    handle_error "Error: failed to GET /nifi-api/flow/flow-analysis-rule-types. Response code = $respCode."
fi

# Existing rules, to match the entries against the rules already created (match by Name)
respCode=$(call_nifi_api GET "/controller/flow-analysis-rules" "" "$TMP_EXISTING")
if [ "$respCode" != "200" ]; then
    echo "Response body:" >&2
    cat "$TMP_EXISTING" >&2
    handle_error "Error: failed to GET /nifi-api/controller/flow-analysis-rules. Response code = $respCode."
fi

created=0
updated=0
skipped=0
# Names of the rules this run leaves DISABLED because NiFi reports them as not VALID.
leftDisabled=()

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

    # Build the properties object from the Property array. NiFi stores every property value as a
    # string, so a value the configuration writes as a number or a boolean is converted here; left
    # as it is, it would differ from the stored value on every run.
    properties=$(echo "$entry" | jq -c '[.Property[]? | {(.name): (.value | tostring)}] | add // {}')

    # Where a rule with this Name already exists, NiFi accepts a change to its configured values
    # only while the rule is disabled, so a rule that differs is disabled, updated and enabled
    # again. A rule that matches is left as it is, except that a DISABLED but VALID one is enabled,
    # which repairs a rule left disabled by an earlier failed run, and a DISABLED one that is not
    # VALID fails the run.
    existingRule=$(jq -c --arg name "$name" \
        '[.flowAnalysisRules[]? | select(.component.name == $name)] | first // empty' "$TMP_EXISTING")
    if [ -n "$existingRule" ]; then
        existingId=$(echo "$existingRule" | jq -r '.id')
        existingType=$(echo "$existingRule" | jq -r '.component.type')
        existingState=$(echo "$existingRule" | jq -r '.component.state // "UNKNOWN"')
        existingValidationStatus=$(echo "$existingRule" | jq -r '.component.validationStatus // "UNKNOWN"')
        existingVersion=$(echo "$existingRule" | jq -r '.revision.version')

        # The type of a rule is fixed once NiFi creates it.
        if [ "$existingType" != "$type" ]; then
            handle_error "Error: rule '$name' already exists with type '$existingType', but the configuration sets type '$type'. Delete the rule and run the script again."
        fi

        differences=$(describe_differences "$existingRule" "$policy" "$properties")
        if [ -z "$differences" ]; then
            echo "Rule '$name' already matches the configuration (state = $existingState," \
                "validationStatus = $existingValidationStatus), skipping."
            if [ "$existingState" = "DISABLED" ]; then
                if [ "$existingValidationStatus" = "VALIDATING" ]; then
                    wait_for_validation "$existingId"
                    existingRule=$(jq -c '.' "$TMP_RESPONSE")
                    existingVersion=$(echo "$existingRule" | jq -r '.revision.version')
                    existingValidationStatus=$(echo "$existingRule" | jq -r '.component.validationStatus // "UNKNOWN"')
                fi
                if [ "$existingValidationStatus" = "VALID" ]; then
                    enable_rule "$existingId" "$existingVersion" "$name"
                else
                    echo "  Warning: rule '$name' is $existingValidationStatus, leaving it DISABLED." \
                        "Validation errors:" >&2
                    echo "$existingRule" | jq -r '.component.validationErrors[]? | "    - " + .' >&2
                    leftDisabled+=("$name")
                fi
            fi
            skipped=$((skipped + 1))
            continue
        fi

        echo "Rule '$name' differs from the configuration, updating:"
        echo "$differences"

        if [ "$existingState" != "DISABLED" ]; then
            disable_rule "$existingId" "$existingVersion" "$name"
            wait_for_state "$existingId" "DISABLED"
            existingVersion=$(jq -r '.revision.version' "$TMP_RESPONSE")
        fi

        update_rule "$existingId" "$existingVersion" "$name" "$policy" "$properties"

        # NiFi revalidates the updated rule asynchronously, so the PUT response's validationStatus
        # is not reliable; wait_for_validation polls the rule until validation settles.
        wait_for_validation "$existingId"
        existingVersion=$(jq -r '.revision.version' "$TMP_RESPONSE")
        validationStatus=$(jq -r '.component.validationStatus // "UNKNOWN"' "$TMP_RESPONSE")

        if [ "$validationStatus" != "VALID" ]; then
            echo "  Warning: rule '$name' is $validationStatus, leaving it DISABLED. Validation errors:" >&2
            jq -r '.component.validationErrors[]? | "    - " + .' "$TMP_RESPONSE" >&2
            leftDisabled+=("$name")
            updated=$((updated + 1))
            continue
        fi

        enable_rule "$existingId" "$existingVersion" "$name"
        updated=$((updated + 1))
        continue
    fi

    # Resolve the bundle for the rule type
    bundle=$(jq -c --arg type "$type" \
        '[.flowAnalysisRuleTypes[] | select(.type == $type) | .bundle] | first // empty' "$TMP_RULE_TYPES")
    if [ -z "$bundle" ]; then
        handle_error "Error: rule type '$type' (rule '$name') is not installed in the target NiFi."
    fi

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
        leftDisabled+=("$name")
        created=$((created + 1))
        continue
    fi

    enable_rule "$ruleId" "$ruleVersion" "$name"
    created=$((created + 1))
done < <(jq -c '.[]' "$effectiveConfig")

delete_tmp_file

echo "Done. Created: $created, updated: $updated, skipped (unchanged): $skipped."

# A rule that is not VALID enforces nothing, so a run that leaves one DISABLED fails. The check runs
# after the loop, so every other entry is still applied.
if [ "${#leftDisabled[@]}" -gt 0 ]; then
    disabledNames=$(printf "'%s', " "${leftDisabled[@]}")
    echo "Error: ${#leftDisabled[@]} rule(s) left DISABLED because they are not VALID: ${disabledNames%, }." >&2
    exit 1
fi

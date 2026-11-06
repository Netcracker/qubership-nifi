#!/usr/bin/env bash
# Searches the Apache NiFi Jira for Major bugs that affect a NiFi version and match the known-issues keywords.
# Writes all result pages, merged, to upgrade-temp-data/known-issues/jira-issues-<version>.json.
set -Eeuo pipefail
if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Usage: $0 <target_nifi_version> [<component_export_dir>]"
    echo "Example: $0 2.12.0 upgrade-temp-data/nifi-property-exports/2.12.0"
    exit 1
fi
TARGET="$1"
EXPORT_DIR="${2:-upgrade-temp-data/nifi-property-exports/${TARGET}}"
OUT_DIR="upgrade-temp-data/known-issues"
OUT_FILE="${OUT_DIR}/jira-issues-${TARGET}.json"
SEARCH_URL="https://issues.apache.org/jira/rest/api/2/search"
KEYWORDS="kafka redis json sql http request s3"
PAGE_SIZE="${JIRA_PAGE_SIZE:-100}"

if [[ ! -d "$EXPORT_DIR" ]]; then
    echo "Error: component export directory '${EXPORT_DIR}' not found. Run the component export step first."
    exit 1
fi
mkdir -p "$OUT_DIR"

# Jira matches whole words only, so "sql" doesn't match "ExecuteSQL". Add every exported component name that
# contains a keyword as a search term of its own.
KEYWORD_REGEX=$(tr ' ' '|' <<< "$KEYWORDS")
TERMS=$( { tr ' ' '\n' <<< "$KEYWORDS"
    find "$EXPORT_DIR" -name '*.json' -exec basename {} .json \; | grep -i -E "$KEYWORD_REGEX" || true
} | sort -u)
TEXT_CONDITION=$(jq -R . <<< "$TERMS" \
    | jq -rs 'map("summary ~ \(.) OR description ~ \(.)") | join(" OR ")')
JQL="project = NIFI AND issuetype = Bug AND priority = Major
AND (affectedVersion = \"${TARGET}\"
  OR (fixVersion > \"${TARGET}\" AND (affectedVersion is EMPTY OR affectedVersion <= \"${TARGET}\")))
AND (${TEXT_CONDITION}) ORDER BY key"

PAGES_DIR=$(mktemp -d)
trap 'rm -rf "$PAGES_DIR"' EXIT
start=0
while :; do
    page="${PAGES_DIR}/page-${start}.json"
    jq -n --arg jql "$JQL" --argjson startAt "$start" --argjson maxResults "$PAGE_SIZE" \
        '{jql: $jql, startAt: $startAt, maxResults: $maxResults,
          fields: ["summary", "description", "status", "resolution", "versions", "fixVersions", "issuelinks"]}' \
        | curl -s -X POST -H "Content-Type: application/json" --data @- "$SEARCH_URL" > "$page"
    if jq -e '.errorMessages // empty | length > 0' "$page" > /dev/null; then
        echo "Error: Jira rejected the search:"
        jq -r '.errorMessages[]' "$page"
        exit 1
    fi
    total=$(jq '.total' "$page")
    start=$((start + PAGE_SIZE))
    [[ "$start" -lt "$total" ]] || break
done

jq -s '{total: .[0].total, jql: $jql, issues: (map(.issues) | add)}' --arg jql "$JQL" "$PAGES_DIR"/page-*.json \
    > "$OUT_FILE"
jq '{total, returned: (.issues | length)}' "$OUT_FILE"
echo "Search terms: $(wc -l <<< "$TERMS")"
echo "Saved to ${OUT_FILE}"

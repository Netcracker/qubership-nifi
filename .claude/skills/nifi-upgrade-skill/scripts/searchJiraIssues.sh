#!/usr/bin/env bash
# Searches the Apache NiFi Jira for Blocker, Critical, and Major bugs that match the known-issues keywords and affect
# the target NiFi version, or are still open and affect a version from the current one to the target one.
# Replaces each duplicate with its original and writes the result to
# upgrade-temp-data/known-issues/jira-issues-<version>.json.
set -Eeuo pipefail
if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <current_nifi_version> <target_nifi_version>"
    echo "Example: $0 2.7.2 2.12.0"
    exit 1
fi
CURRENT="$1"
TARGET="$2"
EXPORT_DIR="upgrade-temp-data/nifi-property-exports/${TARGET}"
OUT_DIR="upgrade-temp-data/known-issues"
OUT_FILE="${OUT_DIR}/jira-issues-${TARGET}.json"
REPORT_FILE="${OUT_DIR}/known-issues-${TARGET}.md"
SEARCH_URL="https://issues.apache.org/jira/rest/api/2/search"
KEYWORDS="kafka redis json sql http request s3"
NL=$'\n'

# Prints the error message $1, writes it to the known-issues report in place of the issue table, and exits with an
# error.
fail() {
    local message="$1"
    echo "$message" >&2
    mkdir -p "$OUT_DIR"
    printf '# Known issues for Apache NiFi %s\n\nThe Jira search failed:\n\n```text\n%s\n```\n' \
        "$TARGET" "$message" > "$REPORT_FILE"
    exit 1
}

# Prints the value of the environment variable $1 as a decimal number, or $2 if the variable is unset.
# Exits with an error if the value isn't a positive integer.
positive_int_env() {
    local value="${!1:-$2}"
    if ! [[ "$value" =~ ^[0-9]+$ ]] || (( 10#$value == 0 )); then
        fail "Error: $1 must be a positive integer, got '${value}'."
    fi
    echo $((10#$value))
}
PAGE_SIZE=$(positive_int_env JIRA_PAGE_SIZE 100)
# Timeout of one Jira request, in seconds.
TIMEOUT=$(positive_int_env JIRA_TIMEOUT 30)

if [[ ! -d "$EXPORT_DIR" ]]; then
    fail "Error: component export directory '${EXPORT_DIR}' not found. Run the component export step first."
fi
mkdir -p "$OUT_DIR"

# Jira matches whole words only, so "sql" doesn't match "ExecuteSQL". Add every exported component name that
# contains a keyword as a search term of its own.
KEYWORD_REGEX=$(tr ' ' '|' <<< "$KEYWORDS")
TERMS=$( { tr ' ' '\n' <<< "$KEYWORDS"
    find "$EXPORT_DIR" -name '*.json' -exec basename {} .json \; | grep -i -E "$KEYWORD_REGEX" || true
} | sort -u)
# Match the summary and the description only: "text ~" also searches comments and finds unrelated issues, such as
# "request" in "pull request".
TEXT_CONDITION=$(jq -R . <<< "$TERMS" \
    | jq -rs 'map("summary ~ \(.) OR description ~ \(.)") | join(" OR ")')
# Resolutions with which the project found no bug in NiFi.
NOT_A_BUG='"Information Provided", "Not A Problem", "Not A Bug", "Invalid", "Cannot Reproduce", "Works for Me",
  "Incomplete"'
# A bug counts if it affects the target version, if it's fixed later and its reporter left Affects Version/s empty or
# named an older version, or if it's still open and affects a version from the current one to the target one.
JQL="project = NIFI AND issuetype = Bug AND priority in (Blocker, Critical, Major)
AND (resolution is EMPTY OR resolution not in (${NOT_A_BUG}))
AND (affectedVersion = \"${TARGET}\"
  OR (fixVersion > \"${TARGET}\" AND (affectedVersion is EMPTY OR affectedVersion <= \"${TARGET}\"))
  OR (affectedVersion >= \"${CURRENT}\" AND affectedVersion <= \"${TARGET}\" AND resolution is EMPTY))
AND (${TEXT_CONDITION}) ORDER BY key"

PAGES_DIR=$(mktemp -d)
trap 'rm -rf "$PAGES_DIR"' EXIT

# Runs the JQL query $1, fetches every result page, and prints the issues of all pages as one JSON array.
# Exits with an error if Jira rejects the query, the request fails, or fewer issues arrive than the Jira total.
search_all() {
    local jql="$1" start=0 total returned page curl_status
    rm -f "$PAGES_DIR"/page-*.json
    while :; do
        page="${PAGES_DIR}/page-${start}.json"
        curl_status=0
        jq -n --arg jql "$jql" --argjson startAt "$start" --argjson maxResults "$PAGE_SIZE" \
            '{jql: $jql, startAt: $startAt, maxResults: $maxResults,
              fields: ["summary", "description", "status", "resolution", "versions", "fixVersions", "issuelinks"]}' \
            | curl -sS --fail-with-body --max-time "$TIMEOUT" -X POST -H "Content-Type: application/json" \
                --data @- -o "$page" "$SEARCH_URL" || curl_status=$?
        if jq -e '.errorMessages // empty | length > 0' "$page" > /dev/null 2>&1; then
            fail "Error: Jira rejected the search:${NL}$(jq -r '.errorMessages[]' "$page")"
        fi
        if [[ "$curl_status" -ne 0 ]]; then
            fail "Error: the Jira request failed with curl exit code ${curl_status}.${NL}$(
                head -c 500 "$page" 2> /dev/null)"
        fi
        if ! jq -e '(.total | type == "number") and (.issues | type == "array")' "$page" > /dev/null 2>&1; then
            fail "Error: the Jira response isn't a search result:${NL}$(head -c 500 "$page")"
        fi
        total=$(jq '.total' "$page")
        # Jira may return fewer issues than maxResults, so move by the number of issues actually returned.
        returned=$(jq '.issues | length' "$page")
        start=$((start + returned))
        [[ "$returned" -gt 0 && "$start" -lt "$total" ]] || break
    done
    if [[ "$start" -ne "$total" ]]; then
        fail "Error: Jira returned ${start} issues of ${total}; the search result is incomplete."
    fi
    jq -s 'map(.issues) | add // []' "$PAGES_DIR"/page-*.json
}

# Keys of the issues that the issues resolved as Duplicate are linked to by a Duplicate link, in either direction
# (reporters link both ways), and that aren't in the list yet.
DUPLICATE_LINK_DEFS='
    def is_duplicate: .fields.resolution.name == "Duplicate";
    def duplicate_links: [.fields.issuelinks[]? | select(.type.name == "Duplicate")
        | (.outwardIssue // .inwardIssue).key | select(type == "string" and test("^NIFI-[0-9]+$"))];
'

ISSUES_FILE="${PAGES_DIR}/issues.json"
search_all "$JQL" > "$ISSUES_FILE"
echo "Issues matching the search: $(jq length "$ISSUES_FILE")"

# A duplicate tells less than its original, which the search may miss: the original can have another priority,
# other versions, or other words. Add the originals; originals that are duplicates themselves are followed too.
for _ in 1 2 3 4 5; do
    MISSING=$(jq -r "$DUPLICATE_LINK_DEFS"'
        (map(.key)) as $keys
        | [.[] | select(is_duplicate) | duplicate_links[]] | unique - $keys | join(", ")' "$ISSUES_FILE")
    [[ -n "$MISSING" ]] || break
    echo "Adding the originals of duplicates: ${MISSING}"
    search_all "key in (${MISSING}) ORDER BY key" > "${PAGES_DIR}/originals.json"
    jq -s 'add' "$ISSUES_FILE" "${PAGES_DIR}/originals.json" > "${PAGES_DIR}/merged.json"
    mv "${PAGES_DIR}/merged.json" "$ISSUES_FILE"
done

# First drop the originals that the project found no bug in: the main query already excludes such issues, the
# originals query doesn't. Then drop a duplicate when its Duplicate links lead, directly or through other duplicates,
# to an issue left in the list that isn't a duplicate. A duplicate without such an issue stays, so no bug is lost.
jq -s "$DUPLICATE_LINK_DEFS"'
    # The key of the first issue on the Duplicate links from this issue that is not a duplicate, or null.
    def original($by; $seen):
        if is_duplicate | not then .key
        else first(duplicate_links[] as $k | select($by[$k] != null and ($seen | index($k) | not))
            | $by[$k] | original($by; $seen + [$k]) | select(. != null)) // null
        end;
    (.[0] | map(select(.fields.resolution.name as $r | $not_a_bug | index([$r]) | not))) as $all
    | ($all | map({(.key): .}) | add // {}) as $by
    | ($all | map(select(is_duplicate) | {key, original: original($by; [.key])} | select(.original != null)))
        as $replaced
    | ($replaced | map(.key)) as $dropped
    | ($all | map(select(.key as $k | $dropped | index($k) | not))
        | sort_by(.key | ltrimstr("NIFI-") | tonumber)) as $issues
    | {total: ($issues | length), date: $date, jql: $jql, keywords: ($keywords | split(" ")),
       duplicates: $replaced, issues: $issues}
' --arg date "$(date -u +%Y-%m-%d)" --arg jql "$JQL" --arg keywords "$KEYWORDS" --argjson not_a_bug "[${NOT_A_BUG}]" \
    "$ISSUES_FILE" > "$OUT_FILE"

jq -r '"Duplicates replaced by their originals: \(.duplicates | length)",
    (.duplicates[] | "  \(.key) -> \(.original)"), "Issues to analyze: \(.total)"' "$OUT_FILE"
echo "Search terms: $(wc -l <<< "$TERMS")"
echo "Saved to ${OUT_FILE}"

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
# One keyword per line.
KEYWORDS_FILE="$(dirname "${BASH_SOURCE[0]}")/../conf/known-issues-keywords.txt"
NL=$'\n'
# A report left by an earlier run must not pass for the result of this one.
rm -f "$REPORT_FILE"

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

if [[ ! -f "$KEYWORDS_FILE" ]]; then
    fail "Error: keyword file '${KEYWORDS_FILE}' not found."
fi
# The keywords go into a regular expression and into quotes in the JQL query: allow letters and digits only.
KEYWORDS=$(tr -d '\r' < "$KEYWORDS_FILE" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | grep -v '^$' | sort -u || true)
if [[ -z "$KEYWORDS" ]] || grep -qv '^[A-Za-z0-9]*$' <<< "$KEYWORDS"; then
    fail "Error: '${KEYWORDS_FILE}' must list one keyword of letters and digits per line."
fi

if [[ ! -d "$EXPORT_DIR" ]]; then
    fail "Error: component export directory '${EXPORT_DIR}' not found. Run the component export step first."
fi
mkdir -p "$OUT_DIR"

# Jira matches whole words only, so "sql" doesn't match "ExecuteSQL". Add every exported component name that
# contains a keyword as a search term of its own.
KEYWORD_REGEX=$(paste -sd '|' <<< "$KEYWORDS")
TERMS=$( { echo "$KEYWORDS"
    find "$EXPORT_DIR" -name '*.json' -exec basename {} .json \; | grep -i -E "$KEYWORD_REGEX" || true
} | sort -u)
# Match the summary and the description only: "text ~" also searches comments and finds unrelated issues, such as
# "request" in "pull request". Each term is quoted: Jira rejects a reserved JQL word, such as "file" or "string", that
# isn't in quotes.
TEXT_CONDITION=$(jq -R . <<< "$TERMS" \
    | jq -rs 'map("summary ~ \"\(.)\" OR description ~ \"\(.)\"") | join(" OR ")')
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
# With $2 set to false, Jira leaves out the issue keys it doesn't know instead of rejecting the query.
# Exits with an error if Jira rejects the query, the request fails, or fewer issues arrive than the Jira total.
search_all() {
    local jql="$1" validate="${2:-true}" start=0 total returned page curl_status
    rm -f "$PAGES_DIR"/page-*.json
    while :; do
        page="${PAGES_DIR}/page-${start}.json"
        curl_status=0
        jq -n --arg jql "$jql" --argjson startAt "$start" --argjson maxResults "$PAGE_SIZE" \
            --argjson validate "$validate" \
            '{jql: $jql, startAt: $startAt, maxResults: $maxResults, validateQuery: $validate,
              fields: ["summary", "description", "status", "resolution", "versions", "fixVersions", "issuelinks"]}' \
            | curl -sS --fail-with-body --max-time "$TIMEOUT" -X POST -H "Content-Type: application/json" \
                --data @- -o "$page" "$SEARCH_URL" || curl_status=$?
        if jq -e '.errorMessages // empty | length > 0' "$page" > /dev/null 2>&1; then
            fail "Error: Jira rejected the search:${NL}$(jq -r '.errorMessages[]' "$page")"
        fi
        # The response body can hold issue summaries and descriptions, which are untrusted text: the errors below
        # don't quote it, so that it never reaches the report or the agent that reads the error.
        if [[ "$curl_status" -ne 0 ]]; then
            fail "Error: the Jira request failed with curl exit code ${curl_status}."
        fi
        if ! jq -e '(.total | type == "number") and (.issues | type == "array")' "$page" > /dev/null 2>&1; then
            fail "Error: the Jira response isn't a search result."
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

# is_duplicate: the issue is resolved as Duplicate. duplicate_links: the keys of the issues that the issue is linked to
# by a Duplicate link, in either direction (reporters link both ways).
DUPLICATE_LINK_DEFS='
    def is_duplicate: .fields.resolution.name == "Duplicate";
    def duplicate_links: [.fields.issuelinks[]? | select(.type.name == "Duplicate")
        | (.outwardIssue // .inwardIssue).key | select(type == "string" and test("^NIFI-[0-9]+$"))];
'

ISSUES_FILE="${PAGES_DIR}/issues.json"
search_all "$JQL" > "$ISSUES_FILE"
echo "Issues matching the search: $(jq length "$ISSUES_FILE")"

# A duplicate tells less than its original, which the search may miss: the original can have another priority,
# other versions, or other words. Add the originals; originals that are duplicates themselves are followed too, one
# level of the duplicate chains per request, until no new original is left. Each key is requested once, so the loop
# ends even when the links form a cycle or Jira returns a moved issue under another key.
REQUESTED='[]'
while :; do
    MISSING=$(jq -cj --argjson requested "$REQUESTED" "$DUPLICATE_LINK_DEFS"'
        (map(.key)) as $keys
        | [.[] | select(is_duplicate) | duplicate_links[]] | unique - $keys - $requested' "$ISSUES_FILE")
    [[ "$MISSING" != "[]" ]] || break
    REQUESTED=$(jq -cj --argjson missing "$MISSING" '. + $missing' <<< "$REQUESTED")
    MISSING=$(jq -j 'join(", ")' <<< "$MISSING")
    echo "Adding the originals of duplicates: ${MISSING}"
    # A Duplicate link can lead to a deleted issue or one hidden from anonymous users: skip it instead of failing the
    # search. Its duplicate then has no original in the list and stays.
    search_all "key in (${MISSING}) ORDER BY key" false > "${PAGES_DIR}/originals.json"
    # Jira returns a moved issue under its new key. Keep only NIFI issues, which the report sorts by number, and don't
    # add an issue that is already in the list.
    jq -s 'add | map(select(.key | type == "string" and test("^NIFI-[0-9]+$"))) | unique_by(.key)' \
        "$ISSUES_FILE" "${PAGES_DIR}/originals.json" > "${PAGES_DIR}/merged.json"
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
    | {total: ($issues | length), date: $date, jql: $jql, keywords: ($keywords | split("\n")),
       duplicates: $replaced, issues: $issues}
' --arg date "$(date -u +%Y-%m-%d)" --arg jql "$JQL" --arg keywords "$KEYWORDS" --argjson not_a_bug "[${NOT_A_BUG}]" \
    "$ISSUES_FILE" > "$OUT_FILE"

jq -r '"Duplicates replaced by their originals: \(.duplicates | length)",
    (.duplicates[] | "  \(.key) -> \(.original)"), "Issues to analyze: \(.total)"' "$OUT_FILE"
echo "Search terms: $(wc -l <<< "$TERMS")"
echo "Saved to ${OUT_FILE}"

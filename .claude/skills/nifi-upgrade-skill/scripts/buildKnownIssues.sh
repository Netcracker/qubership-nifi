#!/usr/bin/env bash
# Checks the classification of the known issues returned by the nifi-jira-issue-analyst subagent, checks in Jira the
# version that introduced each regression, and joins the result with the Jira search result in
# upgrade-temp-data/known-issues/known-issues-<version>.md. The user reads the report and decides whether to accept
# the upgrade. The script, not the agent, copies issue summaries from Jira into the table, so the agent never reads
# them.
set -Eeuo pipefail
if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <target_nifi_version>"
    echo "Example: $0 2.12.0"
    exit 1
fi
TARGET="$1"
DIR="upgrade-temp-data/known-issues"
ISSUES_FILE="${DIR}/jira-issues-${TARGET}.json"
CLASSIFICATION_FILE="${DIR}/classification-${TARGET}.json"
REPORT_FILE="${DIR}/known-issues-${TARGET}.md"
ISSUE_URL="https://issues.apache.org/jira/rest/api/2/issue"
# Timeout of one Jira request, in seconds.
TIMEOUT="${JIRA_TIMEOUT:-30}"
if ! [[ "$TIMEOUT" =~ ^[0-9]+$ ]] || (( 10#$TIMEOUT == 0 )); then
    echo "Error: JIRA_TIMEOUT must be a positive integer, got '${TIMEOUT}'." >&2
    exit 1
fi

# Prints the error message $1, writes it to the known-issues report in place of the issue table, and exits with an
# error.
fail() {
    echo "$1" >&2
    mkdir -p "$DIR"
    printf '# Known issues for Apache NiFi %s\n\nThe known issues analysis failed:\n\n```text\n%s\n```\n' \
        "$TARGET" "$1" > "$REPORT_FILE"
    exit 1
}

for file in "$ISSUES_FILE" "$CLASSIFICATION_FILE"; do
    if [[ ! -f "$file" ]]; then
        fail "Error: '${file}' not found."
    fi
done
if ! jq -e '.keywords | type == "array"' "$ISSUES_FILE" > /dev/null; then
    fail "Error: '${ISSUES_FILE}' has no keyword list. Run searchJiraIssues.sh again."
fi
if ! jq -e . "$CLASSIFICATION_FILE" > /dev/null 2>&1; then
    fail "Error: '${CLASSIFICATION_FILE}' isn't valid JSON."
fi

# The classification comes from a subagent that read untrusted issue text. Accept only the fixed values, and report
# a problem by the key or the position of the entry, never by its content.
PROBLEMS=$(jq -r --slurpfile jira "$ISSUES_FILE" '
    def is_key: type == "string" and test("^NIFI-[0-9]+$");
    ($jira[0].issues | map(.key)) as $keys
    | $jira[0].keywords as $keywords
    | if type != "array" then "the classification isn'"'"'t a JSON array" else
        (to_entries[] | (.key + 1) as $n | .value as $e
            | (if ($e | type) == "object" and ($e.key | is_key) then $e.key else "entry \($n)" end) as $id
            | if ($e | type) != "object" then "\($id): not an object"
              elif ($e | keys) != (["impact", "introduced_by", "key", "keywords", "suspicious", "verdict"])
              then "\($id): wrong set of fields"
              else
                (if ($e.key | is_key) | not then "\($id): key isn'"'"'t NIFI-<digits>"
                 elif ($keys | index($e.key)) == null then "\($id): not in the search result" else empty end),
                (if ($e.verdict | IN("breaks-feature", "workaround", "not relevant")) | not
                 then "\($id): invalid verdict" else empty end),
                (if ($e.keywords | type) != "array" or ($e.keywords - $keywords) != []
                 then "\($id): keywords not from the keyword list" else empty end),
                (if ($e.introduced_by == "" or ($e.introduced_by | is_key)) | not
                 then "\($id): introduced_by isn'"'"'t empty or NIFI-<digits>" else empty end),
                (if ($e.suspicious | IN("yes", "no")) | not then "\($id): suspicious isn'"'"'t yes or no"
                 else empty end),
                (if ($e.impact | type) != "string" or ($e.impact | length) == 0 or ($e.impact | length) > 200
                    or ($e.impact | test("[\r\n]"))
                 then "\($id): impact isn'"'"'t one line of 1-200 characters" else empty end)
              end),
        (map(.key?) | group_by(.) | map(select(length > 1) | .[0]) | .[]
            | "\(if is_key then . else "an entry" end): classified more than once"),
        (($keys - map(.key?)) | .[] | "\(.): not classified")
      end
' "$CLASSIFICATION_FILE")
if [[ -n "$PROBLEMS" ]]; then
    fail "Error: the classification in ${CLASSIFICATION_FILE} has problems:"$'\n'"${PROBLEMS}"
fi

# A regression exists in the target version only if the issue that introduced it was fixed in the target version or
# earlier. Fetch the fix versions of each such issue; null means that they couldn't be fetched.
INTRODUCED='{}'
for key in $(jq -r '[.[].introduced_by | select(. != "")] | unique | .[]' "$CLASSIFICATION_FILE" | tr -d '\r'); do
    if body=$(curl -sS --fail-with-body --max-time "$TIMEOUT" "${ISSUE_URL}/${key}?fields=fixVersions") \
        && versions=$(jq -c '[.fields.fixVersions[]?.name]' <<< "$body" 2> /dev/null); then
        :
    else
        echo "Warning: couldn't fetch the fix versions of ${key}; its regression isn't checked." >&2
        versions=null
    fi
    INTRODUCED=$(jq -c --arg k "$key" --argjson v "$versions" '. + {($k): $v}' <<< "$INTRODUCED")
done

# Issue summaries and statuses are untrusted text: keep each on one line and escape the table separator.
REPORT_DEFS='
    def cell: tostring | gsub("[\r\n]+"; " ") | gsub("\\|"; "\\|");
    def link: "[\(.)](https://issues.apache.org/jira/browse/\(.))";
    def names: map(.name) | join(", ");
    # A sort key for versions such as 2.0.0-M4: a milestone sorts before its release.
    def vkey: (split("-") | .[0]) as $base | (split("-") | .[1] // "") as $milestone
        | (($base | split(".") | map(tonumber? // 0)) + [0, 0, 0])[0:3]
        + (if $milestone == "" then [1, 0] else [0, ($milestone | ltrimstr("M") | tonumber? // 0)] end);
    # The classification of the issue with the result of the regression check: verdict is the final verdict,
    # and introduced is the text of the "introduced by" cell.
    def checked: if .introduced_by == "" then . + {introduced: ""}
        else $introduced[.introduced_by] as $v | (.introduced_by | link) as $by
        | if $v == null then . + {introduced: "\($by), fix versions not fetched"}
          elif $v == [] then . + {introduced: "\($by), no fix version"}
          elif all($v[]; vkey > ($target | vkey))
          then . + {verdict: "not relevant", introduced: "\($by), fixed in \($v | join(", ")), after \($target)"}
              + (if .verdict == "not relevant" then {} else {classified: .verdict} end)
          else . + {introduced: "\($by), fixed in \($v | join(", "))"} end
        end;
    def rows: ($cls[0] | map({(.key): checked}) | add // {}) as $by_key
        | .issues | sort_by(.key | ltrimstr("NIFI-") | tonumber)
        | map(. as $i | $by_key[$i.key] + {
            summary: ($i.fields.summary // "" | cell),
            status: ($i.fields.status.name // "" | cell),
            resolution: ($i.fields.resolution.name // "Unresolved" | cell),
            affects: ($i.fields.versions // [] | names | cell),
            fixes: ($i.fields.fixVersions // [] | map(.name))});
'

jq -r --slurpfile cls "$CLASSIFICATION_FILE" --argjson introduced "$INTRODUCED" --arg target "$TARGET" \
    "$REPORT_DEFS"'
    . as $search | rows as $rows
    | "# Known issues for Apache NiFi \($target)", "",
      "- Target version: \($target)", "- Search date: \($search.date // "unknown")",
      "- Issues found: \($rows | length)",
      (if ($search.duplicates // []) == [] then empty else
        "- Duplicates replaced by their originals: "
            + ($search.duplicates | map("\(.key | link) -> \(.original | link)") | join(", ")) end),
      "",
      if ($rows | length) == 0 then "No known issues found." else
        ("| key | summary | status | resolution | affects versions | fix versions | matched keywords | introduced by"
            + " | verdict | suspicious content | impact |"),
        "|---|---|---|---|---|---|---|---|---|---|---|",
        ($rows[] | "| \(.key | link) | \(.summary) | \(.status) | \(.resolution) | \(.affects) | "
            + "\(.fixes | join(", ") | cell) | \(.keywords | join(", ")) | \(.introduced) | "
            + "\(.verdict)\(if .classified then " (classified as \(.classified))" else "" end) | "
            + "\(.suspicious) | \(.impact | cell) |")
      end,
      "", "## Suspicious content", "", "Issues with suspicious content, to read in Jira:", "",
      (($rows | map(select(.suspicious == "yes") | "- \(.key | link)")) as $suspicious
       | if $suspicious == [] then "- none" else $suspicious[] end)
' "$ISSUES_FILE" > "$REPORT_FILE"

echo "Saved to ${REPORT_FILE}"

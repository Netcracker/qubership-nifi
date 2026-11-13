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
# A report left by an earlier run must not pass for the result of this one.
rm -f "$REPORT_FILE"
SEARCH_URL="https://issues.apache.org/jira/rest/api/2/search"
# Prints the error message $1, writes it to the known-issues report in place of the issue table, and exits with an
# error.
fail() {
    echo "$1" >&2
    mkdir -p "$DIR"
    printf '# Known issues for Apache NiFi %s\n\nThe known issues analysis failed:\n\n```text\n%s\n```\n' \
        "$TARGET" "$1" > "$REPORT_FILE"
    exit 1
}

# Timeout of one Jira request, in seconds.
TIMEOUT="${JIRA_TIMEOUT:-30}"
if ! [[ "$TIMEOUT" =~ ^[0-9]+$ ]] || (( 10#$TIMEOUT == 0 )); then
    fail "Error: JIRA_TIMEOUT must be a positive integer, got '${TIMEOUT}'."
fi
TIMEOUT=$((10#$TIMEOUT))
for file in "$ISSUES_FILE" "$CLASSIFICATION_FILE"; do
    if [[ ! -f "$file" ]]; then
        fail "Error: '${file}' not found."
    fi
done
if ! jq -e '(.keywords | type == "array") and (.issues | type == "array")' "$ISSUES_FILE" > /dev/null 2>&1; then
    fail "Error: '${ISSUES_FILE}' isn't valid JSON or has no keyword list or issue list. Run searchJiraIssues.sh again."
fi
if ! jq empty "$CLASSIFICATION_FILE" > /dev/null 2>&1; then
    fail "Error: '${CLASSIFICATION_FILE}' isn't valid JSON."
fi
# The file must hold exactly one JSON value, an array: a second value after it would be read as another
# classification.
if ! jq -es 'length == 1 and (.[0] | type == "array")' "$CLASSIFICATION_FILE" > /dev/null 2>&1; then
    fail "Error: '${CLASSIFICATION_FILE}' must contain exactly one top-level JSON array."
fi

# The classification comes from a subagent that read untrusted issue text. Accept only the fixed values, and report
# a problem by the key or the position of the entry, never by its content.
PROBLEMS=$(jq -r --slurpfile jira "$ISSUES_FILE" '
    def is_key: type == "string" and test("^NIFI-[0-9]+$");
    ($jira[0].issues | map(.key)) as $keys
    | $jira[0].keywords as $keywords
    | (to_entries[] | (.key + 1) as $n | .value as $e
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
' "$CLASSIFICATION_FILE")
if [[ -n "$PROBLEMS" ]]; then
    fail "Error: the classification in ${CLASSIFICATION_FILE} has problems:"$'\n'"${PROBLEMS}"
fi

# A regression exists in the target version only if the issue that introduced it was fixed in the target version or
# earlier. Fetch the fix versions of all such issues in one search; null means that they couldn't be fetched. The
# validator above accepted only NIFI-<digits> keys, so the keys are safe to put into the query. With validateQuery
# false, a key that Jira doesn't know is left out of the result instead of failing the whole search.
KEYS=$(jq -c '[.[].introduced_by | select(. != "")] | unique' "$CLASSIFICATION_FILE")
INTRODUCED='{}'
if [[ "$KEYS" != "[]" ]]; then
    if body=$(jq -n --argjson keys "$KEYS" '{jql: "key in (\($keys | join(", ")))", fields: ["fixVersions"],
                maxResults: ($keys | length), validateQuery: false}' \
            | curl -sS --fail-with-body --max-time "$TIMEOUT" -X POST -H "Content-Type: application/json" \
                --data @- "$SEARCH_URL") \
        && INTRODUCED=$(jq -c --argjson keys "$KEYS" '
            (.issues | map({(.key): [.fields.fixVersions[]?.name]}) | add // {}) as $found
            | $keys | map({(.): $found[.]}) | add' <<< "$body" 2> /dev/null); then
        MISSING=$(jq -r 'to_entries | map(select(.value == null) | .key) | join(", ")' <<< "$INTRODUCED")
    else
        INTRODUCED=$(jq -c 'map({(.): null}) | add' <<< "$KEYS")
        MISSING=$(jq -r 'join(", ")' <<< "$KEYS")
    fi
    if [[ -n "$MISSING" ]]; then
        echo "Warning: couldn't fetch the fix versions of ${MISSING}; their regressions aren't checked." >&2
    fi
fi

# Issue summaries, statuses, impact lines, and version names are untrusted text. The report shows them as plain text:
# links and images keep only their text or alt text, URLs and e-mail addresses are removed, and angle brackets are
# escaped, so that HTML shows as text and Java types such as Map<String, Object> keep their words. The user opens the
# issue in Jira to see the original. Each value is kept on one line, and Markdown brackets, backslashes, and the
# table separator are escaped.
REPORT_DEFS='
    def cell: tostring | gsub("[\r\n]+"; " ")
        # Markdown images and links: inline, with one level of parentheses in the URL; reference style; and
        # reference definitions [ref]: url.
        | gsub("!?\\[(?<t>[^\\]]*)\\]\\((?:[^()]|\\([^()]*\\))*\\)"; "\(.t)")
        | gsub("!?\\[(?<t>[^\\]]*)\\]\\[[^\\]]*\\]"; "\(.t)") | gsub("\\[[^\\]]*\\]:\\s*\\S+"; "")
        # Jira links [text|url] and images !file.png! or !file.png|options!.
        | gsub("\\[(?<t>[^\\]|]*)\\|[^\\]]*\\]"; "\(.t)")
        | gsub("![^!\\s]+\\.(?:png|jpe?g|gif|svg|bmp|webp)(?:\\|[^!]*)?!"; ""; "i")
        # HTML images keep their alt text, and autolinks such as <https://...> or <name@example.com> are removed.
        | gsub("<img\\b[^>]*\\balt\\s*=\\s*\"(?<t>[^\"]*)\"[^>]*>"; "\(.t)"; "i")
        | gsub("<(?:https?|ftp|mailto):[^>]*>"; ""; "i") | gsub("<[^<>\\s@]+@[^<>\\s]+>"; "")
        # Bare URLs and e-mail addresses, which Markdown viewers turn into links.
        # No word boundary before them: GitHub also links a URL that follows "_", as in _https://..._.
        | gsub("(?:https?|ftp)://[^\\s\"<>\\[\\]()]*"; ""; "i") | gsub("www\\.[^\\s\"<>\\[\\]()]*"; ""; "i")
        | gsub("(?:mailto:)?[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"; ""; "i")
        # The brackets left by a link without text.
        | gsub("\\[\\s*\\]|\\(\\s*\\)"; "")
        # Any other HTML is shown as text.
        | gsub("&"; "&amp;") | gsub("<"; "&lt;") | gsub(">"; "&gt;")
        # The spaces left around a removed link.
        | gsub("\\s{2,}"; " ") | gsub("^\\s+|\\s+$"; "")
        # Markdown escapes: a link that the rules above missed, such as one with brackets in its text, can no longer
        # be parsed as a link. The backslash goes first, so that an escape in the text stays text.
        | gsub("\\\\"; "\\\\") | gsub("\\["; "\\[") | gsub("\\]"; "\\]")
        | gsub("\\|"; "\\|");
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
          then . + {verdict: "not relevant",
                    introduced: "\($by), fixed in \($v | map(cell) | join(", ")), after \($target)"}
              + (if .verdict == "not relevant" then {} else {classified: .verdict} end)
          else . + {introduced: "\($by), fixed in \($v | map(cell) | join(", "))"} end
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

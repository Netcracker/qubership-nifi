#!/bin/bash
# Runs nifi-upgrade-skill through opencode to upgrade Apache NiFi in the working tree.
# The agent only changes files: committing, pushing, and opening the pull request are left
# to the caller. The agent writes its final report, which becomes the pull request
# description, to REPORT_FILE.
#
# Environment:
#   NIFI_VERSION       target Apache NiFi version, for example 2.13.0
#   NIFI_DIGEST        digest of the apache/nifi:<NIFI_VERSION> image, sha256:<hex>
#   REPORT_FILE        report path, upgrade-temp-data/pr-body.md by default
#   SIGNATURE_FILE     path of the AI agent signature with the token usage and cost of the run, in the
#                      qubership-ai-packages format; upgrade-temp-data/agent-signature.md by default
#   QUBERSHIP_NIFI_VERSION  planned qubership-nifi release for the release notes; when unset, the version
#                      in pom.xml without -SNAPSHOT for a patch upgrade of Apache NiFi, and the next minor
#                      release for a minor one: 2.3.2-SNAPSHOT gives 2.3.2 and 2.4.0
#   OPENCODE_CONFIG    opencode configuration file, .github/opencode.json by default
#   OPENCODE_API_KEY   key for the model provider; the opencode provider reads it as is, and another provider
#                      reads it when the configuration sets its apiKey option to {env:OPENCODE_API_KEY}

set -euo pipefail

: "${NIFI_VERSION:?NIFI_VERSION is not set}"
: "${NIFI_DIGEST:?NIFI_DIGEST is not set}"
REPORT_FILE="${REPORT_FILE:-upgrade-temp-data/pr-body.md}"
# opencode looks for its configuration in the project root and .opencode/ only, so the path is set explicitly.
export OPENCODE_CONFIG="${OPENCODE_CONFIG:-$PWD/.github/opencode.json}"

SIGNATURE_FILE="${SIGNATURE_FILE:-upgrade-temp-data/agent-signature.md}"
# opencode stores the time of each message in milliseconds. Messages from this point on belong to this run.
RUN_START_MS=$(($(date +%s) * 1000))

mkdir -p "$(dirname "$REPORT_FILE")" "$(dirname "$SIGNATURE_FILE")"

# Prints the AI agent signature in the qubership-ai-packages format, with k for thousands in the token counts.
# The model, and its variant as the reasoning effort, are those of the last response of the main session.
# The totals cover every model response since RUN_START_MS in the opencode database, so subagents and retries
# are counted too. Fails when the run has no model response, because the model is then unknown.
write_signature() {
    local main totals
    main=$(opencode db --format json "
        select json_extract(m.data, '\$.modelID') as model, json_extract(m.data, '\$.variant') as effort
        from message m join session s on s.id = m.session_id
        where s.parent_id is null and json_extract(m.data, '\$.role') = 'assistant'
            and m.time_created >= $RUN_START_MS
        order by m.time_created desc limit 1") || return 1
    totals=$(opencode db --format json "
        select coalesce(sum(json_extract(data, '\$.tokens.input')), 0)
                + coalesce(sum(json_extract(data, '\$.tokens.cache.write')), 0) as input,
            coalesce(sum(json_extract(data, '\$.tokens.cache.read')), 0) as cached,
            coalesce(sum(json_extract(data, '\$.tokens.output')), 0)
                + coalesce(sum(json_extract(data, '\$.tokens.reasoning')), 0) as output,
            coalesce(sum(json_extract(data, '\$.cost')), 0) as cost
        from message
        where json_extract(data, '\$.role') = 'assistant' and time_created >= $RUN_START_MS") || return 1
    jq -n -r -e --argjson main "$main" --argjson totals "$totals" '
        def tokens: if . < 1000 then tostring else (. / 100 | round) / 10 | tostring + "k" end;
        # Rounds to two significant digits.
        def usd:
            if . == 0 then "0"
            else ((log10 | floor) - 1) as $n
                | if $n >= 0 then (. / pow(10; $n) | round) * pow(10; $n)
                    else (. * pow(10; -$n) | round) / pow(10; -$n) end
                | tostring
            end;
        ($main[0] // empty) as $m
        | $totals[0] as $t
        | "<sub>\ud83e\udd16 \($m.model)\(if $m.effort then " " + $m.effort else "" end)"
            + " in=\($t.input | tokens) cached=\($t.cached | tokens) out=\($t.output | tokens)"
            + " usd=\($t.cost | usd)</sub>"
        '
}

# Prints the value of a property of the root pom.xml.
evaluate_pom() {
    mvn --batch-mode --no-transfer-progress --non-recursive --quiet help:evaluate \
        -Dexpression="$1" -DforceStdout
}

# Prints the qubership-nifi release that ships the upgrade. pom.xml holds the next patch release as a
# -SNAPSHOT version, so a patch upgrade of Apache NiFi releases that version, and a minor upgrade releases
# the next minor version: with 2.3.2-SNAPSHOT and Apache NiFi 2.7.0, NiFi 2.7.1 gives 2.3.2 and 2.8.0 gives 2.4.0.
next_release() {
    local version current_nifi major minor
    version=$(evaluate_pom project.version)
    version="${version%-SNAPSHOT}"
    if [[ ! "$version" =~ ^([0-9]+)\.([0-9]+)\.[0-9]+$ ]]; then
        echo "::error::Cannot read the qubership-nifi version from pom.xml, found '$version'." >&2
        return 1
    fi
    major="${BASH_REMATCH[1]}"
    minor="${BASH_REMATCH[2]}"
    current_nifi=$(evaluate_pom nifi.version)
    if [[ ! "$current_nifi" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "::error::Cannot read nifi.version from pom.xml, found '$current_nifi'." >&2
        return 1
    fi
    if [ "${current_nifi%.*}" = "${NIFI_VERSION%.*}" ]; then
        echo "$version"
    else
        echo "$major.$((minor + 1)).0"
    fi
}

QUBERSHIP_NIFI_VERSION="${QUBERSHIP_NIFI_VERSION:-$(next_release)}"
export QUBERSHIP_NIFI_VERSION
echo "Planned qubership-nifi release: $QUBERSHIP_NIFI_VERSION"

PROMPT=$(
    cat <<EOF
Upgrade Apache NiFi to version ${NIFI_VERSION} by following the nifi-upgrade-skill skill.
Load it with the skill tool (@nifi-upgrade-skill) and run its steps in order.

Inputs for the "Get versions" step:
- Target NiFi version: ${NIFI_VERSION}
- SHA256 hash: ${NIFI_DIGEST}

This run is non-interactive in GitHub Actions, and nobody can answer questions. Follow these rules:
- Do not ask questions. Where the skill tells you to ask the user, make no change and record the item in the report.
- In the "Generate releases notes" step, use ${QUBERSHIP_NIFI_VERSION} as the planned qubership-nifi release version.
- Do not run git commit or git push, and do not create branches or pull requests. Leave the changes in the working tree.
- Docker is not available in this run. Do not run docker: the skill says what replaces each step that uses it.
- Do not change your own instructions and tools, the CI that runs you, the build tooling, the configuration of
    the security scanners and linters, or the repository configuration: .github/, .codex/, .opencode/, .qubership/,
    .mvn/, agent-packages/, opencode.json, opencode.jsonc, mvnw, mvnw.cmd, renovate.json, .gitmodules, apm.yml,
    apm.lock.yaml, .gitignore, .editorconfig, .trivyignore, .gitleaksignore, .prettierignore, a .agents/, .claude/,
    or .cursor/ directory anywhere, a .gitattributes file anywhere, and any AGENTS.md or CLAUDE.md, including
    variants such as AGENTS.override.md and CLAUDE.local.md. The workflow rejects a change to any of them. Where the
    upgrade seems to need one, record it under "Tasks for the reviewer" instead.

When you finish, write the final report in GitHub-flavored Markdown to ${REPORT_FILE}.
The report becomes the pull request description. Start it with a one-paragraph summary, then include:
- every Migration Guidance item classified as applied, not applicable, or user action required, as the "Migration
    guidance" step requires;
- a "Tasks for the reviewer" section with the deleted properties from the "Resolve display-name renames" step,
    by section, for the reviewer to check, and every item that needs user action.
Do not list the changed files or their line counts: the pull request already shows them.
Keep the report short: the summary is at most three sentences, and each item is one line. Give the result, not the
process: do not describe the steps you ran, quote command output, or restate the skill. Leave out a section that has
nothing to report, except "Tasks for the reviewer", which says "None" when it is empty.
EOF
)

RUN_STATUS=0
opencode run --auto "$PROMPT" || RUN_STATUS=$?

if [ ! -s "$REPORT_FILE" ]; then
    echo "::warning::The agent did not write the report to $REPORT_FILE."
fi

# The signature is written even when the agent fails, so that the cost of every run is known.
if ! write_signature >"$SIGNATURE_FILE"; then
    echo "::warning::Cannot read the token usage of the agent from opencode, so $SIGNATURE_FILE is not written."
    rm -f "$SIGNATURE_FILE"
fi

exit "$RUN_STATUS"

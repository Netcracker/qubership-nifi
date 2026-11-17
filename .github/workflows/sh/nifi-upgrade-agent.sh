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
#   QUBERSHIP_NIFI_VERSION  planned qubership-nifi release for the release notes; when unset, the version
#                      in pom.xml without -SNAPSHOT and with the minor version incremented, 2.8.7-SNAPSHOT -> 2.9.0
#   OPENCODE_CONFIG    opencode configuration file, .github/opencode.json by default
#   OPENAI_API_KEY     key for the openai provider, when the configuration selects an openai model

set -euo pipefail

: "${NIFI_VERSION:?NIFI_VERSION is not set}"
: "${NIFI_DIGEST:?NIFI_DIGEST is not set}"
REPORT_FILE="${REPORT_FILE:-upgrade-temp-data/pr-body.md}"
# opencode looks for its configuration in the project root and .opencode/ only, so the path is set explicitly.
export OPENCODE_CONFIG="${OPENCODE_CONFIG:-$PWD/.github/opencode.json}"

mkdir -p "$(dirname "$REPORT_FILE")"

# Prints the next minor release after the project version in pom.xml.
next_minor_release() {
    local version major minor
    version=$(mvn --batch-mode --no-transfer-progress --non-recursive --quiet help:evaluate \
        -Dexpression=project.version -DforceStdout)
    version="${version%-SNAPSHOT}"
    if [[ ! "$version" =~ ^([0-9]+)\.([0-9]+)\.[0-9]+$ ]]; then
        echo "::error::Cannot read the qubership-nifi version from pom.xml, found '$version'." >&2
        return 1
    fi
    major="${BASH_REMATCH[1]}"
    minor="${BASH_REMATCH[2]}"
    echo "$major.$((minor + 1)).0"
}

QUBERSHIP_NIFI_VERSION="${QUBERSHIP_NIFI_VERSION:-$(next_minor_release)}"
export QUBERSHIP_NIFI_VERSION
echo "Planned qubership-nifi release: $QUBERSHIP_NIFI_VERSION"

PROMPT=$(
    cat <<EOF
Upgrade Apache NiFi to version ${NIFI_VERSION} by following the nifi-upgrade-skill skill.
Load it with the skill tool (@nifi-upgrade-skill) and run its steps in order.

Inputs for step 1:
- Target NiFi version: ${NIFI_VERSION}
- SHA256 hash: ${NIFI_DIGEST}

This run is non-interactive in GitHub Actions, and nobody can answer questions. Follow these rules:
- Do not ask questions. Where the skill tells you to ask the user, make no change and record the item in the report.
- In step 14, use ${QUBERSHIP_NIFI_VERSION} as the planned qubership-nifi release version.
- Do not run git commit or git push, and do not create branches or pull requests. Leave the changes in the working tree.
- Do not change your own instructions and tools, or the CI that runs you: .github/, .agents/, .claude/, .codex/,
    .cursor/, .opencode/, agent-packages/, apm.yml, apm.lock.yaml, and any AGENTS.md or CLAUDE.md.
    The workflow rejects a change to any of them. Where the upgrade seems to need one, record it under
    "Tasks for the reviewer" instead.

When you finish, write the final report in GitHub-flavored Markdown to ${REPORT_FILE}.
The report becomes the pull request description. Start it with a one-paragraph summary, then include:
- every Migration Guidance item classified as applied, not applicable, or user action required, as step 9 requires;
- a "Tasks for the reviewer" section with the deleted properties from step 12, by section, for the reviewer to check,
    and every item that needs user action.
Do not list the changed files or their line counts: the pull request already shows them.
Keep the report short: the summary is at most three sentences, and each item is one line. Give the result, not the
process: do not describe the steps you ran, quote command output, or restate the skill. Leave out a section that has
nothing to report, except "Tasks for the reviewer", which says "None" when it is empty.
EOF
)

opencode run --auto "$PROMPT"

if [ ! -s "$REPORT_FILE" ]; then
    echo "::warning::The agent did not write the report to $REPORT_FILE."
fi

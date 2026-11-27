#!/bin/bash
# Stages every change the agent left in the working tree and saves it as a patch, with the report and the AI
# agent signature of the agent, for the job that opens the pull request. Fails when the agent made no change, or
# when the change touches a binary file, a protected path, a symlink, or a submodule.
#
# Environment:
#   NIFI_VERSION    target Apache NiFi version, for the error messages
#   OUT_DIR         directory for changes.patch, pr-body.md, and agent-signature.md; $RUNNER_TEMP/nifi-upgrade by
#                   default
#   REPORT_FILE     report of the agent, upgrade-temp-data/pr-body.md by default; copied when it is not empty
#   SIGNATURE_FILE  AI agent signature, upgrade-temp-data/agent-signature.md by default; copied when it is not empty

set -euo pipefail

# shellcheck source=nifi-upgrade-checks.sh
. "$(dirname "${BASH_SOURCE[0]}")/nifi-upgrade-checks.sh"

main() {
    local out_dir="${OUT_DIR:-${RUNNER_TEMP:?RUNNER_TEMP is not set}/nifi-upgrade}"
    local report_file="${REPORT_FILE:-upgrade-temp-data/pr-body.md}"
    local signature_file="${SIGNATURE_FILE:-upgrade-temp-data/agent-signature.md}"

    mkdir -p "$out_dir"
    git add --all
    if git diff --cached --quiet; then
        echo "::error::The agent made no changes for Apache NiFi ${NIFI_VERSION:-}."
        return 1
    fi
    git diff --cached --numstat | check_binary_files "The change"
    git diff --cached --name-only --no-renames -z | check_protected_paths "The change"
    git diff --cached --summary --no-renames | check_special_entries "The change"

    git diff --cached --stat
    git diff --cached --no-renames >"$out_dir/changes.patch"
    if [ -s "$report_file" ]; then
        cp "$report_file" "$out_dir/pr-body.md"
    fi
    if [ -s "$signature_file" ]; then
        cp "$signature_file" "$out_dir/agent-signature.md"
    fi
}

main "$@"

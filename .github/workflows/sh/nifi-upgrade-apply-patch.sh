#!/bin/bash
# Applies the patch that nifi-upgrade-save-patch.sh saved, and prepares the pull request description from the
# report and the AI agent signature next to it. This job can push, so it checks the patch again rather than
# trusting the job that saved it, and fails when the patch touches a binary file, a symlink, a submodule, or a
# protected path. Writes draft, true when the agent wrote no report, and model, the model of .github/opencode.json,
# to $GITHUB_OUTPUT; outside GitHub Actions, where GITHUB_OUTPUT is unset, it prints them.
#
# Environment:
#   PATCH_DIR          directory with changes.patch and, when the agent wrote them, pr-body.md and
#                      agent-signature.md; $RUNNER_TEMP/nifi-upgrade by default. pr-body.md is completed in place.
#   GITHUB_SERVER_URL, GITHUB_REPOSITORY, GITHUB_RUN_ID
#                      the workflow run that the pull request description links to; set by GitHub Actions
#   GITHUB_OUTPUT      file for the step outputs; set by GitHub Actions

set -euo pipefail

# shellcheck source=nifi-upgrade-checks.sh
. "$(dirname "${BASH_SOURCE[0]}")/nifi-upgrade-checks.sh"

write_output() {
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "$1" >>"$GITHUB_OUTPUT"
    else
        echo "$1"
    fi
}

# Everything runs from this function, which bash reads in full before it runs a line of it, so a patch that
# changes this script cannot change what the rest of the run does.
main() {
    local patch_dir="${PATCH_DIR:-${RUNNER_TEMP:?RUNNER_TEMP is not set}/nifi-upgrade}"
    local patch_file="$patch_dir/changes.patch"
    local body_file="$patch_dir/pr-body.md"
    local signature_file="$patch_dir/agent-signature.md"
    local draft run_url

    git apply --numstat "$patch_file" | check_binary_files "The patch"
    git apply --summary "$patch_file" | check_special_entries "The patch"
    git apply "$patch_file"
    # The check runs on the files the patch actually changed, so no form of the patch can hide a path.
    git add --all
    git diff --cached --name-only --no-renames -z | check_protected_paths "The patch"

    # Without the report the agent may have stopped partway, so the pull request opens as a draft.
    draft=false
    if [ ! -s "$body_file" ]; then
        echo "The agent did not write a report. Review the changes against nifi-upgrade-skill manually." >"$body_file"
        draft=true
    fi
    run_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
    printf '\n---\n\nThe release notes are in the release-notes artifact, and the other files of this run in %s\n' \
        "the upgrade-temp-data artifact, of [workflow run $GITHUB_RUN_ID]($run_url)." >>"$body_file"
    if [ -s "$signature_file" ]; then
        printf '\n' >>"$body_file"
        cat "$signature_file" >>"$body_file"
    fi
    write_output "draft=$draft"
    write_output "model=$(jq -r '.model' .github/opencode.json)"
}

main "$@"

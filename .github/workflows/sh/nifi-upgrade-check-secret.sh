#!/bin/bash
# Fails when the key of the model provider, OPENCODE_API_KEY, is in a file under upgrade-temp-data/ or in a file
# that the agent changed or added, so that the key leaves the job in neither an artifact nor the patch. GitHub
# masks a secret in the log, but not in an artifact or a commit. Prints the names of the files only, never the
# key. Does nothing when OPENCODE_API_KEY is empty.
#
# Environment:
#   OPENCODE_API_KEY  key to look for
#   TEMP_DATA_DIR     directory that the workflow uploads as artifacts, upgrade-temp-data by default

set -euo pipefail

main() {
    local key="${OPENCODE_API_KEY:-}" temp_data_dir="${TEMP_DATA_DIR:-upgrade-temp-data}" files status=0

    if [ -z "$key" ]; then
        echo "OPENCODE_API_KEY is empty, so there is no key to look for."
        return 0
    fi

    # grep and git grep exit with 1 when nothing matches, which is the expected result, and with 2 or more on an
    # error, which fails the check, because the files were then not searched at all.
    if [ -d "$temp_data_dir" ]; then
        files=$(grep -r -l -F -e "$key" -- "$temp_data_dir") || status=$?
        if [ "$status" -gt 1 ]; then
            echo "::error::Cannot search $temp_data_dir for OPENCODE_API_KEY: grep exited with status $status."
            return 1
        fi
        if [ -n "$files" ]; then
            echo "::error::OPENCODE_API_KEY is in files that the workflow uploads as artifacts:"
            echo "$files"
            return 1
        fi
    fi

    # The changes are staged, so that git grep --cached searches the added files as well as the changed ones.
    # A file the agent did not change cannot hold the key, so searching the whole index finds only changed files.
    git add --all
    status=0
    files=$(git grep --cached -l -F -e "$key") || status=$?
    if [ "$status" -gt 1 ]; then
        echo "::error::Cannot search the changes for OPENCODE_API_KEY: git grep exited with status $status."
        return 1
    fi
    if [ -n "$files" ]; then
        echo "::error::OPENCODE_API_KEY is in files that the agent changed:"
        echo "$files"
        return 1
    fi
    echo "OPENCODE_API_KEY is in neither $temp_data_dir nor the changes."
}

main "$@"

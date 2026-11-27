#!/bin/bash
# Checks that reject a change the Apache NiFi upgrade must not make, shared by nifi-upgrade-save-patch.sh and
# nifi-upgrade-apply-patch.sh. Each check reads git output on stdin. When it finds a rejected path, it prints an
# error that starts with its subject argument, for example "The patch", lists the paths, and fails. The callers
# pipe git into a check under set -o pipefail, so a failed git command fails the caller too.

# The agent's own instructions and tools, the CI that runs it, the build tooling, the configuration of the security
# scanners and linters, and the repository configuration rather than the product. A change to them would steer the
# next run of the agent or what CI checks, so a person makes such a change; the upgrade fails when its change touches
# one of these paths. .agents/, .claude/, .cursor/, and .gitattributes are matched in any directory, and AGENTS.md
# and CLAUDE.md in any directory and with any suffix, such as AGENTS.override.md or CLAUDE.local.md. The other
# files are matched in the repository root only.
PROTECTED_PATHS='^(\.github|\.codex|\.opencode|\.qubership|\.mvn|agent-packages)/'
PROTECTED_PATHS+='|(^|/)\.(agents|claude|cursor)/'
PROTECTED_PATHS+='|^(opencode\.jsonc?|mvnw(\.cmd)?|renovate\.json|\.gitmodules|apm(\.lock)?\.ya?ml)$'
PROTECTED_PATHS+='|^\.(gitignore|editorconfig|trivyignore|gitleaksignore|prettierignore)$'
PROTECTED_PATHS+='|(^|/)\.gitattributes$'
PROTECTED_PATHS+='|(^|/)(AGENTS|CLAUDE)(\.[^/]*)?\.md$'

# Reads the output of git diff --numstat or git apply --numstat, which print "-" instead of line counts for a
# binary file. The upgrade changes only text files, so a binary file is rejected.
check_binary_files() {
    local subject="$1" files
    files=$(awk -F '\t' '$1 == "-" || $2 == "-" { print $3 }')
    if [ -n "$files" ]; then
        echo "::error::$subject changes binary files, which the upgrade does not allow:"
        echo "$files"
        return 1
    fi
}

# Reads NUL-terminated paths, as git diff --name-only -z prints them. Without -z, git quotes a path with non-ASCII
# characters, such as ".claude/skills/\303\251/SKILL.md", and the quoted path does not match PROTECTED_PATHS.
# Pass --no-renames to git as well, so that a file moved out of a protected path is caught by its old path.
check_protected_paths() {
    local subject="$1" files status=0
    # grep exits with 1 when no path matches, which is the expected result, and with 2 on an error, such as an
    # invalid PROTECTED_PATHS. An error fails the check, because the paths were then not checked at all.
    files=$(grep -zE "$PROTECTED_PATHS" | tr '\0' '\n') || status=$?
    if [ "$status" -gt 1 ]; then
        echo "::error::Cannot check the changed paths against PROTECTED_PATHS: grep exited with status $status."
        return 1
    fi
    if [ -n "$files" ]; then
        echo "::error::$subject changes protected files, which only a person may change:"
        echo "$files"
        return 1
    fi
}

# Reads the output of git diff --summary or git apply --summary, which print the modes of symlinks, 120000, and
# submodules, 160000. A symlink can point outside the repository and a submodule pulls in another repository, so
# the change may not add, remove, or switch to either.
check_special_entries() {
    local subject="$1" entries
    entries=$(grep -E ' (120000|160000)( |$)' || true)
    if [ -n "$entries" ]; then
        echo "::error::$subject changes symlinks or submodules, which the upgrade does not allow:"
        echo "$entries"
        return 1
    fi
}

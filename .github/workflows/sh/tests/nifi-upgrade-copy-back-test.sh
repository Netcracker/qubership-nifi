#!/bin/bash
# Tests for nifi-upgrade-copy-back.sh. sudo is replaced by a stub that runs the command as it is, and chown by a stub
# that records its arguments in the file calls, because the files already belong to the user that runs the tests.
# The tests need rsync and symlinks, which Git Bash on Windows does not have, so outside CI they are skipped where
# either is missing.

# shellcheck source=test-lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/test-lib.sh"

has_rsync_and_symlinks() {
    local dir found=0
    command -v rsync >/dev/null || return 1
    dir=$(mktemp -d)
    touch "$dir/file"
    ln -s "$dir/file" "$dir/link" 2>/dev/null && [ -L "$dir/link" ] || found=1
    rm -rf "$dir"
    return "$found"
}

if [ -z "${CI:-}" ] && ! has_rsync_and_symlinks; then
    echo "SKIPPED $(basename "$0"): rsync or symlinks are not available; run it on Linux."
    exit 0
fi

# Sets up a workspace and the copy of the agent, each with a .git directory, and a file that the agent changed, one it
# added, and one it deleted.
setup_copies() {
    mkdir -p workspace/.git workspace/docs agent-home/repo/.git agent-home/repo/docs
    echo "[core]" >workspace/.git/config
    echo "one" >workspace/docs/changed.txt
    echo "old" >workspace/docs/deleted.txt
    printf '[core]\n\thooksPath = /tmp/hooks\n' >agent-home/repo/.git/config
    echo "two" >agent-home/repo/docs/changed.txt
    echo "new" >agent-home/repo/docs/added.txt
    # rsync -a skips a file whose size and modification time match, and changed.txt keeps its size. On the runner the
    # workspace files are older than the edits of the agent, so they are dated back here too.
    touch -t 200001010000 workspace/docs/changed.txt workspace/docs/deleted.txt
    stub sudo <<'EOF'
#!/bin/bash
exec "$@"
EOF
    stub chown <<EOF
#!/bin/bash
echo "chown \$*" >>"$TEST_DIR/calls"
EOF
}

run_copy_back() {
    run env AGENT_HOME="$TEST_DIR/agent-home" GITHUB_WORKSPACE="$TEST_DIR/workspace" \
        bash "$SH_DIR/nifi-upgrade-copy-back.sh"
}

test_the_changes_of_the_agent_reach_the_workspace() {
    setup_copies
    run_copy_back
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq "two" "$(cat workspace/docs/changed.txt)" "changed file"
    assert_eq "new" "$(cat workspace/docs/added.txt)" "added file"
    assert_file_missing workspace/docs/deleted.txt
}

test_the_workspace_is_given_back_to_the_runner_user() {
    setup_copies
    run_copy_back
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq "chown -R $(id -u):$(id -g) $TEST_DIR/workspace" "$(cat calls)" "calls"
}

test_the_git_directory_of_the_workspace_is_kept() {
    setup_copies
    run_copy_back
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq "[core]" "$(cat workspace/.git/config)" "workspace .git/config"
}

test_a_symlink_fails_and_nothing_is_copied() {
    setup_copies
    ln -s /etc/passwd agent-home/repo/docs/passwd
    run_copy_back
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "::error::The agent created symlinks, which the upgrade does not allow:" "output"
    assert_contains "$OUTPUT" "agent-home/repo/docs/passwd" "output"
    assert_eq "one" "$(cat workspace/docs/changed.txt)" "changed file"
}

test_a_symlink_inside_git_is_allowed() {
    setup_copies
    ln -s /etc/passwd agent-home/repo/.git/passwd
    run_copy_back
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq "two" "$(cat workspace/docs/changed.txt)" "changed file"
}

run_tests

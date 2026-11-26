#!/bin/bash
# Tests for nifi-upgrade-save-patch.sh and nifi-upgrade-apply-patch.sh, and through them for the checks of
# nifi-upgrade-checks.sh. Each test works in a new git repository with one commit, except the tests that call a
# check of nifi-upgrade-checks.sh directly.

# shellcheck source=test-lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/test-lib.sh"

# e with an acute accent, which git quotes in a path unless it prints the path with -z.
E_ACUTE=$(printf '\xc3\xa9')

# Creates the repository in repo/ and enters it. TEST_ROOT keeps the test directory, outside the repository.
init_repo() {
    TEST_ROOT="$PWD"
    git init -q repo
    cd repo || return
    git config user.email test@example.com
    git config user.name test
    git config core.autocrlf false
    mkdir -p docs .github
    echo one >docs/a.txt
    echo '{"model": "opencode/test-model"}' >.github/opencode.json
    echo 'upgrade-temp-data/' >.gitignore
    git add --all
    git commit -q -m init
}

# Writes a file, creating its directory.
write_file() {
    mkdir -p "$(dirname "$1")"
    echo "$2" >"$1"
}

# Adds a symlink to the index. Where git keeps symlinks as plain files, as on Windows, the link is a file that
# holds the target, and the index entry still gets the symlink mode, 120000.
add_symlink() {
    local target="$1" link="$2"
    if [ "$(git config --get core.symlinks)" = "false" ]; then
        printf '%s' "$target" >"$link"
        git update-index --add --cacheinfo "120000,$(git hash-object -w "$link"),$link"
    else
        ln -s "$target" "$link"
    fi
}

# Adds a submodule entry, mode 160000, that points at the current commit.
add_submodule() {
    mkdir -p "$1"
    git update-index --add --cacheinfo "160000,$(git rev-parse HEAD),$1"
}

# Turns the changes in the working tree into $TEST_ROOT/patch/changes.patch, as the update job would save them,
# and resets the repository to its commit. Extra arguments go to git diff, such as --binary.
make_patch() {
    mkdir -p "$TEST_ROOT/patch"
    git add --all
    git diff --cached --no-renames "$@" >"$TEST_ROOT/patch/changes.patch"
    git reset -q --hard
    git clean -q -f -d
}

# Clears the changes in the working tree and the index.
reset_repo() {
    git reset -q --hard
    git clean -q -f -d
}

run_save() {
    run env OUT_DIR="$TEST_ROOT/out" NIFI_VERSION=2.7.3 bash "$SH_DIR/nifi-upgrade-save-patch.sh"
}

run_apply() {
    : >"$TEST_ROOT/github-output"
    run env PATCH_DIR="$TEST_ROOT/patch" GITHUB_OUTPUT="$TEST_ROOT/github-output" \
        GITHUB_SERVER_URL=https://github.com GITHUB_REPOSITORY=owner/repo GITHUB_RUN_ID=42 \
        bash "$SH_DIR/nifi-upgrade-apply-patch.sh"
}

RUN_LINK_LINE="The release notes are in the release-notes artifact, and the other files of this run in the \
upgrade-temp-data artifact, of [workflow run 42](https://github.com/owner/repo/actions/runs/42)."

# Paths the upgrade may not change, one per protected pattern, and one with a non-ASCII character.
protected_paths() {
    cat <<EOF
.github/workflows/build.yml
.codex/config.toml
.opencode/agents/a.md
.qubership/config.yml
.mvn/maven.config
agent-packages/kit/apm.yml
.agents/skills/a/SKILL.md
module/.agents/skills/a/SKILL.md
.claude/skills/$E_ACUTE/SKILL.md
docs/AGENTS.md
CLAUDE.local.md
opencode.json
opencode.jsonc
mvnw
mvnw.cmd
renovate.json
.gitattributes
.gitmodules
module/.gitattributes
.cursor/rules/a.mdc
module/.cursor/rules/a.mdc
.gitignore
.editorconfig
.trivyignore
.gitleaksignore
.prettierignore
apm.yml
apm.lock.yaml
EOF
}

test_save_writes_the_patch_report_and_signature() {
    init_repo
    echo two >docs/a.txt
    write_file docs/b.txt new
    write_file upgrade-temp-data/pr-body.md "The report"
    write_file upgrade-temp-data/agent-signature.md "<sub>sig</sub>"
    run_save
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$(cat "$TEST_ROOT/out/changes.patch")" "+two" "patch"
    assert_contains "$(cat "$TEST_ROOT/out/changes.patch")" "+++ b/docs/b.txt" "patch"
    assert_not_contains "$(cat "$TEST_ROOT/out/changes.patch")" "upgrade-temp-data" "patch"
    assert_eq "The report" "$(cat "$TEST_ROOT/out/pr-body.md")" "saved report"
    assert_eq "<sub>sig</sub>" "$(cat "$TEST_ROOT/out/agent-signature.md")" "saved signature"
}

test_save_without_a_report_or_signature_saves_the_patch_alone() {
    init_repo
    echo two >docs/a.txt
    run_save
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_file_missing "$TEST_ROOT/out/pr-body.md"
    assert_file_missing "$TEST_ROOT/out/agent-signature.md"
}

test_save_without_changes_fails() {
    init_repo
    run_save
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "The agent made no changes for Apache NiFi 2.7.3" "output"
}

test_save_rejects_a_binary_file() {
    init_repo
    printf 'a\000b' >docs/data.bin
    run_save
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "The change changes binary files" "output"
    assert_contains "$OUTPUT" "docs/data.bin" "output"
    assert_file_missing "$TEST_ROOT/out/changes.patch"
}

test_save_rejects_each_protected_path() {
    local path
    init_repo
    while read -r path; do
        write_file "$path" changed
        run_save
        assert_eq 1 "$STATUS" "exit status for $path"
        assert_contains "$OUTPUT" "The change changes protected files" "output for $path"
        assert_contains "$OUTPUT" "$path" "output for $path"
        reset_repo
    done < <(protected_paths)
}

test_save_rejects_a_file_moved_out_of_a_protected_path() {
    init_repo
    git mv .github/opencode.json docs/opencode.json
    run_save
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" ".github/opencode.json" "output"
}

test_save_accepts_paths_that_only_resemble_protected_ones() {
    init_repo
    write_file docs/agents.md changed
    write_file docs/opencode.json changed
    write_file src/github/a.txt changed
    write_file nifi-config/claude/a.txt changed
    write_file AGENTS.txt changed
    write_file module/.gitignore changed
    write_file module/.editorconfig changed
    run_save
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
}

test_save_rejects_a_symlink() {
    init_repo
    add_symlink ../outside docs/link
    run_save
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "The change changes symlinks or submodules" "output"
    assert_contains "$OUTPUT" "docs/link" "output"
}

test_save_rejects_a_submodule() {
    init_repo
    add_submodule modules/other
    run_save
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "The change changes symlinks or submodules" "output"
    assert_contains "$OUTPUT" "modules/other" "output"
}

test_apply_applies_the_patch_and_completes_the_description() {
    init_repo
    echo two >docs/a.txt
    make_patch
    echo "The report" >"$TEST_ROOT/patch/pr-body.md"
    echo "<sub>sig</sub>" >"$TEST_ROOT/patch/agent-signature.md"
    run_apply
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq two "$(cat docs/a.txt)" "docs/a.txt"
    assert_eq "$(printf 'The report\n\n---\n\n%s\n\n<sub>sig</sub>' "$RUN_LINK_LINE")" \
        "$(cat "$TEST_ROOT/patch/pr-body.md")" "description"
    assert_eq false "$(output_value "$TEST_ROOT/github-output" draft)" "draft"
    assert_eq opencode/test-model "$(output_value "$TEST_ROOT/github-output" model)" "model"
}

test_apply_without_a_report_opens_a_draft() {
    init_repo
    echo two >docs/a.txt
    make_patch
    run_apply
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq "$(printf 'The agent did not write a report. Review the changes against nifi-upgrade-skill manually.%s' \
        "$(printf '\n\n---\n\n%s' "$RUN_LINK_LINE")")" "$(cat "$TEST_ROOT/patch/pr-body.md")" "description"
    assert_eq true "$(output_value "$TEST_ROOT/github-output" draft)" "draft"
}

test_apply_rejects_a_binary_file_before_applying() {
    init_repo
    printf 'a\000b' >docs/data.bin
    make_patch --binary
    run_apply
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "The patch changes binary files" "output"
    assert_file_missing docs/data.bin
}

test_apply_rejects_a_symlink_before_applying() {
    init_repo
    add_symlink ../outside docs/link
    make_patch
    run_apply
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "The patch changes symlinks or submodules" "output"
    assert_file_missing docs/link
}

test_apply_rejects_a_submodule_before_applying() {
    init_repo
    add_submodule modules/other
    make_patch
    run_apply
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "The patch changes symlinks or submodules" "output"
}

test_apply_rejects_each_protected_path() {
    local path
    init_repo
    while read -r path; do
        write_file "$path" changed
        make_patch
        run_apply
        assert_eq 1 "$STATUS" "exit status for $path"
        assert_contains "$OUTPUT" "The patch changes protected files" "output for $path"
        assert_contains "$OUTPUT" "$path" "output for $path"
        reset_repo
    done < <(protected_paths)
}

# Puts a git stub first on PATH that fails git diff --name-only, the command that lists the paths for the
# protected path check, and passes every other command to the real git.
stub_failing_name_only_diff() {
    local real_git
    real_git=$(command -v git)
    stub git <<EOF
#!/bin/bash
if [ "\$1" = diff ] && [[ " \$* " == *" --name-only "* ]]; then
    echo "fatal: stub failure" >&2
    exit 128
fi
exec "$real_git" "\$@"
EOF
}

test_save_fails_when_git_cannot_list_the_changed_paths() {
    init_repo
    echo two >docs/a.txt
    stub_failing_name_only_diff
    run_save
    assert_eq 128 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "fatal: stub failure" "output"
    assert_file_missing "$TEST_ROOT/out/changes.patch"
}

test_apply_fails_when_git_cannot_list_the_changed_paths() {
    init_repo
    echo two >docs/a.txt
    make_patch
    stub_failing_name_only_diff
    run_apply
    assert_eq 128 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq "" "$(output_value "$TEST_ROOT/github-output" draft)" "draft"
}

test_the_protected_path_check_fails_on_an_invalid_pattern() {
    . "$SH_DIR/nifi-upgrade-checks.sh"
    # shellcheck disable=SC2034 # read by check_protected_paths from nifi-upgrade-checks.sh
    PROTECTED_PATHS='('
    run check_protected_paths "The change" < <(printf 'docs/a.txt\0')
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "Cannot check the changed paths against PROTECTED_PATHS: grep exited with status 2" \
        "output"
}

test_the_protected_path_check_passes_when_no_path_is_protected() {
    . "$SH_DIR/nifi-upgrade-checks.sh"
    run check_protected_paths "The change" < <(printf 'docs/a.txt\0nifi-config/b.txt\0')
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq "" "$OUTPUT" "output"
}

run_tests

#!/bin/bash
# Tests for nifi-upgrade-check-secret.sh. Each test works in a new git repository with one commit.

# shellcheck source=test-lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/test-lib.sh"

KEY="test-key-test-key-test-key"

init_repo() {
    git init -q repo
    cd repo || return
    git config user.email test@example.com
    git config user.name test
    git config core.autocrlf false
    mkdir -p docs
    echo one >docs/a.txt
    echo 'upgrade-temp-data/' >.gitignore
    git add --all
    git commit -q -m init
}

write_file() {
    mkdir -p "$(dirname "$1")"
    echo "$2" >"$1"
}

run_check() {
    run env OPENCODE_API_KEY="$KEY" bash "$SH_DIR/nifi-upgrade-check-secret.sh"
}

# Asserts that the check failed, named the file, and did not print the key.
assert_key_found_in() {
    local path="$1" message="$2"
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "$message" "output"
    assert_contains "$OUTPUT" "$path" "output"
    assert_not_contains "$OUTPUT" "$KEY" "output"
}

test_changes_and_temp_data_without_the_key_pass() {
    init_repo
    echo two >docs/a.txt
    write_file docs/b.txt new
    write_file upgrade-temp-data/pr-body.md "The report"
    run_check
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
}

test_an_empty_key_is_not_looked_for() {
    init_repo
    write_file upgrade-temp-data/pr-body.md "The report"
    run env OPENCODE_API_KEY= bash "$SH_DIR/nifi-upgrade-check-secret.sh"
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "OPENCODE_API_KEY is empty" "output"
}

test_a_run_without_temp_data_passes() {
    init_repo
    echo two >docs/a.txt
    run_check
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
}

test_the_key_in_a_temp_data_file_fails() {
    init_repo
    write_file upgrade-temp-data/pr-body.md "The key is $KEY."
    run_check
    assert_key_found_in upgrade-temp-data/pr-body.md "OPENCODE_API_KEY is in files that the workflow uploads"
}

test_the_key_in_a_nested_temp_data_file_fails() {
    init_repo
    write_file upgrade-temp-data/releases-notes/notes.md "key=$KEY"
    run_check
    assert_key_found_in upgrade-temp-data/releases-notes/notes.md \
        "OPENCODE_API_KEY is in files that the workflow uploads"
}

test_the_key_in_a_changed_file_fails() {
    init_repo
    echo "one $KEY" >docs/a.txt
    run_check
    assert_key_found_in docs/a.txt "OPENCODE_API_KEY is in files that the agent changed"
}

test_the_key_in_an_added_file_fails() {
    init_repo
    write_file nifi-config/new.properties "api.key=$KEY"
    run_check
    assert_key_found_in nifi-config/new.properties "OPENCODE_API_KEY is in files that the agent changed"
}

test_a_failed_search_of_the_changes_fails() {
    local real_git
    init_repo
    real_git=$(command -v git)
    stub git <<EOF
#!/bin/bash
if [ "\$1" = grep ]; then
    echo "fatal: stub failure" >&2
    exit 128
fi
exec "$real_git" "\$@"
EOF
    run_check
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "Cannot search the changes for OPENCODE_API_KEY: git grep exited with status 128" \
        "output"
}

test_a_failed_search_of_the_temp_data_fails() {
    init_repo
    write_file upgrade-temp-data/report.md "no key here"
    stub grep <<'EOF'
#!/bin/bash
echo "grep: stub failure" >&2
exit 2
EOF
    run_check
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "Cannot search upgrade-temp-data for OPENCODE_API_KEY: grep exited with status 2" \
        "output"
}

run_tests

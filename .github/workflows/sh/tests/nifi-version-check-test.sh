#!/bin/bash
# Tests for nifi-version-check.sh. Docker Hub is replaced by tag files read through a file:// TAGS_URL, and the
# GitHub CLI by a stub that prints the existing branch and pull requests that a test sets up.

# shellcheck source=test-lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/test-lib.sh"

# Prints a Docker Hub tag object pushed the given number of days ago.
tag() {
    local name="$1" days="$2" digest="${3-sha256:$1}"
    jq -n -c --arg name "$name" --arg digest "$digest" \
        --arg pushed "$(date -u -d "-$days days" +%Y-%m-%dT%H:%M:%S.000000Z)" \
        '{name: $name, digest: (if $digest == "" then null else $digest end), tag_last_pushed: $pushed}'
}

# Prints a file:// URL for a path; curl on Windows needs the drive letter form of the path.
file_url() {
    local dir
    dir=$(cd "$(dirname "$1")" && (pwd -W 2>/dev/null || pwd))
    echo "file://$dir/$(basename "$1")"
}

# Writes the tags given as arguments as the only page of the tag list, the file tags.
tags_page() {
    jq -s -c '{results: ., next: null}' <<<"$(printf '%s
' "$@")" >tags
    TAGS_URL="$(file_url tags)?page_size=100"
    export TAGS_URL
}

# Writes a single tag where select_target_version reads it: TAGS_URL without the query, followed by /<version>.
# A test of a target version reads no tag list, so tags is a directory here.
single_tag() {
    mkdir -p tags
    tag "$@" >"tags/$1"
    TAGS_URL="$(file_url tags)?page_size=100"
    export TAGS_URL
}

# The gh stub prints gh-branch for gh api and gh-prs for gh pr list, and logs its arguments to gh.log.
stub_gh() {
    stub gh <<'EOF'
#!/bin/bash
echo "$*" >>gh.log
case "$1" in
    api) cat gh-branch 2>/dev/null || true ;;
    pr) cat gh-prs 2>/dev/null || true ;;
esac
EOF
}

run_check() {
    stub_gh
    # A test that writes no tags still reads them from the test directory rather than from Docker Hub.
    if [ -z "${TAGS_URL:-}" ]; then
        TAGS_URL="$(file_url .)/tags?page_size=100"
        export TAGS_URL
    fi
    : >github-output
    # EVENT_NAME stands for the event that started the workflow run, a manual run unless a test sets it, so that
    # the GITHUB_EVENT_NAME of the run that runs the tests does not leak in.
    run env CURRENT_VERSION=2.7.2 GITHUB_OUTPUT=github-output GITHUB_EVENT_NAME="${EVENT_NAME-workflow_dispatch}" \
        bash "$SH_DIR/nifi-version-check.sh"
}

assert_found() {
    local version="$1"
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq "$version" "$(output_value github-output version)" "version"
    assert_eq "sha256:$version" "$(output_value github-output digest)" "digest"
    assert_eq "feat/upgrade-nifi-to-$version" "$(output_value github-output branch)" "branch"
}

assert_nothing_found() {
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq "" "$(output_value github-output version)" "version"
    assert_eq "" "$(output_value github-output branch)" "branch"
}

test_a_patch_version_qualifies_after_7_days() {
    tags_page "$(tag 2.7.3 8)" "$(tag 2.7.4 6)"
    run_check
    assert_found 2.7.3
}

test_a_minor_version_does_not_qualify_before_14_days() {
    tags_page "$(tag 2.7.3 8)" "$(tag 2.8.0 13)"
    run_check
    assert_found 2.7.3
}

test_a_minor_version_qualifies_after_14_days() {
    tags_page "$(tag 2.7.3 30)" "$(tag 2.8.0 15)"
    run_check
    assert_found 2.8.0
}

test_tags_of_another_major_version_without_a_digest_or_not_x_y_z_are_skipped() {
    tags_page "$(tag 3.0.0 30)" "$(tag 2.7.5 30 "")" "$(tag 2.9.0-M1 30)" "$(tag latest 30)" "$(tag 2.7.1 30)"
    run_check
    assert_nothing_found
}

test_the_tag_list_is_read_across_pages() {
    jq -n -c --argjson tag "$(tag 2.7.4 8)" '{results: [$tag], next: null}' >tags-page2
    jq -n -c --arg next "$(file_url tags-page2)" --argjson tag "$(tag 2.7.3 8)" \
        '{results: [$tag], next: $next}' >tags
    TAGS_URL="$(file_url tags)?page_size=100"
    export TAGS_URL
    run_check
    assert_found 2.7.4
}

test_a_scheduled_run_skips_a_version_with_an_existing_branch() {
    tags_page "$(tag 2.7.3 8)"
    echo "refs/heads/feat/upgrade-nifi-to-2.7.3" >gh-branch
    echo "#12 (OPEN)" >gh-prs
    EVENT_NAME=schedule run_check
    assert_nothing_found
    assert_contains "$(cat gh.log)" "git/matching-refs/heads/feat/upgrade-nifi-to-2.7.3" "gh calls"
}

test_a_manual_run_fails_on_an_existing_branch_without_pull_requests() {
    tags_page "$(tag 2.7.3 8)"
    echo "refs/heads/feat/upgrade-nifi-to-2.7.3" >gh-branch
    run_check
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" \
        "Branch feat/upgrade-nifi-to-2.7.3 already exists. To upgrade to Apache NiFi 2.7.3 again," "output"
    assert_eq "" "$(output_value github-output version)" "version"
}

test_a_run_outside_github_actions_fails_on_an_existing_branch() {
    tags_page "$(tag 2.7.3 8)"
    echo "refs/heads/feat/upgrade-nifi-to-2.7.3" >gh-branch
    EVENT_NAME="" run_check
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "Branch feat/upgrade-nifi-to-2.7.3 already exists" "output"
}

test_a_target_version_ignores_the_minimum_age() {
    single_tag 2.8.0 1
    TARGET_VERSION=2.8.0 run_check
    assert_found 2.8.0
}

test_a_target_version_equal_to_the_current_one_fails() {
    single_tag 2.7.2 30
    TARGET_VERSION=2.7.2 run_check
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "Apache NiFi 2.7.2 is not newer than the current 2.7.2" "output"
}

test_a_target_version_older_than_the_current_one_fails() {
    single_tag 2.7.1 30
    TARGET_VERSION=2.7.1 run_check
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "Apache NiFi 2.7.1 is not newer than the current 2.7.2" "output"
}

test_a_target_version_of_another_major_version_fails() {
    single_tag 3.0.0 30
    TARGET_VERSION=3.0.0 run_check
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "Apache NiFi 3.0.0 has a different major version" "output"
}

test_a_target_version_that_is_not_x_y_z_fails() {
    TARGET_VERSION=2.8 run_check
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "TARGET_VERSION must be X.Y.Z, found '2.8'" "output"
}

test_a_target_version_without_a_tag_fails() {
    single_tag 2.8.1 30
    TARGET_VERSION=2.8.0 run_check
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "Cannot read the apache/nifi:2.8.0 tag" "output"
}

test_a_target_version_without_a_digest_fails() {
    single_tag 2.8.0 30 ""
    TARGET_VERSION=2.8.0 run_check
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "The apache/nifi:2.8.0 tag on Docker Hub has no digest" "output"
}

test_a_manual_run_fails_on_an_existing_branch_and_names_its_pull_requests() {
    single_tag 2.8.0 30
    echo "refs/heads/feat/upgrade-nifi-to-2.8.0" >gh-branch
    echo "#12 (CLOSED)" >gh-prs
    TARGET_VERSION=2.8.0 run_check
    assert_eq 1 "$STATUS" "exit status"
    assert_contains "$OUTPUT" "pull requests from it already exist: #12 (CLOSED)" "output"
    assert_eq "" "$(output_value github-output version)" "version"
}

run_tests

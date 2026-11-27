#!/bin/bash
# A small test harness for the scripts in .github/workflows/sh, sourced by the *-test.sh files next to it.
# A test is a function whose name starts with test_. run_tests runs each one in a subshell, in a new empty
# directory with a bin/ directory first on PATH for the stubs of the test, and prints "ok" or "not ok" with the
# output of the failed test. An assertion fails the test it runs in at once.

set -euo pipefail

SH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export SH_DIR

# Runs a command and keeps its exit status in STATUS and its stdout and stderr in OUTPUT, so that a test can
# assert on a script that is expected to fail.
# shellcheck disable=SC2034 # OUTPUT and STATUS are read by the test files that source this one.
run() {
    set +e
    OUTPUT=$("$@" 2>&1)
    STATUS=$?
    set -e
}

fail() {
    echo "FAILED: $*" >&2
    exit 1
}

assert_eq() {
    local expected="$1" actual="$2" what="$3"
    if [ "$expected" != "$actual" ]; then
        fail "$what: expected <$expected> but was <$actual>"
    fi
}

assert_contains() {
    local haystack="$1" needle="$2" what="$3"
    if [[ "$haystack" != *"$needle"* ]]; then
        fail "$what: expected to contain <$needle> but was <$haystack>"
    fi
}

assert_not_contains() {
    local haystack="$1" needle="$2" what="$3"
    if [[ "$haystack" == *"$needle"* ]]; then
        fail "$what: expected not to contain <$needle> but was <$haystack>"
    fi
}

assert_file_missing() {
    local path="$1"
    if [ -e "$path" ]; then
        fail "expected no file $path, but it exists with <$(cat "$path")>"
    fi
}

# Prints the value of a key=value line of a GitHub Actions output file, or nothing when the key is missing.
output_value() {
    local file="$1" key="$2"
    sed -n "s/^$key=//p" "$file"
}

# Writes an executable stub to the bin/ directory of the test, which is first on PATH, whatever the current
# directory of the test is.
stub() {
    local name="$1"
    cat >"$TEST_DIR/bin/$name"
    chmod +x "$TEST_DIR/bin/$name"
}

run_tests() {
    local test_name test_dir status failed=0 total=0
    for test_name in $(declare -F | awk '$3 ~ /^test_/ { print $3 }'); do
        total=$((total + 1))
        test_dir=$(mktemp -d)
        TEST_DIR="$test_dir"
        set +e
        (
            set -euo pipefail
            cd "$test_dir"
            mkdir -p bin
            PATH="$test_dir/bin:$PATH"
            "$test_name"
        ) >"$test_dir.log" 2>&1
        status=$?
        set -e
        if [ "$status" -eq 0 ]; then
            echo "ok - $test_name"
        else
            echo "not ok - $test_name"
            sed 's/^/    /' "$test_dir.log"
            failed=$((failed + 1))
        fi
        rm -rf "$test_dir" "$test_dir.log"
    done
    echo "$((total - failed)) of $total tests passed in $(basename "$0")"
    [ "$failed" -eq 0 ]
}

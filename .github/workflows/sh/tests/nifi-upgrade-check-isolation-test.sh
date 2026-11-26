#!/bin/bash
# Tests for nifi-upgrade-check-isolation.sh. sudo is replaced by a stub that plays the agent user: it runs env as it
# is, so that the tools are looked up on the agent PATH, and lets any other command succeed only when the file
# can/<command> exists.

# shellcheck source=test-lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/test-lib.sh"

# Sets up an agent user that can do nothing of what the check looks for, and has java, mvn, and opencode in tools/.
setup_isolated_user() {
    local tool
    mkdir -p can tools
    for tool in java mvn opencode; do
        printf '#!/bin/bash\n' >"tools/$tool"
        chmod +x "tools/$tool"
    done
    stub sudo <<'EOF'
#!/bin/bash
shift 2
if [ "$1" = env ]; then
    exec "$@"
fi
[ -e "can/$1" ]
EOF
    stub pgrep <<'EOF'
#!/bin/bash
echo 4242
EOF
}

run_check() {
    run env AGENT_PATH="$TEST_DIR/tools:/usr/bin:/bin" RUNNER_HOME="$TEST_DIR/runner" \
        bash "$SH_DIR/nifi-upgrade-check-isolation.sh"
}

test_an_isolated_user_with_its_tools_passes() {
    setup_isolated_user
    run_check
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "The agent user is isolated and has its tools." "output"
}

test_a_user_that_can_run_sudo_fails() {
    setup_isolated_user
    touch can/sudo
    run_check
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "::error::nifi-agent can run sudo" "output"
}

test_a_user_that_can_use_docker_fails() {
    setup_isolated_user
    touch can/docker
    run_check
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "::error::nifi-agent can use Docker" "output"
}

test_a_user_that_can_read_the_runner_worker_environment_fails() {
    setup_isolated_user
    touch can/cat
    run_check
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "::error::nifi-agent can read Runner.Worker" "output"
}

test_a_missing_runner_worker_process_fails() {
    setup_isolated_user
    stub pgrep <<'EOS'
#!/bin/bash
exit 1
EOS
    run_check
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "::error::Cannot find the Runner.Worker process to check that nifi-agent cannot read it" \
        "output"
}

test_a_user_that_can_list_the_runner_work_directory_fails() {
    setup_isolated_user
    touch can/ls
    run_check
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "::error::nifi-agent can read the runner home" "output"
}

test_a_user_without_opencode_on_its_path_fails() {
    setup_isolated_user
    rm tools/opencode
    run_check
    assert_eq 1 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$OUTPUT" "::error::nifi-agent cannot find java, mvn, or opencode" "output"
}

run_tests

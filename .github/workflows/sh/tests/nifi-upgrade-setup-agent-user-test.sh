#!/bin/bash
# Tests for nifi-upgrade-setup-agent-user.sh. sudo is replaced by a stub that runs the command as it is, and useradd,
# chmod, chown, and install by stubs that record their arguments in the file calls, because the tests do not run as
# root and Git Bash on Windows cannot set the mode of a directory. The useradd and install stubs also create the
# directory they are given.

# shellcheck source=test-lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/test-lib.sh"

# Sets up a workspace, a runner home with Maven settings and a repository, and the stubs.
setup_user() {
    mkdir -p workspace runner/.m2/repository/org/example
    echo "readme" >workspace/README.md
    echo "<settings/>" >runner/.m2/settings.xml
    echo "jar" >runner/.m2/repository/org/example/example.jar
    stub sudo <<'EOF'
#!/bin/bash
exec "$@"
EOF
    stub useradd <<EOF
#!/bin/bash
echo "useradd \$*" >>"$TEST_DIR/calls"
while [ "\$#" -gt 0 ]; do
    if [ "\$1" = --home-dir ]; then
        mkdir -p "\$2"
    fi
    shift
done
EOF
    stub chown <<EOF
#!/bin/bash
echo "chown \$*" >>"$TEST_DIR/calls"
EOF
    stub install <<EOF
#!/bin/bash
echo "install \$*" >>"$TEST_DIR/calls"
mkdir -p "\${@: -1}"
EOF
    # The last stub, because stub runs chmod on every stub it writes.
    stub chmod <<EOF
#!/bin/bash
echo "chmod \$*" >>"$TEST_DIR/calls"
EOF
}

run_setup() {
    run env AGENT_HOME="$TEST_DIR/agent-home" RUNNER_HOME="$TEST_DIR/runner" GITHUB_WORKSPACE="$TEST_DIR/workspace" \
        bash "$SH_DIR/nifi-upgrade-setup-agent-user.sh"
}

test_the_user_is_created_with_a_home_and_bash() {
    setup_user
    run_setup
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_contains "$(cat calls)" "useradd --create-home --home-dir $TEST_DIR/agent-home --shell /bin/bash nifi-agent" \
        "calls"
}

test_the_runner_home_is_closed_to_other_users() {
    setup_user
    run_setup
    assert_contains "$(cat calls)" "chmod 750 $TEST_DIR/runner" "calls"
}

test_the_maven_directory_of_the_agent_is_closed_to_other_users() {
    setup_user
    run_setup
    assert_contains "$(cat calls)" "install -d -m 700 $TEST_DIR/agent-home/.m2" "calls"
}

test_the_repository_and_the_maven_files_are_copied_to_the_agent_home() {
    setup_user
    run_setup
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq "readme" "$(cat agent-home/repo/README.md)" "README.md of the agent copy"
    assert_eq "<settings/>" "$(cat agent-home/.m2/settings.xml)" "Maven settings of the agent"
    assert_eq "jar" "$(cat agent-home/.m2/repository/org/example/example.jar)" "Maven repository of the agent"
}

test_the_copies_are_given_to_the_agent_user() {
    setup_user
    run_setup
    assert_contains "$(cat calls)" "chown -R nifi-agent:nifi-agent $TEST_DIR/agent-home/repo $TEST_DIR/agent-home/.m2" \
        "calls"
}

run_tests

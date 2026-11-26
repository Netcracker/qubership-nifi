#!/bin/bash
# Tests for nifi-upgrade-run-agent.sh. sudo is replaced by a stub that records its arguments in sudo-args and runs the
# command as sudo -H --preserve-env=<list> does on the runner: with HOME set to agent-home, PATH set to secure_path,
# the variables of the list, and XDG_CONFIG_HOME, which reaches the agent on the runner too. The agent script in
# agent-home/repo writes its environment to agent-env and its working directory to agent-pwd, and exits with the
# status in agent-status, 0 by default.

# shellcheck source=test-lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/test-lib.sh"

setup_agent() {
    mkdir -p agent-home/repo/.github/workflows/sh
    cat >agent-home/repo/.github/workflows/sh/nifi-upgrade-agent.sh <<'EOF'
env >"$HOME/agent-env"
pwd >"$HOME/agent-pwd"
exit "$(cat "$HOME/agent-status" 2>/dev/null || echo 0)"
EOF
    stub sudo <<'EOF'
#!/bin/bash
printf '%s\n' "$@" >sudo-args
vars=(HOME="$PWD/agent-home" PATH=/usr/sbin:/usr/bin:/sbin:/bin)
if [ -n "${XDG_CONFIG_HOME:-}" ]; then
    vars+=(XDG_CONFIG_HOME="$XDG_CONFIG_HOME")
fi
while [ "$#" -gt 0 ]; do
    case "$1" in
        -u) shift 2 ;;
        -H) shift ;;
        --preserve-env=*)
            IFS=, read -ra names <<<"${1#--preserve-env=}"
            for name in "${names[@]}"; do
                if [ -n "${!name+set}" ]; then
                    vars+=("$name=${!name}")
                fi
            done
            shift
            ;;
        *) break ;;
    esac
done
exec env -i "${vars[@]}" "$@"
EOF
}

run_agent() {
    run env JAVA_HOME=/opt/jdk NIFI_VERSION=2.13.0 NIFI_DIGEST=sha256:abc REPORT_FILE=upgrade-temp-data/pr-body.md \
        GITHUB_ACTOR=octocat GITHUB_TOKEN=ghs-token OPENCODE_API_KEY=sk-key XDG_CONFIG_HOME=/home/runner/.config \
        bash "$SH_DIR/nifi-upgrade-run-agent.sh"
}

test_the_agent_runs_in_its_copy_of_the_repository() {
    setup_agent
    run_agent
    assert_eq 0 "$STATUS" "exit status, output: $OUTPUT"
    assert_eq "$TEST_DIR/agent-home/repo" "$(cat agent-home/agent-pwd)" "working directory of the agent"
}

test_the_agent_gets_the_variables_of_the_workflow() {
    setup_agent
    run_agent
    assert_eq 'CI=true
GITHUB_ACTOR=octocat
GITHUB_TOKEN=ghs-token
JAVA_HOME=/opt/jdk
NIFI_DIGEST=sha256:abc
NIFI_VERSION=2.13.0
OPENCODE_API_KEY=sk-key
REPORT_FILE=upgrade-temp-data/pr-body.md' \
        "$(grep -E '^(CI|GITHUB_ACTOR|GITHUB_TOKEN|JAVA_HOME|NIFI_DIGEST|NIFI_VERSION|OPENCODE_API_KEY|REPORT_FILE)=' \
            agent-home/agent-env | sort)" \
        "variables of the agent"
}

test_the_path_of_the_agent_starts_with_the_jdk() {
    setup_agent
    run_agent
    assert_eq "/opt/jdk/bin:/usr/local/bin:/usr/bin:/bin" "$(output_value agent-home/agent-env PATH)" "PATH of the agent"
}

# The first run of the agent user failed with EACCES on /home/runner/.config/opencode, because XDG_CONFIG_HOME of the
# runner reached it through sudo.
test_the_xdg_directories_are_in_the_agent_home() {
    setup_agent
    run_agent
    assert_eq "XDG_CACHE_HOME=$TEST_DIR/agent-home/.cache
XDG_CONFIG_HOME=$TEST_DIR/agent-home/.config
XDG_DATA_HOME=$TEST_DIR/agent-home/.local/share
XDG_STATE_HOME=$TEST_DIR/agent-home/.local/state" \
        "$(grep '^XDG_' agent-home/agent-env | sort)" "XDG directories of the agent"
}

test_the_secrets_are_not_on_the_sudo_command_line() {
    setup_agent
    run_agent
    assert_not_contains "$(cat sudo-args)" "ghs-token" "sudo arguments"
    assert_not_contains "$(cat sudo-args)" "sk-key" "sudo arguments"
}

test_a_failed_agent_keeps_its_exit_status() {
    setup_agent
    echo 3 >agent-home/agent-status
    run_agent
    assert_eq 3 "$STATUS" "exit status, output: $OUTPUT"
}

run_tests

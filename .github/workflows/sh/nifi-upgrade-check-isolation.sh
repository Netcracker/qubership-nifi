#!/bin/bash
# Fails when the agent user can run sudo, use Docker, read the environment of the Runner.Worker process, which holds
# the secrets of the job, or list the work directory of the runner user. Also fails when the user cannot find java,
# mvn, or opencode on AGENT_PATH, and when no Runner.Worker process is found to check.
#
# Environment:
#   JAVA_HOME    JDK that the agent runs Maven with
#   AGENT_USER   user that runs the agent, nifi-agent by default
#   AGENT_PATH   PATH of the agent, JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin by default
#   RUNNER_HOME  home directory of the runner user, HOME by default

set -uo pipefail

AGENT_USER="${AGENT_USER:-nifi-agent}"
AGENT_PATH="${AGENT_PATH:-$JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin}"
RUNNER_HOME="${RUNNER_HOME:-$HOME}"

fail() {
    echo "::error::$1"
    exit 1
}

main() {
    local pid

    sudo -u "$AGENT_USER" sudo -n true 2>/dev/null && fail "$AGENT_USER can run sudo"
    sudo -u "$AGENT_USER" docker info >/dev/null 2>&1 && fail "$AGENT_USER can use Docker"
    pid=$(pgrep -f Runner.Worker | head -1)
    # Without a pid the read below fails for want of a process, and the check would pass without checking anything.
    [ -n "$pid" ] || fail "Cannot find the Runner.Worker process to check that $AGENT_USER cannot read it"
    sudo -u "$AGENT_USER" cat "/proc/$pid/environ" >/dev/null 2>&1 && fail "$AGENT_USER can read Runner.Worker"
    sudo -u "$AGENT_USER" ls "$RUNNER_HOME/work" >/dev/null 2>&1 && fail "$AGENT_USER can read the runner home"
    # command -v with several names succeeds when it finds any one of them, so each tool is looked up on its own.
    sudo -u "$AGENT_USER" env PATH="$AGENT_PATH" bash -c 'command -v java && command -v mvn && command -v opencode' \
        || fail "$AGENT_USER cannot find java, mvn, or opencode"
    echo "The agent user is isolated and has its tools."
}

main "$@"

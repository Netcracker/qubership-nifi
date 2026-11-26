#!/bin/bash
# Copies the copy of the repository that the agent changed back to the workspace, without .git, and gives the files
# to the runner user. Fails and copies nothing when the copy contains a symlink outside .git: such a link could point
# at a file of the runner user, which upload-artifact would then read and publish, and the patch may not contain
# symlinks anyway.
#
# .git is not copied, because the next steps run git as the runner user, and a .git/config or a hook that the agent
# wrote, such as core.fsmonitor or core.hooksPath, would run code as that user.
#
# Environment:
#   AGENT_USER        user that ran the agent, nifi-agent by default
#   AGENT_HOME        home directory of AGENT_USER, /home/AGENT_USER by default
#   GITHUB_WORKSPACE  workspace to copy to, the current directory by default

set -euo pipefail

AGENT_USER="${AGENT_USER:-nifi-agent}"
AGENT_HOME="${AGENT_HOME:-/home/$AGENT_USER}"
WORKSPACE="${GITHUB_WORKSPACE:-$PWD}"

main() {
    local links

    links=$(sudo find "$AGENT_HOME/repo" -path '*/.git' -prune -o -type l -print)
    if [ -n "$links" ]; then
        echo "::error::The agent created symlinks, which the upgrade does not allow:"
        echo "$links"
        exit 1
    fi
    sudo rsync -a --delete --exclude=.git "$AGENT_HOME/repo/" "$WORKSPACE/"
    sudo chown -R "$(id -u):$(id -g)" "$WORKSPACE"
}

main "$@"

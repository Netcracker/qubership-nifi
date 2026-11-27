#!/bin/bash
# Creates the user that runs the agent, without sudo rights and outside the docker group, so that the agent cannot
# read the memory of the runner processes, use /var/run/docker.sock (root:docker 660), or change the files of the
# runner user that later steps run. Gives the user a copy of the repository and of the Maven settings and repository.
# Runs as the runner user.
#
# Environment:
#   AGENT_USER        user to create, nifi-agent by default
#   AGENT_HOME        home directory of AGENT_USER, /home/AGENT_USER by default
#   RUNNER_HOME       home directory of the runner user, HOME by default
#   GITHUB_WORKSPACE  repository to copy, the current directory by default

set -euo pipefail

AGENT_USER="${AGENT_USER:-nifi-agent}"
AGENT_HOME="${AGENT_HOME:-/home/$AGENT_USER}"
RUNNER_HOME="${RUNNER_HOME:-$HOME}"
WORKSPACE="${GITHUB_WORKSPACE:-$PWD}"

main() {
    sudo useradd --create-home --home-dir "$AGENT_HOME" --shell /bin/bash "$AGENT_USER"
    # Keeps other users out of the workspace and of _temp, which holds the GITHUB_ENV and GITHUB_PATH files.
    sudo chmod 750 "$RUNNER_HOME"
    sudo cp -a "$WORKSPACE/." "$AGENT_HOME/repo/"
    sudo install -d -m 700 "$AGENT_HOME/.m2"
    sudo cp "$RUNNER_HOME/.m2/settings.xml" "$AGENT_HOME/.m2/settings.xml"
    sudo cp -a "$RUNNER_HOME/.m2/repository" "$AGENT_HOME/.m2/repository"
    sudo chown -R "$AGENT_USER:$AGENT_USER" "$AGENT_HOME/repo" "$AGENT_HOME/.m2"
}

main "$@"

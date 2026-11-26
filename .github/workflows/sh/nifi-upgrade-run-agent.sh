#!/bin/bash
# Runs nifi-upgrade-agent.sh as the agent user, in the copy of the repository in its home directory, and exits with
# the exit status of the agent.
#
# --preserve-env with a list passes the variables through the environment, because a value on the sudo command line
# shows in ps to every user while sudo runs. sudo replaces PATH with secure_path, so AGENT_PATH carries the PATH of
# the agent. XDG_CONFIG_HOME of the runner user still reaches the agent and points into the runner home, which the
# agent user cannot write, so the XDG directories are set to the home directory of the agent user.
#
# Environment:
#   JAVA_HOME                       JDK that the agent runs Maven with
#   NIFI_VERSION, NIFI_DIGEST       target Apache NiFi version and the digest of its image
#   REPORT_FILE                     file that the agent writes its report to
#   GITHUB_ACTOR, GITHUB_TOKEN      credentials for GitHub Packages, read by the Maven settings of setup-java
#   OPENCODE_API_KEY                key of the model provider
#   AGENT_USER                      user that runs the agent, nifi-agent by default
#   AGENT_PATH                      PATH of the agent, JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin by default

set -euo pipefail

AGENT_USER="${AGENT_USER:-nifi-agent}"
export CI=true AGENT_PATH="${AGENT_PATH:-$JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin}"

exec sudo -u "$AGENT_USER" -H \
    --preserve-env=CI,JAVA_HOME,AGENT_PATH,NIFI_VERSION,NIFI_DIGEST,REPORT_FILE,GITHUB_ACTOR,GITHUB_TOKEN,OPENCODE_API_KEY \
    bash -c 'export PATH="$AGENT_PATH" XDG_CONFIG_HOME="$HOME/.config" XDG_DATA_HOME="$HOME/.local/share" \
            XDG_CACHE_HOME="$HOME/.cache" XDG_STATE_HOME="$HOME/.local/state"
        cd ~/repo && exec bash .github/workflows/sh/nifi-upgrade-agent.sh'

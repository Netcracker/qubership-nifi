#!/bin/bash
# Prepares the inputs of nifi-upgrade-skill that need Docker, so that the agent runs without Docker.
# The workflow runs it before the agent, on the code of the checked-out commit.
#
# Environment:
#   NIFI_VERSION  target Apache NiFi version, for example 2.13.0
#   GITHUB_ACTOR, GITHUB_TOKEN  credentials for GitHub Packages, read by the Maven settings of setup-java
set -euo pipefail

: "${NIFI_VERSION:?NIFI_VERSION is not set}"
CURRENT=$(sed -n "s/.*NIFI_VERSION='\([0-9.]*\)'.*/\1/p" ./Dockerfile | head -1)
if [ -z "$CURRENT" ]; then
    echo "::error::Cannot read the current Apache NiFi version from the Dockerfile." >&2
    exit 1
fi
MVN=(mvn --batch-mode --no-transfer-progress -Dgpg.skip=true)

bash .claude/skills/nifi-upgrade-skill/scripts/getFilesFromDocker.sh "$CURRENT" "$NIFI_VERSION"

"${MVN[@]}" install -DskipUnitTests=true -am -pl qubership-nifi-tools/qubership-nifi-api-export-tool
for version in "$CURRENT" "$NIFI_VERSION"; do
    "${MVN[@]}" -q exec:java -pl qubership-nifi-tools/qubership-nifi-api-export-tool -DROOT_LOG_LEVEL=ERROR \
        -Dexec.args="--version $version --output-dir ./upgrade-temp-data/nifi-property-exports/$version"
done

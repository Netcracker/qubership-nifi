#!/bin/bash -e

pathToFlow="$1"
echo "Starting upgrade scripts for sources: $pathToFlow"

echo "Executing upgrade scripts 2.0"
cd /scripts/2.0/
bash updateNiFiFlow.sh "$pathToFlow" ./updateNiFiVerNarConfig.json
echo "Finished upgrade scripts 2.0"

echo "Executing upgrade scripts 2.x"
cd /scripts/2.x/
bash analyzeAndUpdateNiFiExports.sh "$pathToFlow"
echo "Finished upgrade scripts 2.x"

echo "Finish upgrade scripts"

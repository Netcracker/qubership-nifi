#!/bin/bash -e

pathToFlow="$1"
echo "Starting upgrade scripts for sources: $pathToFlow"

echo "Executing upgrade scripts 2.0"
cd /scripts/2.0/
bash updateNiFiFlow.sh "$pathToFlow" ./updateNiFiVerNarConfig.json
echo "Finished  upgrade scripts 2.0"

echo "Finish upgrade scripts"

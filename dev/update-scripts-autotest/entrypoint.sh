#!/bin/bash -e

pathToFlow="$1"
echo "Starting upgrade scripts for sources: $pathToFlow"

echo "Executing upgrade scripts 2.0"
cd /scripts/2.0/
bash updateNiFiFlow.sh "$pathToFlow" ./updateNiFiVerNarConfig.json
echo "Finished  upgrade scripts 2.0"

echo "Executing upgrade scripts 2.5"
cd /scripts/2.5/
bash analyzeAndUpdateNiFiExports.sh "$pathToFlow" ./upgradeConfig_2_5.json
echo "Finished  upgrade scripts 2.5"

echo "Executing upgrade scripts 2.7"
cd /scripts/2.7/
bash analyzeAndUpdateNiFiExports.sh "$pathToFlow" ./upgradeConfig_2_7.json
echo "Finished  upgrade scripts 2.7"

echo "Finish upgrade scripts"

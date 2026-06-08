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

echo "Executing external controller services update scripts 2.x"
cd /scripts/ext-cs-2.x/
bash updateExternalControllerServices.sh "$pathToFlow"
echo "Finished external controller services update scripts 2.x"

echo "Finish upgrade scripts"

#!/bin/bash

echo "Settign ENV variable"
#External url for NiFi
export NIFI_TARGET_URL="https://localhost:8443"
#
export DEBUG_MODE="false"
#
export NIFI_CERT=""
#Path to the directory with exported flows
pathToFlow="./export"
#Path to the configuration file
pathToUpdateNiFiConfig="./updateNiFiVerNarConfig.json"

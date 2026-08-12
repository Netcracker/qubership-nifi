#!/bin/sh
set -eu

JAR="$(find /opt/flow-diff/lib -maxdepth 1 -name 'qubership-nifi-flow-diff-cli-*.jar' | head -n 1)"
exec java -jar "$JAR" "$@"

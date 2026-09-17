#!/bin/bash
# Copyright 2020-2025 NetCracker Technology Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Starts the consul app under the base image entrypoint, which fills the Java trust store and forwards
# SIGTERM from the container stop command to the JVM, as it does for NiFi.

mkdir -p "$NIFI_HOME/conf" "$NIFI_HOME/persistent_conf/conf" "$NIFI_HOME/persistent_conf/conf-restore" /tmp/bench
rm -f /tmp/initial-config-completed.txt /tmp/bench/exec-time.txt

case "$FRAMEWORK" in
    spring) jar=/app/app.jar ;;
    quarkus) jar=/app/quarkus-app/quarkus-run.jar ;;
    *)
        echo "Unknown FRAMEWORK '$FRAMEWORK', expected spring or quarkus" >&2
        exit 1
        ;;
esac

# exec keeps the process ID, so this is the JVM process ID that sample.sh reads.
echo $$ > /tmp/bench/app.pid

# Measures the time from JVM launch to the completion file that nifi-scripts/start.sh waits for.
# The file holds two epoch timestamps in seconds: launch and completion.
start_time=$EPOCHREALTIME
(
    while [ ! -e /tmp/initial-config-completed.txt ]; do
        sleep 0.05
    done
    echo "$start_time $EPOCHREALTIME" > /tmp/bench/exec-time.txt
) &

# JAVA_OPTS is split into words on purpose. start_consul_app.sh passes CONSUL_CONFIG_JAVA_OPTIONS through eval,
# which also processes quotes; the options in profiles/ contain no quotes, so both give the same arguments.
# Globbing is off, so an option such as -Xlog:gc* is passed as written.
set -f
# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar "$jar"

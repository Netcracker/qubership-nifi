#!/bin/bash -e
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

# shellcheck source=/dev/null
# shellcheck disable=SC2034
# shellcheck disable=SC2086
. /opt/nifi/scripts/logging_api.sh

info "Starting consul app with options: $CONSUL_CONFIG_JAVA_OPTIONS"
info "Consul integration framework = $NIFI_CONSUL_INT_FRAMEWORK"
if [ "$NIFI_CONSUL_INT_FRAMEWORK" = 'spring' ]; then
    # if mode explicitly set as spring, then run spring:
    eval "$JAVA_HOME"/bin/java "$CONSUL_CONFIG_JAVA_OPTIONS" \
        -jar "$NIFI_HOME"/utility-lib/qubership-nifi-consul-application.jar &
    consul_pid=$!
else
    # if mode explicitly set as quarkus, use quarkus:
    # or if nothing set, use quarkus:
    eval "$JAVA_HOME"/bin/java "$CONSUL_CONFIG_JAVA_OPTIONS" \
        -jar "$NIFI_HOME"/utility-lib/qubership-nifi-quarkus-consul-application/quarkus-run.jar &
    consul_pid=$!
fi


info "Consul application pid: $consul_pid"

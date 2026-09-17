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

# Writes 1000 keys to Consul under config/local/:
#   800 unrelated to NiFi: 300 in the application root and 100 in the qubership-nifi root,
#       which the app watches and filters out, and 400 in 20 other service roots, which it never reads;
#   200 read by the app: 170 loggers, 10 custom properties, and 20 regular nifi.properties keys.

set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

EXPECTED_KEY_COUNT=1000

emit() {
    printf '%s\t%s\n' "$1" "$2"
}

generate_keys() {
    local i
    for i in $(seq 1 400); do
        emit "$(unrelated_watched_key "$i")" "unrelated-value-$i"
    done
    for i in $(seq 1 400); do
        emit "$(printf 'config/%s/bench-other-service-%02d/prop-%03d' "$NAMESPACE" $(( (i - 1) / 20 + 1 )) "$i")" \
            "other-service-value-$i"
    done
    for i in $(seq 1 "$LOGGER_COUNT"); do
        emit "$(logger_key "$i")" "$(logger_level "$i" 0)"
    done

    # The 9 keys ConsulConfiguration writes to custom.properties, plus one read-only key.
    emit "$APP_ROOT/nifi.http-auth-proxying-disabled-schemes" "Default"
    emit "$APP_ROOT/nifi.http-auth-tunneling-disabled-schemes" "Default"
    emit "$APP_ROOT/nifi.cluster.base-node-count" "3"
    emit "$APP_ROOT/nifi.cluster.start-mode" "auto"
    emit "$APP_ROOT/nifi.nifi-registry.nar-provider-enabled" "false"
    emit "$APP_ROOT/nifi.conf.clean-db-repository" "false"
    emit "$APP_ROOT/nifi.conf.clean-configuration" "false"
    emit "$APP_ROOT/nifi.extensions.retry.attempts" "5"
    emit "$APP_ROOT/nifi.extensions.retry.delay" "10 sec"
    emit "$APP_ROOT/nifi.security.identity.mapping.pattern.dn" '^CN=(.*?), OU=(.*?)$'

    # Regular keys from qubership-nifi-consul-templates nifi_default.properties.
    emit "$APP_ROOT/nifi.flow.configuration.archive.max.time" "14 days"
    emit "$APP_ROOT/nifi.flow.configuration.archive.max.storage" "200 MB"
    emit "$APP_ROOT/nifi.flowcontroller.graceful.shutdown.period" "20 sec"
    emit "$APP_ROOT/nifi.flowservice.writedelay.interval" "1 sec"
    emit "$APP_ROOT/nifi.administrative.yield.duration" "15 sec"
    emit "$APP_ROOT/nifi.bored.yield.duration" "20 millis"
    emit "$APP_ROOT/nifi.queue.backpressure.count" "20000"
    emit "$APP_ROOT/nifi.queue.backpressure.size" "2 GB"
    emit "$APP_ROOT/nifi.queue.swap.threshold" "25000"
    emit "$APP_ROOT/nifi.flowfile.repository.checkpoint.interval" "30 secs"
    emit "$APP_ROOT/nifi.content.claim.max.appendable.size" "50 KB"
    emit "$APP_ROOT/nifi.content.repository.archive.max.retention.period" "3 days"
    emit "$APP_ROOT/nifi.content.repository.archive.max.usage.percentage" "40%"
    emit "$APP_ROOT/nifi.provenance.repository.max.storage.time" "7 days"
    emit "$APP_ROOT/nifi.provenance.repository.max.storage.size" "2 GB"
    emit "$APP_ROOT/nifi.provenance.repository.rollover.time" "5 mins"
    emit "$APP_ROOT/nifi.provenance.repository.query.threads" "4"
    emit "$APP_ROOT/nifi.provenance.repository.index.threads" "4"
    emit "$APP_ROOT/nifi.components.status.snapshot.frequency" "30 sec"
    emit "$APP_ROOT/nifi.web.jetty.threads" "100"
}

log "Seeding Consul with $EXPECTED_KEY_COUNT keys"
generate_keys \
    | jq -R -s 'split("\n") | map(select(length > 0) | split("\t") | {key: .[0], flags: 0, value: (.[1] | @base64)})' \
    | engine exec -i "$CONSUL_CONTAINER" consul kv import - > /dev/null

actual=$(consul_curl "$CONSUL_HTTP/v1/kv/config/${NAMESPACE}/?keys" | jq length)
[ "$actual" -eq "$EXPECTED_KEY_COUNT" ] || die "Expected $EXPECTED_KEY_COUNT keys in Consul, found $actual"
log "Consul holds $actual keys"

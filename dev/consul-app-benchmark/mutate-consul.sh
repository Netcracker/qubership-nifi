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

# Applies change round <round> (1 or more): moves 50 loggers to their next level and gives
# 50 watched unrelated properties a new value. Each half is one Consul transaction.
#
# Usage: mutate-consul.sh <round>

set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

round="${1:?Usage: mutate-consul.sh <round>}"

# Reads "key<TAB>value" lines and sends them as one Consul transaction (at most 64 operations).
send_txn() {
    jq -R -s 'split("\n") | map(select(length > 0) | split("\t")
        | {KV: {Verb: "set", Key: .[0], Value: (.[1] | @base64)}})' \
        | consul_curl -X PUT --data-binary @- "$CONSUL_HTTP/v1/txn" > /dev/null
}

for i in $(seq 1 "$LOGGER_MUTATED_COUNT"); do
    printf '%s\t%s\n' "$(logger_key "$i")" "$(logger_level "$i" "$round")"
done | send_txn

for i in "${MUTATED_UNRELATED_IDS[@]}"; do
    printf '%s\t%s\n' "$(unrelated_watched_key "$i")" "round-${round}-${RANDOM}"
done | send_txn

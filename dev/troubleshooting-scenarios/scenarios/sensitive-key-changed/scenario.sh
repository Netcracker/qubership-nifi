#!/bin/bash
# Copyright 2020-2026 NetCracker Technology Corporation
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

# Hooks for the sensitive-key-changed scenario: NiFi starts once with one NIFI_NEW_SENSITIVE_KEY, then starts again
# with another one, and the start script refuses the key that does not match the hash saved on the first start.
# Sourced by run.sh and collect-evidence.sh after lib.sh.
# shellcheck disable=SC2034

# nifi-scripts/re_encrypt_sensitive_keys.sh exits with 3 when the key hashes differ.
SCENARIO_EXPECTED_EXIT_CODE=3

scenario_check_prereqs() {
    require_commands sha256sum
}

scenario_break() {
    local newKey
    newKey=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
    echo -n "$newKey" >"$WORK_DIR/new-sensitive-key"
    log "Starting NiFi again with a different NIFI_NEW_SENSITIVE_KEY..."
    NIFI_SENSITIVE_KEY="$newKey" recreate_nifi
}

# Prints the key hash in the form the start script saves it: the sha256sum output line.
key_hash() {
    echo -n "$1" | sha256sum
}

scenario_evidence() {
    local bundleDir="$1"
    {
        echo "## Hashes of the sensitive keys; the keys themselves are not recorded"
        echo "old_key_hash file:   $(helper_run 'cat /work/temp-vol/nifi/per-conf/old_key_hash')"
        echo "first start key:     $(key_hash "$(sed -n 's/^NIFI_SENSITIVE_KEY=//p' "$WORK_DIR/docker.env")")"
        [ ! -f "$WORK_DIR/new-sensitive-key" ] ||
            echo "second start key:    $(key_hash "$(cat "$WORK_DIR/new-sensitive-key")")"
        echo
        echo "## Startup errors"
        grep -E 'oldKeyHash|newKeyHash|sensitive' "$bundleDir/container.log" || true
    } >"$bundleDir/scenario-sensitive-key.txt"
}

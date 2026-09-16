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

# Runs each profile for both frameworks, one run at a time, and prints a comparison table.
# A profile that is missing for a framework is skipped. A failed run does not stop the others.
#
# Usage: run-all.sh [profile]...    (default: baseline recommended)

set -uo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

profiles=("$@")
[ ${#profiles[@]} -gt 0 ] || profiles=(baseline recommended)

started=$(date +%Y%m%d-%H%M%S)
for profile in "${profiles[@]}"; do
    for framework in spring quarkus; do
        if [ ! -f "$BENCH_DIR/profiles/$framework/$profile.opts" ]; then
            log "Skipping $framework $profile: no profile file"
            continue
        fi
        "$BENCH_DIR/run-benchmark.sh" "$framework" "$profile" || log "Run $framework $profile failed"
    done
done

dirs=()
for dir in "$BENCH_DIR"/results/*; do
    [ -f "$dir/summary.json" ] || continue
    # Directory names end with the run timestamp; keep the runs this invocation started.
    [[ "${dir: -15}" < "$started" ]] || dirs+=("$dir")
done
[ ${#dirs[@]} -gt 0 ] && "$BENCH_DIR/report.sh" "${dirs[@]}"

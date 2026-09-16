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

# Samples the memory and CPU use of the consul app until its container stops.
# Appends one CSV row per sample; all sizes are in KB.
#
# Usage: sample.sh <output.csv> [interval-seconds]

set -uo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

out="${1:?Usage: sample.sh <output.csv> [interval-seconds]}"
interval="${2:-10}"

# Runs inside the container. /proc gives RSS, the RSS high-water mark, threads, and CPU ticks;
# jstat gives heap and metaspace use, and jcmd gives code cache use.
# shellcheck disable=SC2016
probe='
pid=$(cat /tmp/bench/app.pid)
status=$(cat /proc/$pid/status)
rss=$(echo "$status" | awk "/^VmRSS:/ {print \$2}")
hwm=$(echo "$status" | awk "/^VmHWM:/ {print \$2}")
threads=$(echo "$status" | awk "/^Threads:/ {print \$2}")
cpu_ticks=$(awk "{print \$14 + \$15}" /proc/$pid/stat)
heap=$($JAVA_HOME/bin/jstat -gc $pid 2>/dev/null | awk "
    NR == 1 { for (i = 1; i <= NF; i++) col[\$i] = i; next }
    NR == 2 {
        used = \$col[\"S0U\"] + \$col[\"S1U\"] + \$col[\"EU\"] + \$col[\"OU\"]
        committed = \$col[\"S0C\"] + \$col[\"S1C\"] + \$col[\"EC\"] + \$col[\"OC\"]
        printf \"%d,%d,%d,%d\", used, committed, \$col[\"MU\"], \$col[\"MC\"]
    }")
code=$($JAVA_HOME/bin/jcmd $pid Compiler.codecache 2>/dev/null | awk "
    /^CodeCache:/ {
        for (i = 1; i <= NF; i++) {
            if (\$i ~ /^used=/) { u = \$i; gsub(/[^0-9]/, \"\", u) }
            if (\$i ~ /^max_used=/) { m = \$i; gsub(/[^0-9]/, \"\", m) }
        }
    }
    END { printf \"%d,%d\", u, m }")
echo "$(date +%s),$rss,$hwm,$threads,$cpu_ticks,${heap:-,,,},${code:-,}"
'

echo "epoch_s,rss_kb,rss_hwm_kb,threads,cpu_ticks,heap_used_kb,heap_committed_kb,metaspace_used_kb,metaspace_committed_kb,codecache_used_kb,codecache_max_used_kb" > "$out"
while app_running; do
    app_exec sh -c "$probe" >> "$out" 2>/dev/null || true
    sleep "$interval"
done

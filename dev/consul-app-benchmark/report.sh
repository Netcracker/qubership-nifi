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

# Writes summary.json and summary.md into each results directory. With more than one directory,
# also prints a comparison table in Markdown.
#
# Usage: report.sh <results-dir>...

set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

[ $# -gt 0 ] || die "Usage: report.sh <results-dir>..."

# Prints "median min max" of the numbers on standard input, or nothing when there are none.
stats() {
    sort -g | awk '{ v[NR] = $1 } END {
        if (NR == 0) exit
        m = (NR % 2) ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2
        printf "%.2f %.2f %.2f", m, v[1], v[NR]
    }'
}

# Prints the value of flag $2 from the PrintFlagsFinal output in $1.
flag() {
    awk -v name="$2" '$2 == name { print $4; exit }' "$1"
}

# Prints "<category> <committed at exit> <peak>" in KB for the NMT categories in log $1.
# A category peak is the malloc peak plus the mmap or stack peak; NMT prints "at peak" when the peak
# equals the current value.
nmt_categories() {
    awk '
        function num(s) { gsub(/[^0-9]/, "", s); return s + 0 }
        function flush() { if (cat != "") printf "%s %d %d\n", cat, committed / 1024, (mpeak + vpeak) / 1024 }
        /^Native Memory Tracking:/ { in_nmt = 1; next }
        !in_nmt { next }
        /^-  / {
            flush()
            line = $0
            sub(/^- +/, "", line)
            cat = substr(line, 1, index(line, " (") - 1)
            gsub(/ /, "_", cat)
            match(line, /committed=[0-9]+/)
            committed = num(substr(line, RSTART, RLENGTH))
            mpeak = 0; vpeak = 0
            next
        }
        cat != "" && /\(malloc=/ {
            match($0, /malloc=[0-9]+/); cur = num(substr($0, RSTART, RLENGTH))
            if (match($0, /peak=[0-9]+/)) mpeak = num(substr($0, RSTART, RLENGTH)); else mpeak = cur
        }
        cat != "" && /\((mmap|stack):/ {
            match($0, /committed=[0-9]+/); cur = num(substr($0, RSTART, RLENGTH))
            if (match($0, /peak=[0-9]+/)) vpeak = num(substr($0, RSTART, RLENGTH)); else vpeak = cur
        }
        /^Total: / {
            match($0, /committed=[0-9]+/)
            printf "Total %d 0\n", num(substr($0, RSTART, RLENGTH)) / 1024
        }
        END { flush() }
    ' "$1"
}

# Prints "<committed at exit> <peak>" in KB for NMT category $2 from the output of nmt_categories in $1.
nmt() {
    awk -v name="$2" '$1 == name { print $2, $3; found = 1 } END { if (!found) print "null null" }' <<< "$1"
}

# Prints the largest value of CSV column $2 in samples file $1, or null.
sample_max() {
    [ -f "$1" ] || { echo null; return; }
    awk -F, -v name="$2" '
        NR == 1 { for (i = 1; i <= NF; i++) if ($i == name) c = i; next }
        c && $c != "" && ($c + 0 > m) { m = $c + 0; found = 1 }
        END { if (found) print m; else print "null" }' "$1"
}

or_null() {
    if [ -n "$1" ]; then echo "$1"; else echo null; fi
}

summarize() {
    local dir="$1" framework profile soak="$1/soak" regex
    framework=$(sed -n 's/^framework=//p' "$dir/environment.txt")
    profile=$(sed -n 's/^profile=//p' "$dir/environment.txt")
    regex=$(startup_line_regex "$framework")

    # Cold starts: time from JVM launch to the completion file, the startup time the framework
    # logs, and the RSS high-water mark at completion.
    local exec_times="" startup_times="" hwms="" cold
    for cold in "$dir"/cold-*; do
        [ -f "$cold/exec-time.txt" ] || continue
        exec_times+="$(awk '{ printf "%.3f", $2 - $1 }' "$cold/exec-time.txt")"$'\n'
        startup_times+="$(grep -oE "$regex" "$cold/app.log" | head -n 1 | grep -oE '[0-9]+(\.[0-9]+)?' || true)"$'\n'
        hwms+="$(cat "$cold/rss_hwm_kb.txt" 2>/dev/null || true)"$'\n'
    done
    local exec_stats startup_stats hwm_stats
    exec_stats=$(grep -v '^$' <<< "$exec_times" | stats || true)
    startup_stats=$(grep -v '^$' <<< "$startup_times" | stats || true)
    hwm_stats=$(grep -v '^$' <<< "$hwms" | stats || true)

    # Soak: NMT and the code cache report printed at exit, the GC log, and the samples.
    local categories="" code_max_used
    if [ -f "$soak/app.log" ]; then
        categories=$(nmt_categories "$soak/app.log")
    fi
    read -r heap_committed heap_peak <<< "$(nmt "$categories" Java_Heap)"
    read -r metaspace_committed metaspace_peak <<< "$(nmt "$categories" Metaspace)"
    read -r class_committed class_peak <<< "$(nmt "$categories" Class)"
    read -r code_committed code_peak <<< "$(nmt "$categories" Code)"
    read -r thread_committed thread_peak <<< "$(nmt "$categories" Thread)"
    read -r gc_committed gc_peak <<< "$(nmt "$categories" GC)"
    read -r total_committed _ <<< "$(nmt "$categories" Total)"
    code_max_used=$(grep -oE '^CodeCache: .*max_used=[0-9]+' "$soak/app.log" 2>/dev/null | tail -n 1 \
        | grep -oE 'max_used=[0-9]+' | cut -d= -f2 || true)

    # A pause line ends with "<before>M-><after>M(<capacity>M) <time>ms"; the after value is the live heap.
    local gc_heap_live_mb gc_metaspace_used_kb gc_metaspace_committed_kb gc_count full_gc_count gc_pause_ms
    gc_heap_live_mb=$(grep -E '\[gc +\] GC\([0-9]+\) Pause' "$soak/gc.log" 2>/dev/null \
        | grep -oE '[0-9]+M->[0-9]+M\(' | sed -E 's/.*->([0-9]+)M\(/\1/' | sort -g | tail -n 1 || true)
    gc_metaspace_used_kb=$({ grep -oE 'Metaspace: [0-9]+K' "$soak/gc.log" 2>/dev/null \
        | grep -oE '[0-9]+'; grep -oE 'Metaspace +used [0-9]+K' "$soak/gc.log" 2>/dev/null \
        | grep -oE '[0-9]+'; } | sort -g | tail -n 1 || true)
    gc_metaspace_committed_kb=$({ grep -oE 'Metaspace: [0-9]+K\([0-9]+K\)' "$soak/gc.log" 2>/dev/null \
        | grep -oE '\([0-9]+' | tr -d '('; grep -oE 'Metaspace +used [0-9]+K, committed [0-9]+K' "$soak/gc.log" \
        2>/dev/null | grep -oE '[0-9]+K$' | tr -d 'K'; } | sort -g | tail -n 1 || true)
    gc_count=$(grep -cE '\[gc +\] GC\([0-9]+\) Pause' "$soak/gc.log" 2>/dev/null || true)
    full_gc_count=$(grep -cE '\[gc +\] GC\([0-9]+\) Pause Full' "$soak/gc.log" 2>/dev/null || true)
    gc_pause_ms=$(grep -E '\[gc +\] GC\([0-9]+\) Pause' "$soak/gc.log" 2>/dev/null \
        | grep -oE '[0-9.]+ms$' | tr -d 'ms' | awk '{ s += $1 } END { printf "%.1f", s }' || true)

    local heap_live_peak="" metaspace_used_sample metaspace_used_peak metaspace_committed_sample
    local metaspace_committed_peak
    [ -n "$gc_heap_live_mb" ] && heap_live_peak=$(( gc_heap_live_mb * 1024 ))
    metaspace_used_sample=$(sample_max "$soak/samples.csv" metaspace_used_kb)
    metaspace_used_peak=$(printf '%s\n%s\n' "$metaspace_used_sample" "${gc_metaspace_used_kb:-0}" \
        | grep -v null | sed 's/\..*//' | sort -g | tail -n 1)
    # jstat and the GC log count class space in metaspace; NMT reports class space under Class.
    metaspace_committed_sample=$(sample_max "$soak/samples.csv" metaspace_committed_kb)
    metaspace_committed_peak=$(printf '%s\n%s\n' "$metaspace_committed_sample" "${gc_metaspace_committed_kb:-0}" \
        | grep -v null | sed 's/\..*//' | sort -g | tail -n 1)

    local soak_hwm cpu_ticks threads_peak rounds_failed update_max
    soak_hwm=$(awk '/^VmHWM:/ { print $2 }' "$soak/final-status.txt" 2>/dev/null || true)
    # rounds.txt holds "<round> <seconds from the start of the round until logback.xml held the new levels>".
    update_max=$(awk '$2 > m { m = $2 } END { if (NR) print m + 0 }' "$soak/rounds.txt" 2>/dev/null || true)
    cpu_ticks=$(cat "$soak/final-cpu-ticks.txt" 2>/dev/null || true)
    threads_peak=$(sample_max "$soak/samples.csv" threads)
    rounds_failed=$(grep -c '^soak round' "$dir/failures.txt" 2>/dev/null || true)

    jq -n \
        --arg framework "$framework" \
        --arg profile "$profile" \
        --arg java_opts "$(cat "$dir/java-opts.txt")" \
        --arg max_heap "$(flag "$dir/flags.txt" MaxHeapSize)" \
        --arg max_metaspace "$(flag "$dir/flags.txt" MaxMetaspaceSize)" \
        --arg code_cache "$(flag "$dir/flags.txt" ReservedCodeCacheSize)" \
        --arg compiler_count "$(flag "$dir/flags.txt" CICompilerCount)" \
        --arg serial "$(flag "$dir/flags.txt" UseSerialGC)" \
        --arg g1 "$(flag "$dir/flags.txt" UseG1GC)" \
        --arg exec_stats "$exec_stats" \
        --arg startup_stats "$startup_stats" \
        --arg hwm_stats "$hwm_stats" \
        --argjson heap_committed "$heap_committed" --argjson heap_peak "$heap_peak" \
        --argjson metaspace_committed "$metaspace_committed" --argjson metaspace_peak "$metaspace_peak" \
        --argjson class_committed "$class_committed" --argjson class_peak "$class_peak" \
        --argjson code_committed "$code_committed" --argjson code_peak "$code_peak" \
        --argjson thread_committed "$thread_committed" --argjson thread_peak "$thread_peak" \
        --argjson gc_committed "$gc_committed" --argjson gc_peak "$gc_peak" \
        --argjson total_committed "$total_committed" \
        --argjson heap_live_peak "$(or_null "$heap_live_peak")" \
        --argjson metaspace_used_peak "$(or_null "$metaspace_used_peak")" \
        --argjson metaspace_committed_peak "$(or_null "$metaspace_committed_peak")" \
        --argjson code_max_used "$(or_null "$code_max_used")" \
        --argjson gc_count "$(or_null "$gc_count")" \
        --argjson full_gc_count "$(or_null "$full_gc_count")" \
        --argjson update_max "$(or_null "$update_max")" \
        --argjson gc_pause_ms "$(or_null "$gc_pause_ms")" \
        --argjson soak_hwm "$(or_null "$soak_hwm")" \
        --argjson cpu_ticks "$(or_null "$cpu_ticks")" \
        --argjson threads_peak "$threads_peak" \
        --argjson rounds_failed "$(or_null "$rounds_failed")" \
        --arg failures "$(cat "$dir/failures.txt" 2>/dev/null || true)" \
        '
        def triple: if . == "" then null else (split(" ") | map(tonumber) | {median: .[0], min: .[1], max: .[2]}) end;
        {
            framework: $framework,
            profile: $profile,
            passed: ($failures | length == 0),
            failures: ($failures | split("\n") | map(select(length > 0))),
            java_opts: $java_opts,
            effective_flags: {
                MaxHeapSize: $max_heap, MaxMetaspaceSize: $max_metaspace, ReservedCodeCacheSize: $code_cache,
                CICompilerCount: $compiler_count, gc: (if $serial == "true" then "Serial" elif $g1 == "true" then "G1" else "other" end)
            },
            cold_start: {
                exec_time_s: ($exec_stats | triple),
                framework_startup_s: ($startup_stats | triple),
                rss_hwm_kb: ($hwm_stats | triple)
            },
            soak_kb: {
                heap: {committed_at_exit: $heap_committed, committed_peak: $heap_peak, live_after_gc_peak: $heap_live_peak},
                metaspace: {committed_peak: $metaspace_committed_peak, used_peak: $metaspace_used_peak},
                nmt_metaspace_without_class_space: {committed_at_exit: $metaspace_committed, committed_peak: $metaspace_peak},
                class_metadata: {committed_at_exit: $class_committed, committed_peak: $class_peak},
                code_cache: {committed_at_exit: $code_committed, committed_peak: $code_peak, used_peak: $code_max_used},
                threads: {committed_at_exit: $thread_committed, committed_peak: $thread_peak, count_peak: $threads_peak},
                gc_structures: {committed_at_exit: $gc_committed, committed_peak: $gc_peak},
                nmt_total_committed_at_exit: $total_committed,
                rss_hwm: $soak_hwm
            },
            soak: {
                cpu_time_s: (if $cpu_ticks == null then null else $cpu_ticks / 100 end),
                gc_pauses: $gc_count,
                full_gc_pauses: $full_gc_count,
                gc_pause_total_ms: $gc_pause_ms,
                failed_rounds: $rounds_failed,
                logback_update_max_s: $update_max
            }
        }' > "$dir/summary.json"

    table_header > "$dir/summary.md"
    table_row "$dir/summary.json" >> "$dir/summary.md"
}

# Sizes in the table are in MiB.
table_header() {
    echo '| Framework | Profile | Result | Main execution time, s: median (min-max) | Startup time logged, s | Peak RSS | NMT committed at exit | Live heap after GC, max / heap committed, peak | Metaspace used / committed | Code cache used / committed | Threads | CPU time, s | GC pauses (full), total time | logback.xml update, max s |'
    echo '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|'
}

table_row() {
    jq -r '
        def mib: if . == null then "n/a" else (. / 1024 | . * 10 | round / 10 | tostring) end;
        def secs: if . == null then "n/a" else (. * 100 | round / 100 | tostring) end;
        "| \(.framework) | \(.profile) | \(if .passed then "pass" else "FAIL" end)"
        + " | \(.cold_start.exec_time_s.median | secs) (\(.cold_start.exec_time_s.min | secs)-\(.cold_start.exec_time_s.max | secs))"
        + " | \(.cold_start.framework_startup_s.median | secs)"
        + " | \(.soak_kb.rss_hwm | mib)"
        + " | \(.soak_kb.nmt_total_committed_at_exit | mib)"
        + " | \(.soak_kb.heap.live_after_gc_peak | mib) / \(.soak_kb.heap.committed_peak | mib)"
        + " | \(.soak_kb.metaspace.used_peak | mib) / \(.soak_kb.metaspace.committed_peak | mib)"
        + " | \(.soak_kb.code_cache.used_peak | mib) / \(.soak_kb.code_cache.committed_peak | mib)"
        + " | \(.soak_kb.threads.count_peak // "n/a")"
        + " | \(.soak.cpu_time_s | secs)"
        + " | \(.soak.gc_pauses // "n/a") (\(.soak.full_gc_pauses // "n/a")), \(.soak.gc_pause_total_ms // "n/a") ms"
        + " | \(.soak.logback_update_max_s // "n/a") |"
    ' < "$1"
}

for dir in "$@"; do
    summarize "${dir%/}"
done

if [ $# -gt 1 ]; then
    table_header
    for dir in "$@"; do
        table_row "${dir%/}/summary.json"
    done
else
    cat "${1%/}/summary.md"
fi

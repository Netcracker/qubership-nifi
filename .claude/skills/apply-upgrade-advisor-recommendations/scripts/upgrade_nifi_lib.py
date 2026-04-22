#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import sys as _sys
if hasattr(_sys.stdout, "reconfigure"):
    _sys.stdout.reconfigure(encoding="utf-8")
if hasattr(_sys.stderr, "reconfigure"):
    _sys.stderr.reconfigure(encoding="utf-8")
"""
upgrade_nifi_lib.py  —  CLI entry point for the apply-upgrade-advisor-recommendations
                         AI Agent skill.

Handles all automatable NiFi 1.x -> 2.x upgrade fixes derived from an
upgradeAdvisor CSV report.

Usage (analysis / dry-run):
    python upgrade_nifi_lib.py --analyze <csv_path> <exports_dir>
    python upgrade_nifi_lib.py --collect-vars <exports_dir>

Public API for the generated run-script:
    from fixes    import apply_csv_transforms
    from contexts import apply_variable_contexts, apply_hardcoded_values
"""

import json
import sys
from pathlib import Path

from utils    import parse_csv
from fixes    import _classify_row
from analysis import collect_variable_analysis


# ---------------------------------------------------------------------------
# CSV summary printer (CLI --analyze mode)
# ---------------------------------------------------------------------------

def detect_exports_dir(csv_path: str, search_root: str = ".") -> None:
    """Derive exports_dir from the CSV's Flow name values by locating a matching file."""
    rows = parse_csv(csv_path)
    if not rows:
        print("ERROR: CSV is empty", file=sys.stderr)
        sys.exit(1)
    flow_name = rows[0]["Flow name"].strip().replace("\\", "/")
    root = Path(search_root).resolve()
    for candidate in root.rglob("*.json"):
        rel = candidate.as_posix()[len(root.as_posix()) + 1:]
        if rel.endswith(flow_name):
            exports_dir = rel[: -len(flow_name)].rstrip("/") or "."
            print(exports_dir)
            return
    print("ERROR: could not locate flow file matching CSV Flow name", file=sys.stderr)
    sys.exit(1)


def analyze(csv_path: str, exports_dir: str) -> None:
    """Print CSV row summary (AUTO / AI Agent / CONTEXT PLAN / MANUAL tags).

    Variable analysis is handled separately via --collect-vars; the AI agent
    then proposes parameter contexts interactively with the user.
    """
    print("=" * 70)
    print("upgrade_nifi_lib  —  CSV Row Summary")
    print("=" * 70)

    if csv_path and Path(csv_path).exists() and Path(csv_path).stat().st_size > 0:
        rows = parse_csv(csv_path)
        print("\nCSV Row Summary:")
        print("-" * 60)
        for row in rows:
            handler = _classify_row(row)
            tag = {
                "fix_script_engine": "[AI Agent]",
                "fix_variables": "[CONTEXT PLAN]",
                "manual": "[MANUAL]",
            }.get(handler, "[AUTO]")
            proc_cell = row.get("Processor") or row.get("Process Group") or "?"
            print(
                f"  {tag:15s} {row['Flow name']} — {proc_cell[:60]}"
            )
    else:
        print("\n  (No CSV provided or CSV is empty — skipping row summary)")

    print("\n")
    print("Run --collect-vars to get variable data for AI-assisted parameter context planning.")


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="NiFi 1.x->2.x upgrade helper library")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--analyze", action="store_true",
                       help="Print CSV row summary (AUTO/AI Agent/CONTEXT PLAN/MANUAL tags)")
    group.add_argument("--collect-vars", action="store_true",
                       help="Collect variable analysis from flow JSON files; output as JSON to stdout")
    group.add_argument("--detect-exports-dir", action="store_true",
                       help="Derive exports_dir from the CSV's Flow name values; prints the result")
    group.add_argument("--apply", action="store_true",
                       help="Not used directly; use apply_csv_transforms() from generated run script")
    parser.add_argument("csv_path", nargs="?", default=None,
                        help="Path to upgradeAdvisorReport.csv (required for --analyze; use /dev/null to skip)")
    parser.add_argument("exports_dir", nargs="?", default=None,
                        help="Root directory containing NiFi JSON flow exports (not needed for --detect-exports-dir)")
    args = parser.parse_args()

    if args.analyze:
        analyze(args.csv_path or "/dev/null", args.exports_dir)
    elif args.collect_vars:
        result = collect_variable_analysis(args.exports_dir)
        print(json.dumps(result, indent=2, ensure_ascii=False))
    elif args.detect_exports_dir:
        detect_exports_dir(args.csv_path, args.exports_dir or ".")
    else:
        print("Use apply_csv_transforms() and apply_variable_contexts() from the generated run script.")
        sys.exit(1)

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
upgrade_nifi_lib.py   --  CLI entry point for the adapt-nifi-flows-to-2-x
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

import sys as _sys

if hasattr(_sys.stdout, "reconfigure"):
    _sys.stdout.reconfigure(encoding="utf-8")
if hasattr(_sys.stderr, "reconfigure"):
    _sys.stderr.reconfigure(encoding="utf-8")

import json  # noqa: E402
import re  # noqa: E402
import sys  # noqa: E402
from pathlib import Path  # noqa: E402

from utils import parse_csv, load_json, find_component  # noqa: E402
from fixes import _classify_row  # noqa: E402
from analysis import collect_variable_analysis  # noqa: E402


# ---------------------------------------------------------------------------
# Issue-flag helpers (shared by --analyze output and tests)
# ---------------------------------------------------------------------------

_ISSUE_SUBSTRINGS = {
    "proxy":         "Proxy properties in InvokeHTTP",
    "aws":           "\"Access Key ID\" and \"Secret Access Key\"",
    "prometheus":    "PrometheusRecordSink",
    "script_engine": "Script Engine =",
}


def _build_issue_flags(rows: list) -> dict:
    issues_text = [r.get("Issue", "") for r in rows]
    flags: dict = {k: any(sub in t for t in issues_text) for k, sub in _ISSUE_SUBSTRINGS.items()}
    flags["variables"] = any(_classify_row(r) == "fix_variables" for r in rows)
    flags["all_issues"] = sorted(set(t for t in issues_text if t))
    return flags


# ---------------------------------------------------------------------------
# Property lists for --show-processor-props
# ---------------------------------------------------------------------------

_HANDLER_PROPS: dict[str, list[str]] = {
    "fix_invokehttp_proxy": [
        "Proxy Host", "Proxy Port", "Proxy Type",
        "invokehttp-proxy-user", "invokehttp-proxy-password",
    ],
    "fix_s3_credentials": ["Access Key", "Secret Key"],
}


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
        rel = candidate.as_posix()[len(root.as_posix()) + 1 :]
        if rel.endswith(flow_name):
            exports_dir = rel[: -len(flow_name)].rstrip("/") or "."
            print(exports_dir)
            return
    print("ERROR: could not locate flow file matching CSV Flow name", file=sys.stderr)
    sys.exit(1)


def detect_standalone_cs(csv_path: str, exports_dir: str) -> None:
    """Detect standalone CS files with PrometheusRecordSink or AzureStorageCredentialsControllerService
    types that require a rename because NiFi API cannot change a controller service's type in-place.
    Prints a JSON array of {file, current_name, current_type, suggested_name, suggested_file}.
    """
    _STANDALONE_HANDLERS = {"fix_prometheus", "fix_azure_credentials"}
    rows = parse_csv(csv_path)
    exports = Path(exports_dir)
    seen: set[str] = set()
    results = []

    for row in rows:
        if _classify_row(row) not in _STANDALONE_HANDLERS:
            continue
        rel_path = row["Flow name"].strip().replace("\\", "/")
        if rel_path in seen:
            continue
        seen.add(rel_path)
        abs_path = exports / rel_path
        if not abs_path.exists():
            continue
        try:
            data = load_json(abs_path)
        except Exception:
            continue
        if "component" not in data or "flowContents" in data:
            continue  # not a standalone CS file
        svc = data["component"]
        svc_type = svc.get("type", "")
        current_name = svc.get("name", "")

        if svc_type.endswith("PrometheusRecordSink"):
            if "prometheus" in current_name.lower():
                suggested_name = re.sub(
                    r"(?i)prometheus", "QubershipPrometheus", current_name, count=1
                )
            else:
                suggested_name = current_name + " (Qubership)"
        elif svc_type.endswith("AzureStorageCredentialsControllerService"):
            if not current_name.lower().endswith(" v12"):
                suggested_name = current_name + " v12"
            else:
                suggested_name = current_name
        else:
            continue

        stem = re.sub(r"[^a-zA-Z0-9]+", "_", suggested_name).strip("_")
        parent = Path(rel_path).parent.as_posix()
        suggested_file = (f"{parent}/{stem}.json" if parent != "." else f"{stem}.json")

        results.append(
            {
                "file": rel_path,
                "current_name": current_name,
                "current_type": svc_type,
                "suggested_name": suggested_name,
                "suggested_file": suggested_file,
            }
        )

    print(json.dumps(results, indent=2, ensure_ascii=False))


def analyze(csv_path: str, exports_dir: str) -> None:
    """Print CSV row summary (AUTO / AI Agent / CONTEXT PLAN / MANUAL tags) and issue flags.

    Variable analysis is handled separately via --collect-vars; the AI agent
    then proposes parameter contexts interactively with the user.
    """
    print("=" * 70)
    print("upgrade_nifi_lib   --  CSV Row Summary")
    print("=" * 70)

    rows: list = []
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
            print(f"  {tag:15s} {row['Flow name']}  -- {proc_cell[:60]}")
    else:
        print("\n  (No CSV provided or CSV is empty  -- skipping row summary)")

    print("\n")
    print(
        "Run --collect-vars to get variable data for AI-assisted parameter context planning."
    )
    print("\n=== Issue Flags ===")
    print(json.dumps(_build_issue_flags(rows), indent=2, ensure_ascii=False))


# ---------------------------------------------------------------------------
# Processor property extractor (CLI --show-processor-props mode)
# ---------------------------------------------------------------------------


def show_processor_props(csv_path: str, exports_dir: str, handler: str) -> None:
    """Print JSON list of processor property values for all rows matching handler.

    Only properties present in the flow JSON are included (absent keys are omitted).
    """
    prop_keys = _HANDLER_PROPS.get(handler)
    if prop_keys is None:
        print(
            f"ERROR: unknown handler '{handler}'. Known: {list(_HANDLER_PROPS)}",
            file=sys.stderr,
        )
        sys.exit(1)

    rows = parse_csv(csv_path)
    exports = Path(exports_dir)
    file_cache: dict[str, dict] = {}
    results = []

    for row in rows:
        if _classify_row(row) != handler:
            continue
        proc_uuid = row.get("_proc_uuid")
        if not proc_uuid:
            continue
        rel_path = row["Flow name"].strip().replace("\\", "/")
        cache_key = str(exports / rel_path)
        if cache_key not in file_cache:
            try:
                data = load_json(exports / rel_path)
                file_cache[cache_key] = data.get("flowContents", data)
            except Exception:
                continue
        comp, _ = find_component(file_cache[cache_key], proc_uuid)
        if comp is None:
            continue
        props = comp.get("properties", {})
        results.append({
            "uuid": proc_uuid,
            "name": comp.get("name", comp.get("type", "?")),
            "file": rel_path,
            "properties": {k: props[k] for k in prop_keys if k in props},
        })

    print(json.dumps(results, indent=2, ensure_ascii=False))


# ---------------------------------------------------------------------------
# Parameter context structure dumper (CLI --show-contexts mode)
# ---------------------------------------------------------------------------


def show_contexts(exports_dir: str) -> None:
    """Print JSON map of per-file parameter context definitions and PG assignments.

    Keyed by file path relative to exports_dir. Standalone CS files (no flowContents)
    are excluded. Inherited context references are resolved to context names.
    """
    exports = Path(exports_dir)
    output: dict = {}

    for json_file in sorted(exports.rglob("*.json")):
        try:
            data = load_json(json_file)
        except Exception:
            continue
        if "flowContents" not in data:
            continue
        fc = data["flowContents"]
        rel = json_file.relative_to(exports).as_posix()

        ctxs_raw = data.get("parameterContexts", {})
        uuid_to_name: dict[str, str] = {}
        for ctx_id, ctx in ctxs_raw.items():
            if isinstance(ctx, dict):
                uuid_to_name[ctx_id] = ctx.get("name", ctx_id)

        contexts: dict = {}
        for ctx_id, ctx in ctxs_raw.items():
            if not isinstance(ctx, dict):
                continue
            name = ctx.get("name", ctx_id)
            params_obj = ctx.get("parameters", {})
            if isinstance(params_obj, list):
                direct_params = [
                    p["name"] for p in params_obj if isinstance(p, dict) and "name" in p
                ]
            else:
                direct_params = list(params_obj.keys())
            inherited_raw = ctx.get("inheritedParameterContexts", [])
            inherited = []
            for inheritedPC in inherited_raw:
                if isinstance(inheritedPC, str):
                    inherited.append(uuid_to_name.get(inheritedPC, inheritedPC))
                elif isinstance(inheritedPC, dict):
                    inh_id = inheritedPC.get("identifier") or inheritedPC.get("id", "")
                    inherited.append(uuid_to_name.get(inh_id, inh_id))
            contexts[name] = {"direct_params": direct_params, "inherited": inherited}

        output[rel] = {
            "pg_name": fc.get("name"),
            "pg_context": fc.get("parameterContextName"),
            "contexts": contexts,
        }

    print(json.dumps(output, indent=2, ensure_ascii=False))


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="NiFi 1.x->2.x upgrade helper library")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "--analyze",
        action="store_true",
        help="Print CSV row summary (AUTO/AI Agent/CONTEXT PLAN/MANUAL tags)",
    )
    group.add_argument(
        "--collect-vars",
        action="store_true",
        help="Collect variable analysis from flow JSON files; output as JSON to stdout",
    )
    group.add_argument(
        "--detect-exports-dir",
        action="store_true",
        help="Derive exports_dir from the CSV's Flow name values; prints the result",
    )
    group.add_argument(
        "--detect-standalone-cs",
        action="store_true",
        help=(
            "Detect standalone CS files (PrometheusRecordSink, AzureStorageCredentials) "
            "that require rename; prints JSON array of suggestions"
        ),
    )
    group.add_argument(
        "--show-processor-props",
        action="store_true",
        help=(
            "Print JSON list of processor property values for rows matching --handler. "
            "Requires --handler fix_invokehttp_proxy or --handler fix_s3_credentials"
        ),
    )
    group.add_argument(
        "--show-contexts",
        action="store_true",
        help="Print JSON map of parameter context definitions and PG assignments per flow file",
    )
    group.add_argument(
        "--apply",
        action="store_true",
        help="Not used directly; use apply_csv_transforms() from generated run script",
    )
    parser.add_argument(
        "csv_path",
        nargs="?",
        default=None,
        help="Path to upgradeAdvisorReport.csv (optional for --analyze; omit or pass a nonexistent path to skip the row summary)",
    )
    parser.add_argument(
        "exports_dir",
        nargs="?",
        default=None,
        help="Root directory containing NiFi JSON flow exports (not needed for --detect-exports-dir)",
    )
    parser.add_argument(
        "--handler",
        default=None,
        help="Handler name for --show-processor-props (e.g. fix_invokehttp_proxy, fix_s3_credentials)",
    )
    args = parser.parse_args()

    if args.analyze:
        analyze(args.csv_path, args.exports_dir)
    elif args.detect_standalone_cs:
        detect_standalone_cs(args.csv_path, args.exports_dir)
    elif args.collect_vars:
        result = collect_variable_analysis(args.exports_dir)
        print(json.dumps(result, indent=2, ensure_ascii=False))
    elif args.detect_exports_dir:
        detect_exports_dir(args.csv_path, args.exports_dir or ".")
    elif args.show_processor_props:
        if not args.handler:
            print("ERROR: --show-processor-props requires --handler", file=sys.stderr)
            sys.exit(1)
        show_processor_props(args.csv_path, args.exports_dir, args.handler)
    elif args.show_contexts:
        show_contexts(args.exports_dir)
    else:
        print(
            "Use apply_csv_transforms() and apply_variable_contexts() from the generated run script."
        )
        sys.exit(1)

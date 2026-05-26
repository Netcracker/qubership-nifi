"""
contexts.py   --  Parameter context promotion and hard-coded value substitution.
"""

import re
from collections import defaultdict
from pathlib import Path

from utils import load_json, save_json, find_pg, replace_var_refs_in_pg


# ---------------------------------------------------------------------------
# Variable context application
# ---------------------------------------------------------------------------


def apply_variable_contexts(
    exports_dir: str,
    parameter_context_plan: list[dict],
) -> None:
    """
    Apply a PARAMETER_CONTEXT_PLAN to flow JSON files.

    Each plan entry:
        {
            "name": "context-name",
            "parent": "parent-context-name" | None,
            "parameters": { "var": "value", ... },
            "apply_to": [ (rel_path, pg_uuid), ... ]
        }
    """
    exports = Path(exports_dir)

    applied = []

    # Build cumulative parameter name sets per context (including inherited params).
    # Parents must come before children in the plan (guaranteed by construction).
    context_all_params: dict = {}
    for entry in parameter_context_plan:
        params = set(entry.get("parameters", {}).keys())
        parent = entry.get("parent")
        if parent:
            params |= context_all_params.get(parent, set())
        context_all_params[entry["name"]] = params

    # We may need to write multiple flows; load them once
    file_cache: dict = {}

    def get_root(rel_path):
        if rel_path not in file_cache:
            p = exports / rel_path
            if p.exists():
                file_cache[rel_path] = load_json(p)
        return file_cache.get(rel_path)

    for entry in parameter_context_plan:
        ctx_name = entry["name"]
        parent_name = entry.get("parent")
        parameters = entry.get("parameters", {})
        apply_to = entry.get("apply_to", [])

        # Build context object
        ctx_obj = {
            "name": ctx_name,
            "componentType": "PARAMETER_CONTEXT",
            "parameters": [
                {
                    "name": k,
                    "value": v,
                    "sensitive": False,
                    "description": "",
                }
                for k, v in parameters.items()
            ],
            "inheritedParameterContexts": ([parent_name] if parent_name else []),
        }

        for rel_path, pg_uuid in apply_to:
            root = get_root(rel_path)
            if root is None:
                print(f"[WARN] File not found: {rel_path}")
                continue

            # Add context to root-level parameterContexts
            root.setdefault("parameterContexts", {})[ctx_name] = ctx_obj

            # Find the process group and set its parameterContextName
            flow_contents = root.get("flowContents", root)
            pg = find_pg(flow_contents, pg_uuid)
            if pg is None:
                print(f"[WARN] PG {pg_uuid} not found in {rel_path}")
                continue

            pg["parameterContextName"] = ctx_name

            # Replace ${varName} -> #{varName} only for vars moved to param context
            all_param_names = context_all_params.get(ctx_name, set(parameters.keys()))
            n = replace_var_refs_in_pg(pg, all_param_names)

            # Clear variables
            if pg.get("variables"):
                pg["variables"] = {}

            applied.append(
                f"[FIXED] {rel_path} PG={pg.get('name', '?')} ({pg_uuid})  -- "
                f"context '{ctx_name}' applied; {n} property reference(s) updated"
            )

    # Write modified files
    for rel_path, data in file_cache.items():
        save_json(exports / rel_path, data)

    print("\n=== Variable Contexts Applied ===")
    for m in applied:
        print(" ", m)
    print(f"\n  Total: {len(applied)} process group(s) updated")


# ---------------------------------------------------------------------------
# Hard-coded value substitution
# ---------------------------------------------------------------------------


def _hardcode_var_in_pg(pg: dict, var_name: str, literal_value: str) -> int:
    """Replace ${var_name} with literal_value in all processor/service property values
    within pg and its descendant process groups.  Remove var_name from pg["variables"]
    if present.  Returns the count of property values changed.

    Two forms are handled:
      ${varName}              to  literal_value
      ${varName:fn1():fn2()}  to  ${literal("literal_value"):fn1():fn2()}

    The second form preserves the EL function chain using NiFi's literal() function.
    """
    pattern = re.compile(r"\$\{" + re.escape(var_name) + r"(:[^}]*)?\}")
    count = 0

    def _make_replacement(m):
        func_chain = m.group(1)
        if func_chain:
            escaped = literal_value.replace("\\", "\\\\").replace('"', '\\"')
            return '${literal("' + escaped + '")' + func_chain + "}"
        return literal_value

    def replace_in_node(node):
        nonlocal count
        for k, v in list(node.get("properties", {}).items()):
            if isinstance(v, str) and pattern.search(v):
                new_v = pattern.sub(_make_replacement, v)
                if new_v != v:
                    node["properties"][k] = new_v
                    count += 1

    def process_pg(current_pg):
        for proc in current_pg.get("processors", []):
            replace_in_node(proc)
        for svc in current_pg.get("controllerServices", []):
            replace_in_node(svc)
        for child in current_pg.get("processGroups", []):
            if var_name not in child.get("variables", {}):
                process_pg(child)

    process_pg(pg)

    if var_name in pg.get("variables", {}):
        del pg["variables"][var_name]

    return count


def apply_hardcoded_values(
    exports_dir: str,
    hardcode_plan: list[dict],
) -> None:
    """
    Hard-code variable references directly into processor properties.

    Use for variables that cannot be shared (values_differ: true) and that the
    user has chosen not to parameterise (e.g. low reference count per PG).

    Each plan entry:
        {
            "variable": "var_name",
            "value":    "literal_value",
            "rel_path": "relative/path/to/flow.json",
            "pg_uuid":  "process-group-uuid",
        }

    Replaces ${variable} -> literal_value in all processor/service properties
    within the PG subtree, then removes the variable from the PG's variables dict.
    Each flow file is loaded once regardless of how many entries reference it.
    """
    exports = Path(exports_dir)

    # Group by file so each JSON is loaded and saved only once
    by_file: dict[str, list[dict]] = defaultdict(list)
    for entry in hardcode_plan:
        by_file[entry["rel_path"]].append(entry)

    applied: list[str] = []

    for rel_path, entries in by_file.items():
        abs_path = exports / rel_path
        if not abs_path.exists():
            print(f"  [WARN] File not found: {rel_path}")
            continue

        data = load_json(abs_path)
        flow_contents = data.get("flowContents", data)

        for entry in entries:
            pg_uuid = entry["pg_uuid"]
            var_name = entry["variable"]
            literal = entry["value"]

            pg = find_pg(flow_contents, pg_uuid)
            if pg is None:
                print(f"  [WARN] PG {pg_uuid} not found in {rel_path}")
                continue

            n = _hardcode_var_in_pg(pg, var_name, literal)
            applied.append(
                f"[FIXED] {rel_path} PG={pg.get('name', '?')} ({pg_uuid})  -- "
                f"hard-coded ${{{var_name}}} -> '{literal}' in {n} property value(s)"
            )

        save_json(abs_path, data)

    print("\n=== Hard-coded Variable Values ===")
    for m in applied:
        print(" ", m)
    print(f"\n  Total: {len(applied)} substitution(s) applied")


# ---------------------------------------------------------------------------
# Parent PG parameter context assignment (cross-file CS support)
# ---------------------------------------------------------------------------


def _collect_all_context_definitions(exports: Path) -> dict[str, dict]:
    """Walk all *.json files under exports and return {ctx_name: ctx_object}.
    First occurrence of each name wins.
    """
    result: dict[str, dict] = {}
    for json_file in sorted(exports.rglob("*.json")):
        try:
            data = load_json(json_file)
        except Exception:
            continue
        for name, obj in data.get("parameterContexts", {}).items():
            result.setdefault(name, obj)
    return result


def apply_parent_contexts(
    exports_dir: str,
    parent_context_plan: list[dict],
) -> None:
    """
    Assign or extend parameter contexts on parent PG files that received
    cross-file controller services so their #{param} references resolve.

    Each plan entry:
        {
            "action":          "assign_direct" | "create_wrapper" | "add_inheritance" | "none",
            "parent_pg_file":  "relative/path/to/parent.json",   # relative to exports_dir
            "needed_contexts": ["ctx-name-1", ...],
            # create_wrapper only:
            "wrapper_name":    "new-wrapper-context-name",
            # add_inheritance only:
            "existing_context_name": "name-of-the-pgs-current-context",
        }
    """
    exports = Path(exports_dir)

    print("\n=== Parent PG Parameter Contexts Applied ===")

    if not parent_context_plan:
        print("  (none)")
        print("\n  Total: 0 parent PG file(s) updated")
        return

    all_ctx_defs = _collect_all_context_definitions(exports)

    applied: list[str] = []
    warnings: list[str] = []

    for entry in parent_context_plan:
        action = entry.get("action", "none")
        if action == "none":
            continue

        parent_pg_file = entry["parent_pg_file"]
        needed_contexts = entry.get("needed_contexts", [])
        abs_path = exports / parent_pg_file

        if not abs_path.exists():
            warnings.append(f"[WARN] File not found: {parent_pg_file}")
            continue

        parent_data = load_json(abs_path)
        parent_flow = parent_data.get("flowContents", parent_data)

        if action == "assign_direct":
            ctx_name = needed_contexts[0]
            ctx_obj = all_ctx_defs.get(ctx_name)
            if ctx_obj is None:
                warnings.append(
                    f"[WARN] context '{ctx_name}' not found in any file under {exports_dir}"
                )
                continue
            parent_data.setdefault("parameterContexts", {})[ctx_name] = ctx_obj
            parent_flow["parameterContextName"] = ctx_name
            applied.append(
                f"[FIXED] {parent_pg_file}  -- assigned parameter context '{ctx_name}'"
            )

        elif action == "create_wrapper":
            wrapper_name = entry["wrapper_name"]
            if wrapper_name in parent_data.get("parameterContexts", {}):
                warnings.append(
                    f"[WARN] wrapper context '{wrapper_name}' already exists in "
                    f"{parent_pg_file} - skipped to avoid overwrite"
                )
                continue
            for ctx_name in needed_contexts:
                ctx_obj = all_ctx_defs.get(ctx_name)
                if ctx_obj is None:
                    warnings.append(
                        f"[WARN] context '{ctx_name}' not found in any file under {exports_dir}"
                    )
                else:
                    parent_data.setdefault("parameterContexts", {})[ctx_name] = ctx_obj
            wrapper_ctx = {
                "componentType": "PARAMETER_CONTEXT",
                "name": wrapper_name,
                "parameters": [],
                "inheritedParameterContexts": list(needed_contexts),
            }
            parent_data.setdefault("parameterContexts", {})[wrapper_name] = wrapper_ctx
            parent_flow["parameterContextName"] = wrapper_name
            applied.append(
                f"[FIXED] {parent_pg_file}  -- created wrapper context '{wrapper_name}' "
                f"inheriting {', '.join(needed_contexts)}"
            )

        elif action == "add_inheritance":
            existing_ctx_name = entry["existing_context_name"]
            existing_ctx_obj = (
                parent_data.get("parameterContexts", {}).get(existing_ctx_name)
                or all_ctx_defs.get(existing_ctx_name)
            )
            if existing_ctx_obj is None:
                warnings.append(
                    f"[WARN] existing context '{existing_ctx_name}' not found in any "
                    f"file under {exports_dir} - cannot extend inheritance in {parent_pg_file}"
                )
                continue
            current_inherited = existing_ctx_obj.get("inheritedParameterContexts", [])
            missing = [
                c for c in needed_contexts
                if c != existing_ctx_name and c not in current_inherited
            ]
            for ctx_name in missing:
                ctx_obj = all_ctx_defs.get(ctx_name)
                if ctx_obj is None:
                    warnings.append(
                        f"[WARN] context '{ctx_name}' not found in any file under {exports_dir}"
                    )
                else:
                    parent_data.setdefault("parameterContexts", {})[ctx_name] = ctx_obj
            # Ensure existing context is in the parent file, then extend it
            parent_data.setdefault("parameterContexts", {})[existing_ctx_name] = existing_ctx_obj
            parent_data["parameterContexts"][existing_ctx_name][
                "inheritedParameterContexts"
            ] = current_inherited + missing
            applied.append(
                f"[FIXED] {parent_pg_file}  -- extended context '{existing_ctx_name}' "
                f"to inherit {', '.join(missing) if missing else '(nothing new)'}"
            )

        save_json(abs_path, parent_data)

    for m in applied:
        print(" ", m)
    for m in warnings:
        print(" ", m)
    print(f"\n  Total: {len(applied)} parent PG file(s) updated")

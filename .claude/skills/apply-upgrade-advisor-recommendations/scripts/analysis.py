"""
analysis.py  —  Variable collection and analysis helpers used by the AI agent
                 during parameter context planning.
"""

from collections import defaultdict
from pathlib import Path

from utils import load_json


# ---------------------------------------------------------------------------
# Variable collection (structured output for AI analysis)
# ---------------------------------------------------------------------------

def _count_refs_in_subtree(pg: dict, var_name: str) -> int:
    """Count occurrences of ${var_name} in all processor/service property values
    within pg and all its descendant process groups."""
    needle = "${" + var_name + "}"
    count = 0

    def count_in_node(node):
        nonlocal count
        for v in node.get("properties", {}).values():
            if isinstance(v, str):
                count += v.count(needle)

    def process_pg(current_pg):
        for proc in current_pg.get("processors", []):
            count_in_node(proc)
        for svc in current_pg.get("controllerServices", []):
            count_in_node(svc)
        for child in current_pg.get("processGroups", []):
            process_pg(child)

    process_pg(pg)
    return count


def collect_variable_analysis(exports_dir: str) -> dict:
    """
    Scan all *.json files under exports_dir and return a structured dict
    describing every NiFi variable found.

    Return format::

        {
            "<var_name>": {
                "occurrences": [
                    {
                        "file":            "<rel_path>",
                        "pg_id":           "<uuid>",
                        "pg_name":         "<name>",
                        "value":           "<value>",
                        "reference_count": <int>,   # times ${var_name} appears in PG subtree
                    },
                    ...
                ],
                "values_differ": <bool>,  # True when the value is not identical across all PGs
            },
            ...
        }

    ``reference_count`` is counted across the entire subtree rooted at the
    defining process group (children inherit parent variables in NiFi 1.x).
    """
    exports = Path(exports_dir)
    # {var_name: list of occurrence dicts}
    var_data: dict = {}

    for json_file in sorted(exports.rglob("*.json")):
        try:
            data = load_json(json_file)
        except Exception:
            continue
        rel_path = str(json_file.relative_to(exports))
        flow_contents = data.get("flowContents", data)

        def process_pg(pg):
            pg_id = pg.get("identifier", "?")
            pg_name = pg.get("name", "?")
            vars_ = pg.get("variables", {})

            for var_name, value in vars_.items():
                ref_count = _count_refs_in_subtree(pg, var_name)
                if var_name not in var_data:
                    var_data[var_name] = {"occurrences": [], "values_differ": False}
                var_data[var_name]["occurrences"].append({
                    "file": rel_path,
                    "pg_id": pg_id,
                    "pg_name": pg_name,
                    "value": value,
                    "reference_count": ref_count,
                })

            for child in pg.get("processGroups", []):
                process_pg(child)

        process_pg(flow_contents)

    # Compute values_differ after all files are scanned
    for info in var_data.values():
        values = [occ["value"] for occ in info["occurrences"]]
        info["values_differ"] = len(set(values)) > 1

    return var_data


# ---------------------------------------------------------------------------
# Legacy analysis helpers (used by --analyze CSV summary in upgrade_nifi_lib)
# ---------------------------------------------------------------------------

def _collect_variables(exports_dir: str) -> list[tuple]:
    """
    Scan all *.json files under exports_dir and collect
    (rel_path, pg_id, pg_name, variables_dict) for every PG with non-empty variables.
    """
    exports = Path(exports_dir)
    results = []

    for json_file in sorted(exports.rglob("*.json")):
        try:
            data = load_json(json_file)
        except Exception:
            continue
        rel_path = str(json_file.relative_to(exports))
        flow_contents = data.get("flowContents", data)

        def collect(pg):
            vars_ = pg.get("variables", {})
            if vars_:
                results.append((rel_path, pg["identifier"], pg.get("name", "?"), vars_))
            for child in pg.get("processGroups", []):
                collect(child)

        collect(flow_contents)

    return results


def _propose_context_hierarchy(
    pg_variable_list: list[tuple],
) -> tuple[list[dict], str]:
    """
    Given [(rel_path, pg_id, pg_name, {var: val}), ...],
    return (plan_entries, display_text).
    """
    # Build variable inventory: {var_name: {(rel_path, pg_id): value}}
    inventory: dict[str, dict] = defaultdict(dict)
    for rel_path, pg_id, pg_name, vars_ in pg_variable_list:
        for k, v in vars_.items():
            inventory[k][(rel_path, pg_id)] = v

    # Classify
    common_vars: dict[str, str] = {}    # name -> value
    for var_name, occurrences in inventory.items():
        values = list(occurrences.values())
        if len(occurrences) >= 2 and len(set(values)) == 1:
            common_vars[var_name] = values[0]

    # Build plan
    plan = []

    if common_vars:
        # Which PGs does the common context apply to?
        # (all that define at least one common var)
        common_apply_to = set()
        for var_name in common_vars:
            for (rel_path, pg_id) in inventory[var_name]:
                common_apply_to.add((rel_path, pg_id))

        plan.append({
            "name": "shared-params",
            "parent": None,
            "parameters": dict(common_vars),
            "apply_to": sorted(common_apply_to),
        })

    # Per-PG child entries
    pg_lookup = {(r, p): n for r, p, n, _ in pg_variable_list}
    for rel_path, pg_id, pg_name, vars_ in pg_variable_list:
        # Variables not in common (or with different values)
        extra = {
            k: v for k, v in vars_.items()
            if k not in common_vars or common_vars.get(k) != v
        }
        if extra:
            plan.append({
                "name": f"{pg_name}-params",
                "parent": "shared-params" if common_vars else None,
                "parameters": extra,
                "apply_to": [(rel_path, pg_id)],
            })
        # else: PG only has common vars -> will be handled by the DIRECT reference above

    # Build display text
    lines = [
        "\nProposed Parameter Contexts:",
        "-" * 60,
    ]
    for entry in plan:
        parent_str = (
            f"  inherits '{entry['parent']}'" if entry["parent"] else ""
        )
        lines.append(f"  [{entry['name']}]{parent_str}")
        for k, v in entry["parameters"].items():
            lines.append(f"    {k!r} = {v!r}")
        for rel_path, pg_id in entry["apply_to"]:
            pg_name = pg_lookup.get((rel_path, pg_id), pg_id)
            lines.append(f"    -> applies to: {rel_path} / {pg_name}")

    lines.append("-" * 60)

    # Flows that get a DIRECT reference (no child context needed)
    for rel_path, pg_id, pg_name, vars_ in pg_variable_list:
        extra = {
            k: v for k, v in vars_.items()
            if k not in common_vars or common_vars.get(k) != v
        }
        if not extra and common_vars:
            lines.append(
                f"  [DIRECT] {rel_path} / {pg_name} ({pg_id})"
                f" -> will reference 'shared-params' directly (all vars are common)"
            )

    return plan, "\n".join(lines)

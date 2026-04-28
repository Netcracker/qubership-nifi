"""
analysis.py  —  Variable collection and analysis helpers used by the AI agent
                 during parameter context planning.
"""

import re
from pathlib import Path

from utils import load_json


# ---------------------------------------------------------------------------
# Variable collection (structured output for AI analysis)
# ---------------------------------------------------------------------------


def _count_refs_in_pg(pg: dict, var_name: str) -> int:
    """Count occurrences of ${var_name} in the direct processors/services of pg only
    (does not recurse into child process groups).

    Matches both bare variable references (${varName}) and NiFi EL expressions
    that apply functions to the variable (${varName:function():...}).
    """
    # (?=[:}]) — lookahead ensures we stop at the end of the variable name:
    #   ${varName}       matched by (?=})
    #   ${varName:fn()}  matched by (?=:)
    # This prevents ${foo.bar} from being counted as a reference to variable foo.
    pattern = re.compile(r"\$\{" + re.escape(var_name) + r"(?=[:}])")
    count = 0
    for proc in pg.get("processors", []):
        for v in proc.get("properties", {}).values():
            if isinstance(v, str):
                count += len(pattern.findall(v))
    for svc in pg.get("controllerServices", []):
        for v in svc.get("properties", {}).values():
            if isinstance(v, str):
                count += len(pattern.findall(v))
    return count


def _find_child_pg_refs(
    pg: dict, var_name: str, rel_path: str, inherited_value: str
) -> list[dict]:
    """Walk child PGs recursively and return an occurrence entry for each one that
    directly references ${var_name} in its own processors/services."""
    results = []

    def walk(current_pg):
        for child in current_pg.get("processGroups", []):
            ref_count = _count_refs_in_pg(child, var_name)
            if ref_count > 0:
                results.append(
                    {
                        "file": rel_path,
                        "pg_id": child.get("identifier", "?"),
                        "pg_name": child.get("name", "?"),
                        "value": inherited_value,
                        "reference_count": ref_count,
                    }
                )
            walk(child)

    walk(pg)
    return results


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
                ref_count = _count_refs_in_pg(pg, var_name)
                # Collect descendant refs first so we can roll their counts
                # into the defining PG's reference_count (subtree-wide total).
                child_refs = _find_child_pg_refs(pg, var_name, rel_path, value)
                subtree_count = ref_count + sum(
                    r["reference_count"] for r in child_refs
                )
                if var_name not in var_data:
                    var_data[var_name] = {"occurrences": [], "values_differ": False}
                var_data[var_name]["occurrences"].append(
                    {
                        "file": rel_path,
                        "pg_id": pg_id,
                        "pg_name": pg_name,
                        "value": value,
                        "reference_count": subtree_count,
                    }
                )
                var_data[var_name]["occurrences"].extend(child_refs)

            for child in pg.get("processGroups", []):
                process_pg(child)

        process_pg(flow_contents)

    # Compute values_differ after all files are scanned
    for info in var_data.values():
        values = [occ["value"] for occ in info["occurrences"]]
        info["values_differ"] = len(set(values)) > 1

    return var_data

"""
utils.py  —  Shared I/O helpers, JSON-tree walkers, CSV parsing, and
              controller-service skeleton builder for upgrade_nifi_lib.
"""

import csv
import json
import re
import uuid
from pathlib import Path


# ---------------------------------------------------------------------------
# I/O helpers
# ---------------------------------------------------------------------------


def load_json(path: Path) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save_json(path: Path, data: dict) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=4, ensure_ascii=False)
    print(f"  [wrote] {path}")


def new_uuid() -> str:
    return str(uuid.uuid4())


# ---------------------------------------------------------------------------
# JSON-tree walkers
# ---------------------------------------------------------------------------


def find_component(node: dict, target_id: str):
    """Return (component_dict, containing_pg_dict) or (None, None)."""
    for key in ("processors", "controllerServices"):
        for c in node.get(key, []):
            if c.get("identifier") == target_id:
                return c, node
    for pg in node.get("processGroups", []):
        result = find_component(pg, target_id)
        if result[0] is not None:
            return result
    return None, None


def find_pg(node: dict, target_id: str):
    """Return the process-group dict matching target_id, or None."""
    if node.get("identifier") == target_id:
        return node
    for pg in node.get("processGroups", []):
        found = find_pg(pg, target_id)
        if found:
            return found
    return None


def walk_pgs(node: dict, fn):
    """Call fn(pg) for every process group in the tree (depth-first)."""
    fn(node)
    for pg in node.get("processGroups", []):
        walk_pgs(pg, fn)


def find_services_by_type_suffix(node: dict, suffix: str) -> list[tuple[dict, dict]]:
    """Return [(svc, containing_pg)] for every controllerService whose type ends with suffix."""
    results = []
    for svc in node.get("controllerServices", []):
        if svc.get("type", "").endswith(suffix):
            results.append((svc, node))
    for pg in node.get("processGroups", []):
        results.extend(find_services_by_type_suffix(pg, suffix))
    return results


def replace_var_refs_in_pg(pg: dict, parameter_names: set) -> int:
    """Replace variable references with parameter-context syntax for names in parameter_names.

    Three forms are rewritten:
      ${varName}              to  #{varName}
      ${varName:fn():...}     to  ${#{varName}:fn():...}
      ${fn(${varName}):...}   to  ${fn(#{varName}):...}   (nested as fn argument)

    The second form follows the NiFi 2.x user guide: "When referencing a Parameter
    from within Expression Language, the Parameter reference is evaluated first."
    FlowFile attribute expressions like ${filename} or ${uuid()} are left untouched.
    """
    if not parameter_names:
        return 0
    count = 0
    names_alt = "|".join(re.escape(pn) for pn in sorted(parameter_names))
    pattern = re.compile(r"\$\{(" + names_alt + r")(:[^}]*)?\}")

    def _rewrite(m):
        var_part = m.group(1)
        func_part = m.group(2)  # None or ":fn1():fn2()..."
        if func_part:
            return "${" + "#{" + var_part + "}" + func_part + "}"
        return f"#{{{var_part}}}"

    def replace_in_node(node):
        nonlocal count
        props = node.get("properties", {})
        for k, v in list(props.items()):
            if not isinstance(v, str):
                continue
            orig = v
            # Pre-pass: replace bare ${paramName} to #{paramName} for each known
            # parameter. Handles params nested inside EL function arguments —
            # e.g. equalsIgnoreCase(${paramName}) — where the outer ${...} regex
            # match would otherwise swallow the inner reference.
            for pn in parameter_names:
                v = v.replace(f"${{{pn}}}", f"#{{{pn}}}")
            # Main pass: convert ${paramName:fn_chain} to ${#{paramName}:fn_chain}
            if pattern.search(v):
                v = pattern.sub(_rewrite, v)
            if v != orig:
                props[k] = v
                count += 1

    for proc in pg.get("processors", []):
        replace_in_node(proc)
    for svc in pg.get("controllerServices", []):
        replace_in_node(svc)
    return count


# ---------------------------------------------------------------------------
# CSV parsing
# ---------------------------------------------------------------------------

UUID_RE = re.compile(r"\(([0-9a-f\-]+)\)\s*$", re.IGNORECASE)


def _extract_uuid(cell: str) -> str | None:
    m = UUID_RE.search(cell.strip())
    return m.group(1) if m else None


def parse_csv(csv_path: str) -> list[dict]:
    rows = []
    with open(csv_path, encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            row["_proc_uuid"] = _extract_uuid(row.get("Processor", ""))
            row["_pg_uuid"] = _extract_uuid(row.get("Process Group", ""))
            rows.append(row)
    return rows


# ---------------------------------------------------------------------------
# Controller service skeleton builder
# ---------------------------------------------------------------------------


def _make_service(name: str, svc_type: str, bundle: dict, properties: dict) -> dict:
    uid = new_uuid()
    return {
        "identifier": uid,
        "instanceIdentifier": uid,
        "name": name,
        "type": svc_type,
        "bundle": bundle,
        "componentType": "CONTROLLER_SERVICE",
        "scheduledState": "ENABLED",
        "properties": properties,
        "propertyDescriptors": {},
    }

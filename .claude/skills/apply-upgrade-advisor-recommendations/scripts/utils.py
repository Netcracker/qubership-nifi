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


def replace_var_refs_in_pg(pg: dict, parameter_names: set) -> int:
    """Replace ${varName} -> #{varName} only for names in parameter_names.

    NiFi uses the same ${...} syntax for FlowFile attribute expressions, so
    we must not blindly rewrite every occurrence — only variables that are
    actually being promoted to a Parameter Context should be substituted.
    """
    count = 0
    pattern = re.compile(r'\$\{([^}]+)\}')

    def replace_in_node(node):
        nonlocal count
        props = node.get("properties", {})
        for k, v in list(props.items()):
            if isinstance(v, str) and pattern.search(v):
                new_v = pattern.sub(
                    lambda m: f'#{{{m.group(1)}}}' if m.group(1) in parameter_names else m.group(0),
                    v
                )
                if new_v != v:
                    props[k] = new_v
                    count += 1

    for proc in pg.get("processors", []):
        replace_in_node(proc)
    for svc in pg.get("controllerServices", []):
        replace_in_node(svc)
    return count


# ---------------------------------------------------------------------------
# CSV parsing
# ---------------------------------------------------------------------------

UUID_RE = re.compile(r'\(([0-9a-f\-]+)\)\s*$', re.IGNORECASE)


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

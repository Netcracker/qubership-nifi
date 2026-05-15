import csv
import json
import re
import pytest
from pathlib import Path

from utils import (
    load_json,
    save_json,
    find_component,
    find_pg,
    walk_pgs,
    find_services_by_type_suffix,
    replace_var_refs_in_pg,
    parse_csv,
    _make_service,
)


# ---------------------------------------------------------------------------
# load_json / save_json
# ---------------------------------------------------------------------------

def test_load_json_happy(tmp_path):
    data = {"key": "value", "num": 42}
    p = tmp_path / "flow.json"
    p.write_text(json.dumps(data), encoding="utf-8")
    assert load_json(p) == data


def test_load_json_missing_file(tmp_path):
    with pytest.raises((FileNotFoundError, OSError)):
        load_json(tmp_path / "nonexistent.json")


def test_save_json_writes_file(tmp_path):
    p = tmp_path / "out.json"
    data = {"a": 1}
    save_json(p, data)
    loaded = json.loads(p.read_text(encoding="utf-8"))
    assert loaded == data


def test_save_json_indent(tmp_path):
    p = tmp_path / "out.json"
    save_json(p, {"x": 1})
    text = p.read_text(encoding="utf-8")
    assert "    " in text  # indent=4


# ---------------------------------------------------------------------------
# find_component
# ---------------------------------------------------------------------------

def _make_proc(pid, name="P"):
    return {"identifier": pid, "name": name, "properties": {}}


def _make_svc(sid, name="S"):
    return {"identifier": sid, "name": name, "type": "org.example.MyService", "properties": {}}


def test_find_component_top_level_processor():
    proc = _make_proc("proc-1")
    pg = {"identifier": "root", "processors": [proc], "controllerServices": [], "processGroups": []}
    comp, container = find_component(pg, "proc-1")
    assert comp is proc
    assert container is pg


def test_find_component_top_level_service():
    svc = _make_svc("svc-1")
    pg = {"identifier": "root", "processors": [], "controllerServices": [svc], "processGroups": []}
    comp, container = find_component(pg, "svc-1")
    assert comp is svc
    assert container is pg


def test_find_component_nested_pg():
    proc = _make_proc("deep-proc")
    child = {"identifier": "child", "processors": [proc], "controllerServices": [], "processGroups": []}
    root = {"identifier": "root", "processors": [], "controllerServices": [], "processGroups": [child]}
    comp, container = find_component(root, "deep-proc")
    assert comp is proc
    assert container is child


def test_find_component_not_found():
    pg = {"identifier": "root", "processors": [], "controllerServices": [], "processGroups": []}
    comp, container = find_component(pg, "missing")
    assert comp is None
    assert container is None


# ---------------------------------------------------------------------------
# find_pg
# ---------------------------------------------------------------------------

def test_find_pg_self_match():
    pg = {"identifier": "root", "processGroups": []}
    assert find_pg(pg, "root") is pg


def test_find_pg_child_match():
    child = {"identifier": "child-1", "processGroups": []}
    root = {"identifier": "root", "processGroups": [child]}
    assert find_pg(root, "child-1") is child


def test_find_pg_not_found():
    pg = {"identifier": "root", "processGroups": []}
    assert find_pg(pg, "missing") is None


# ---------------------------------------------------------------------------
# walk_pgs
# ---------------------------------------------------------------------------

def test_walk_pgs_visits_all():
    gc = {"identifier": "gc", "processGroups": []}
    child = {"identifier": "child", "processGroups": [gc]}
    root = {"identifier": "root", "processGroups": [child]}
    visited = []
    walk_pgs(root, lambda pg: visited.append(pg["identifier"]))
    assert set(visited) == {"root", "child", "gc"}


def test_walk_pgs_leaf_only():
    root = {"identifier": "root", "processGroups": []}
    visited = []
    walk_pgs(root, lambda pg: visited.append(pg["identifier"]))
    assert visited == ["root"]


# ---------------------------------------------------------------------------
# find_services_by_type_suffix
# ---------------------------------------------------------------------------

def test_find_services_by_type_suffix_match():
    svc = {"identifier": "s1", "type": "org.example.MySpecialService", "properties": {}}
    pg = {"identifier": "root", "controllerServices": [svc], "processGroups": []}
    results = find_services_by_type_suffix(pg, "MySpecialService")
    assert len(results) == 1
    assert results[0][0] is svc
    assert results[0][1] is pg


def test_find_services_by_type_suffix_nested():
    svc = {"identifier": "s2", "type": "org.example.TargetService", "properties": {}}
    child = {"identifier": "child", "controllerServices": [svc], "processGroups": []}
    root = {"identifier": "root", "controllerServices": [], "processGroups": [child]}
    results = find_services_by_type_suffix(root, "TargetService")
    assert len(results) == 1
    assert results[0][0] is svc
    assert results[0][1] is child


def test_find_services_by_type_suffix_no_match():
    svc = {"identifier": "s3", "type": "org.example.OtherService", "properties": {}}
    pg = {"identifier": "root", "controllerServices": [svc], "processGroups": []}
    assert find_services_by_type_suffix(pg, "NotPresent") == []


# ---------------------------------------------------------------------------
# replace_var_refs_in_pg
# ---------------------------------------------------------------------------

def _pg_with_proc(properties: dict) -> dict:
    return {
        "processors": [{"identifier": "p1", "properties": properties}],
        "controllerServices": [],
        "processGroups": [],
    }


def test_replace_var_refs_bare():
    pg = _pg_with_proc({"url": "${myVar}"})
    count = replace_var_refs_in_pg(pg, {"myVar"})
    assert count == 1
    assert pg["processors"][0]["properties"]["url"] == "#{myVar}"


def test_replace_var_refs_el_chain():
    pg = _pg_with_proc({"url": "${myVar:toLower()}"})
    count = replace_var_refs_in_pg(pg, {"myVar"})
    assert count == 1
    assert pg["processors"][0]["properties"]["url"] == "${#{myVar}:toLower()}"


def test_replace_var_refs_nested_el_argument():
    # Pre-pass should convert ${myVar} inside EL function argument
    pg = _pg_with_proc({"expr": "${fn(${myVar}):upper()}"})
    count = replace_var_refs_in_pg(pg, {"myVar"})
    assert count == 1
    result = pg["processors"][0]["properties"]["expr"]
    assert "#{myVar}" in result


def test_replace_var_refs_false_positive():
    # ${foo.bar} must NOT match variable "foo"
    pg = _pg_with_proc({"url": "${foo.bar}"})
    count = replace_var_refs_in_pg(pg, {"foo"})
    assert count == 0
    assert pg["processors"][0]["properties"]["url"] == "${foo.bar}"


def test_replace_var_refs_no_match():
    pg = _pg_with_proc({"url": "${otherVar}"})
    count = replace_var_refs_in_pg(pg, {"myVar"})
    assert count == 0
    assert pg["processors"][0]["properties"]["url"] == "${otherVar}"


def test_replace_var_refs_multiple_props():
    pg = _pg_with_proc({"urlA": "${myVar}", "urlB": "${myVar}"})
    count = replace_var_refs_in_pg(pg, {"myVar"})
    assert count == 2


def test_replace_var_refs_service_node():
    pg = {
        "processors": [],
        "controllerServices": [{"identifier": "s1", "properties": {"host": "${myVar}"}}],
        "processGroups": [],
    }
    count = replace_var_refs_in_pg(pg, {"myVar"})
    assert count == 1
    assert pg["controllerServices"][0]["properties"]["host"] == "#{myVar}"


def test_replace_var_refs_empty_names():
    pg = _pg_with_proc({"url": "${myVar}"})
    count = replace_var_refs_in_pg(pg, set())
    assert count == 0
    assert pg["processors"][0]["properties"]["url"] == "${myVar}"


# ---------------------------------------------------------------------------
# parse_csv
# ---------------------------------------------------------------------------

def _write_csv(tmp_path, rows: list[dict], fieldnames: list[str]) -> Path:
    p = tmp_path / "report.csv"
    with open(p, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(rows)
    return str(p)


def test_parse_csv_proc_uuid_extracted(tmp_path):
    path = _write_csv(
        tmp_path,
        [{"Flow name": "flow.json", "Processor": "MyProc (abc-123)", "Process Group": "PG (pg-456)", "Issue": "x", "Level": "WARNING", "Solution": "y"}],
        ["Flow name", "Processor", "Process Group", "Issue", "Level", "Solution"],
    )
    rows = parse_csv(path)
    assert rows[0]["_proc_uuid"] == "abc-123"


def test_parse_csv_pg_uuid_extracted(tmp_path):
    path = _write_csv(
        tmp_path,
        [{"Flow name": "flow.json", "Processor": "", "Process Group": "MyPG (abcd1234-0000-0000-0000-000000000001)", "Issue": "x", "Level": "WARNING", "Solution": "y"}],
        ["Flow name", "Processor", "Process Group", "Issue", "Level", "Solution"],
    )
    rows = parse_csv(path)
    assert rows[0]["_pg_uuid"] == "abcd1234-0000-0000-0000-000000000001"


def test_parse_csv_no_uuid_in_cell(tmp_path):
    path = _write_csv(
        tmp_path,
        [{"Flow name": "flow.json", "Processor": "MyProc", "Process Group": "PG", "Issue": "x", "Level": "WARNING", "Solution": "y"}],
        ["Flow name", "Processor", "Process Group", "Issue", "Level", "Solution"],
    )
    rows = parse_csv(path)
    assert rows[0]["_proc_uuid"] is None
    assert rows[0]["_pg_uuid"] is None


# ---------------------------------------------------------------------------
# _make_service
# ---------------------------------------------------------------------------

def test_make_service_structure():
    bundle = {"group": "org.apache.nifi", "artifact": "nifi-test-nar", "version": "1.0.0"}
    svc = _make_service("MySvc", "org.example.MySvc", bundle, {"prop1": "val1"})
    assert svc["name"] == "MySvc"
    assert svc["type"] == "org.example.MySvc"
    assert svc["bundle"] is bundle
    assert svc["componentType"] == "CONTROLLER_SERVICE"
    assert svc["scheduledState"] == "ENABLED"
    assert svc["properties"] == {"prop1": "val1"}
    assert svc["propertyDescriptors"] == {}
    assert svc["identifier"] == svc["instanceIdentifier"]
    assert re.match(
        r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        svc["identifier"],
    )

import json
from pathlib import Path

from contexts import apply_variable_contexts, apply_hardcoded_values, _hardcode_var_in_pg
from utils import load_json


def _base_flow(pg_id: str, pg_name: str, variables: dict, processors=None, processGroups=None) -> dict:
    fc = {
        "identifier": pg_id,
        "name": pg_name,
        "variables": variables,
        "processors": processors or [],
        "controllerServices": [],
        "processGroups": processGroups or [],
        "connections": [],
    }
    return {"flowContents": fc}


def _proc(pid: str, properties: dict) -> dict:
    return {"identifier": pid, "name": "P", "type": "org.example.P", "properties": properties}


def _write_flow(tmp_path: Path, rel: str, data: dict) -> Path:
    p = tmp_path / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data), encoding="utf-8")
    return p


# ---------------------------------------------------------------------------
# apply_variable_contexts
# ---------------------------------------------------------------------------

def test_apply_variable_contexts_creates_param_context(tmp_path):
    data = _base_flow("root", "Root", {"myVar": "val"})
    _write_flow(tmp_path, "flow.json", data)
    plan = [{"name": "ctx-1", "parameters": {"myVar": "val"}, "apply_to": [("flow.json", "root")]}]
    apply_variable_contexts(str(tmp_path), plan)
    result = load_json(tmp_path / "flow.json")
    assert "ctx-1" in result.get("parameterContexts", {})


def test_apply_variable_contexts_links_pg(tmp_path):
    data = _base_flow("pg-1", "MyPG", {"x": "1"})
    _write_flow(tmp_path, "flow.json", data)
    plan = [{"name": "my-ctx", "parameters": {"x": "1"}, "apply_to": [("flow.json", "pg-1")]}]
    apply_variable_contexts(str(tmp_path), plan)
    result = load_json(tmp_path / "flow.json")
    assert result["flowContents"]["parameterContextName"] == "my-ctx"


def test_apply_variable_contexts_replaces_var_refs(tmp_path):
    proc = _proc("p1", {"url": "${myVar}"})
    data = _base_flow("root", "Root", {"myVar": "http://host"}, processors=[proc])
    _write_flow(tmp_path, "flow.json", data)
    plan = [{"name": "ctx", "parameters": {"myVar": "http://host"}, "apply_to": [("flow.json", "root")]}]
    apply_variable_contexts(str(tmp_path), plan)
    result = load_json(tmp_path / "flow.json")
    assert result["flowContents"]["processors"][0]["properties"]["url"] == "#{myVar}"


def test_apply_variable_contexts_clears_variables(tmp_path):
    data = _base_flow("root", "Root", {"myVar": "val"})
    _write_flow(tmp_path, "flow.json", data)
    plan = [{"name": "ctx", "parameters": {"myVar": "val"}, "apply_to": [("flow.json", "root")]}]
    apply_variable_contexts(str(tmp_path), plan)
    result = load_json(tmp_path / "flow.json")
    assert result["flowContents"]["variables"] == {}


def test_apply_variable_contexts_inherited_context(tmp_path):
    """
    Child context inherits parent. A processor in the child PG references both
    a parent-owned var and a child-owned var. Both should be rewritten.
    """
    child_proc = _proc("cp1", {"a": "${parentVar}", "b": "${childVar}"})
    child_pg = {
        "identifier": "child-pg",
        "name": "Child",
        "variables": {"childVar": "cv"},
        "processors": [child_proc],
        "controllerServices": [],
        "processGroups": [],
    }
    root_proc = _proc("rp1", {"a": "${parentVar}"})
    data = _base_flow("root", "Root", {"parentVar": "pv"}, processors=[root_proc], processGroups=[child_pg])
    _write_flow(tmp_path, "flow.json", data)
    plan = [
        {"name": "parent-ctx", "parent": None, "parameters": {"parentVar": "pv"}, "apply_to": [("flow.json", "root")]},
        {"name": "child-ctx", "parent": "parent-ctx", "parameters": {"childVar": "cv"}, "apply_to": [("flow.json", "child-pg")]},
    ]
    apply_variable_contexts(str(tmp_path), plan)
    result = load_json(tmp_path / "flow.json")
    # Root processor: parentVar rewritten
    assert result["flowContents"]["processors"][0]["properties"]["a"] == "#{parentVar}"
    # Child processor: both parentVar (inherited) and childVar rewritten
    child_result = result["flowContents"]["processGroups"][0]
    assert child_result["processors"][0]["properties"]["a"] == "#{parentVar}"
    assert child_result["processors"][0]["properties"]["b"] == "#{childVar}"


def test_apply_variable_contexts_file_not_found(tmp_path, capsys):
    plan = [{"name": "ctx", "parameters": {"v": "1"}, "apply_to": [("missing.json", "some-pg")]}]
    apply_variable_contexts(str(tmp_path), plan)
    out = capsys.readouterr().out
    assert "WARN" in out or "not found" in out.lower()


def test_apply_variable_contexts_pg_not_found(tmp_path, capsys):
    data = _base_flow("real-pg", "Real", {"v": "1"})
    _write_flow(tmp_path, "flow.json", data)
    plan = [{"name": "ctx", "parameters": {"v": "1"}, "apply_to": [("flow.json", "wrong-pg-uuid")]}]
    apply_variable_contexts(str(tmp_path), plan)
    out = capsys.readouterr().out
    assert "WARN" in out or "not found" in out.lower()


def test_apply_variable_contexts_writes_file(tmp_path):
    data = _base_flow("root", "Root", {"x": "1"})
    _write_flow(tmp_path, "flow.json", data)
    plan = [{"name": "ctx", "parameters": {"x": "1"}, "apply_to": [("flow.json", "root")]}]
    apply_variable_contexts(str(tmp_path), plan)
    on_disk = load_json(tmp_path / "flow.json")
    assert "ctx" in on_disk.get("parameterContexts", {})


# ---------------------------------------------------------------------------
# apply_hardcoded_values  (exercises _hardcode_var_in_pg internally)
# ---------------------------------------------------------------------------

def test_apply_hardcoded_values_bare_replacement(tmp_path):
    proc = _proc("p1", {"url": "${dbUrl}"})
    data = _base_flow("root", "Root", {"dbUrl": "jdbc:h2"}, processors=[proc])
    _write_flow(tmp_path, "flow.json", data)
    plan = [{"variable": "dbUrl", "value": "jdbc:h2", "rel_path": "flow.json", "pg_uuid": "root"}]
    apply_hardcoded_values(str(tmp_path), plan)
    result = load_json(tmp_path / "flow.json")
    assert result["flowContents"]["processors"][0]["properties"]["url"] == "jdbc:h2"


def test_apply_hardcoded_values_el_chain(tmp_path):
    proc = _proc("p1", {"url": "${dbUrl:toLower()}"})
    data = _base_flow("root", "Root", {"dbUrl": "MyDB"}, processors=[proc])
    _write_flow(tmp_path, "flow.json", data)
    plan = [{"variable": "dbUrl", "value": "MyDB", "rel_path": "flow.json", "pg_uuid": "root"}]
    apply_hardcoded_values(str(tmp_path), plan)
    result = load_json(tmp_path / "flow.json")
    val = result["flowContents"]["processors"][0]["properties"]["url"]
    assert "literal(" in val
    assert "MyDB" in val
    assert ":toLower()" in val


def test_apply_hardcoded_values_descends_child_pgs(tmp_path):
    child_proc = _proc("cp1", {"x": "${v}"})
    child_pg = {
        "identifier": "child", "name": "C", "variables": {},
        "processors": [child_proc], "controllerServices": [], "processGroups": [],
    }
    data = _base_flow("root", "Root", {"v": "hello"}, processGroups=[child_pg])
    _write_flow(tmp_path, "flow.json", data)
    plan = [{"variable": "v", "value": "hello", "rel_path": "flow.json", "pg_uuid": "root"}]
    apply_hardcoded_values(str(tmp_path), plan)
    result = load_json(tmp_path / "flow.json")
    child_val = result["flowContents"]["processGroups"][0]["processors"][0]["properties"]["x"]
    assert child_val == "hello"


def test_apply_hardcoded_values_writes_file(tmp_path):
    proc = _proc("p1", {"x": "${v}"})
    data = _base_flow("root", "Root", {"v": "42"}, processors=[proc])
    _write_flow(tmp_path, "flow.json", data)
    plan = [{"variable": "v", "value": "42", "rel_path": "flow.json", "pg_uuid": "root"}]
    apply_hardcoded_values(str(tmp_path), plan)
    on_disk = load_json(tmp_path / "flow.json")
    assert on_disk["flowContents"]["processors"][0]["properties"]["x"] == "42"


def test_apply_hardcoded_values_pg_not_found(tmp_path, capsys):
    data = _base_flow("root", "Root", {"v": "1"})
    _write_flow(tmp_path, "flow.json", data)
    plan = [{"variable": "v", "value": "1", "rel_path": "flow.json", "pg_uuid": "wrong-uuid"}]
    apply_hardcoded_values(str(tmp_path), plan)
    out = capsys.readouterr().out
    assert "WARN" in out


def test_apply_hardcoded_values_file_not_found(tmp_path, capsys):
    plan = [{"variable": "v", "value": "1", "rel_path": "missing.json", "pg_uuid": "root"}]
    apply_hardcoded_values(str(tmp_path), plan)
    out = capsys.readouterr().out
    assert "WARN" in out


# ---------------------------------------------------------------------------
# _hardcode_var_in_pg  (direct unit tests)
# ---------------------------------------------------------------------------

def _pg(processors=None, services=None, child_pgs=None, variables=None):
    return {
        "processors": processors or [],
        "controllerServices": services or [],
        "processGroups": child_pgs or [],
        "variables": variables or {},
    }


def _proc(pid: str, properties: dict) -> dict:
    return {"identifier": pid, "properties": properties}


def test_hardcode_var_in_pg_bare_ref():
    pg = _pg(processors=[_proc("p1", {"url": "${myVar}"})])
    count = _hardcode_var_in_pg(pg, "myVar", "http://example.com")
    assert count == 1
    assert pg["processors"][0]["properties"]["url"] == "http://example.com"


def test_hardcode_var_in_pg_el_chain():
    pg = _pg(processors=[_proc("p1", {"url": "${myVar:toUpper()}"})])
    count = _hardcode_var_in_pg(pg, "myVar", "mydb")
    assert count == 1
    val = pg["processors"][0]["properties"]["url"]
    assert 'literal("mydb")' in val
    assert ":toUpper()" in val


def test_hardcode_var_in_pg_special_chars_in_value():
    pg = _pg(processors=[_proc("p1", {"q": "${v:fn()}"})])
    count = _hardcode_var_in_pg(pg, "v", 'say \\"hello\\"')
    assert count == 1
    val = pg["processors"][0]["properties"]["q"]
    assert "literal(" in val


def test_hardcode_var_in_pg_no_match():
    pg = _pg(processors=[_proc("p1", {"url": "${otherVar}"})])
    count = _hardcode_var_in_pg(pg, "myVar", "value")
    assert count == 0
    assert pg["processors"][0]["properties"]["url"] == "${otherVar}"


def test_hardcode_var_in_pg_descends_child_pgs():
    child = _pg(processors=[_proc("cp1", {"x": "${v}"})])
    pg = _pg(child_pgs=[child])
    count = _hardcode_var_in_pg(pg, "v", "hello")
    assert count == 1
    assert child["processors"][0]["properties"]["x"] == "hello"


def test_hardcode_var_in_pg_skips_shadowed_child():
    """When a child PG defines the same variable name, its processors and
    descendants must not be replaced — the child's own variable shadows the
    parent's, so those references should continue to use the child's value."""
    grandchild = _pg(
        processors=[_proc("gp1", {"url": "${host}"})],
        variables={},
    )
    child = _pg(
        processors=[_proc("cp1", {"url": "${host}"})],
        child_pgs=[grandchild],
        variables={"host": "b"},
    )
    parent_proc = _proc("pp1", {"url": "${host}"})
    pg = _pg(
        processors=[parent_proc],
        child_pgs=[child],
        variables={"host": "a"},
    )
    count = _hardcode_var_in_pg(pg, "host", "a")
    # Only the parent-direct processor is replaced.
    assert count == 1
    assert parent_proc["properties"]["url"] == "a"
    assert child["processors"][0]["properties"]["url"] == "${host}"
    assert grandchild["processors"][0]["properties"]["url"] == "${host}"
    # Parent variable removed; child variable preserved.
    assert "host" not in pg["variables"]
    assert "host" in child["variables"]

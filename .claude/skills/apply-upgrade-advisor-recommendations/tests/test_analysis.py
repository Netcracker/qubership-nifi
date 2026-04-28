import json
import pytest
from pathlib import Path

from analysis import collect_variable_analysis


def _write_flow(tmp_path: Path, name: str, flow_contents: dict) -> None:
    data = {"flowContents": flow_contents}
    (tmp_path / name).write_text(json.dumps(data), encoding="utf-8")


def _pg(identifier: str, name: str, variables: dict, processors=None, processGroups=None) -> dict:
    return {
        "identifier": identifier,
        "name": name,
        "variables": variables,
        "processors": processors or [],
        "controllerServices": [],
        "processGroups": processGroups or [],
    }


def _proc(pid: str, properties: dict) -> dict:
    return {"identifier": pid, "name": "P", "properties": properties}


# ---------------------------------------------------------------------------
# Basic collection
# ---------------------------------------------------------------------------

def test_collect_single_var_present(tmp_path):
    pg = _pg("pg-1", "MyPG", {"dbUrl": "jdbc:h2"})
    _write_flow(tmp_path, "flow.json", pg)
    result = collect_variable_analysis(str(tmp_path))
    assert "dbUrl" in result
    assert result["dbUrl"]["occurrences"][0]["value"] == "jdbc:h2"
    assert result["dbUrl"]["occurrences"][0]["pg_name"] == "MyPG"


def test_collect_values_differ_false(tmp_path):
    pg1 = _pg("pg-1", "PG1", {"host": "localhost"})
    pg2 = _pg("pg-2", "PG2", {"host": "localhost"})
    root = _pg("root", "Root", {}, processGroups=[pg1, pg2])
    _write_flow(tmp_path, "flow.json", root)
    result = collect_variable_analysis(str(tmp_path))
    assert result["host"]["values_differ"] is False


def test_collect_values_differ_true(tmp_path):
    pg1 = _pg("pg-1", "PG1", {"host": "server-a"})
    pg2 = _pg("pg-2", "PG2", {"host": "server-b"})
    root = _pg("root", "Root", {}, processGroups=[pg1, pg2])
    _write_flow(tmp_path, "flow.json", root)
    result = collect_variable_analysis(str(tmp_path))
    assert result["host"]["values_differ"] is True


def test_collect_child_pg_refs_counted(tmp_path):
    child = _pg(
        "child", "Child", {},
        processors=[_proc("p1", {"url": "${myVar}"})]
    )
    parent = _pg("parent", "Parent", {"myVar": "http://example.com"}, processGroups=[child])
    _write_flow(tmp_path, "flow.json", parent)
    result = collect_variable_analysis(str(tmp_path))
    # The subtree reference_count for the parent occurrence should include the child ref
    parent_occ = next(o for o in result["myVar"]["occurrences"] if o["pg_id"] == "parent")
    assert parent_occ["reference_count"] >= 1


def test_collect_no_variables(tmp_path):
    pg = _pg("root", "Root", {}, processors=[_proc("p1", {"url": "http://fixed"})])
    _write_flow(tmp_path, "flow.json", pg)
    result = collect_variable_analysis(str(tmp_path))
    assert result == {}


def test_collect_multiple_files(tmp_path):
    _write_flow(tmp_path, "flow1.json", _pg("pg-a", "A", {"varA": "valA"}))
    _write_flow(tmp_path, "flow2.json", _pg("pg-b", "B", {"varB": "valB"}))
    result = collect_variable_analysis(str(tmp_path))
    assert "varA" in result
    assert "varB" in result


def test_collect_skips_corrupt_file(tmp_path):
    (tmp_path / "bad.json").write_text("{{not json}}", encoding="utf-8")
    _write_flow(tmp_path, "good.json", _pg("root", "Root", {"x": "1"}))
    result = collect_variable_analysis(str(tmp_path))
    assert "x" in result


# ---------------------------------------------------------------------------
# False-positive guard: ${foo.bar} must NOT count as a reference to variable "foo"
# ---------------------------------------------------------------------------

def test_count_refs_no_false_positive(tmp_path):
    proc = _proc("p1", {"url": "${foo.bar}"})
    pg = _pg("root", "Root", {"foo": "myvalue"}, processors=[proc])
    _write_flow(tmp_path, "flow.json", pg)
    result = collect_variable_analysis(str(tmp_path))
    # ${foo.bar} should not be counted as a reference to variable "foo"
    root_occ = result["foo"]["occurrences"][0]
    assert root_occ["reference_count"] == 0

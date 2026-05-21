import csv
import json
import sys
import subprocess
import pytest
from pathlib import Path

from upgrade_nifi_lib import detect_exports_dir, analyze, detect_standalone_cs


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _write_csv(tmp_path: Path, rows: list[dict]) -> str:
    p = tmp_path / "report.csv"
    fieldnames = ["Flow name", "Processor", "Process Group", "Issue", "Level", "Solution"]
    with open(p, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for row in rows:
            w.writerow({k: row.get(k, "") for k in fieldnames})
    return str(p)


def _row(flow_name: str, issue: str = "some issue", level: str = "WARNING", proc: str = "") -> dict:
    return {"Flow name": flow_name, "Processor": proc, "Process Group": "", "Issue": issue, "Level": level, "Solution": "fix"}


# ---------------------------------------------------------------------------
# detect_exports_dir
# ---------------------------------------------------------------------------

def test_detect_exports_dir_found(tmp_path, capsys):
    subdir = tmp_path / "exports" / "domain1"
    subdir.mkdir(parents=True)
    (subdir / "myflow.json").write_text("{}", encoding="utf-8")
    # Flow name is "domain1/myflow.json"; file lives under exports/domain1/
    # detect_exports_dir strips the flow name from the rel path -> "exports"
    csv_path = _write_csv(tmp_path, [_row("domain1/myflow.json")])
    detect_exports_dir(csv_path, str(tmp_path))
    out = capsys.readouterr().out.strip()
    assert out == "exports"


def test_detect_exports_dir_windows_backslash(tmp_path, capsys):
    subdir = tmp_path / "exports"
    subdir.mkdir(parents=True)
    (subdir / "myflow.json").write_text("{}", encoding="utf-8")
    # CSV row uses backslash as path separator; should be normalised to "exports/myflow.json"
    # The whole rel path equals the flow name, so exports_dir = "."
    csv_path = _write_csv(tmp_path, [_row("exports\\myflow.json")])
    detect_exports_dir(csv_path, str(tmp_path))
    out = capsys.readouterr().out.strip()
    assert out == "."


def test_detect_exports_dir_not_found(tmp_path):
    csv_path = _write_csv(tmp_path, [_row("missing/flow.json")])
    with pytest.raises(SystemExit) as exc_info:
        detect_exports_dir(csv_path, str(tmp_path))
    assert exc_info.value.code == 1


def test_detect_exports_dir_empty_csv(tmp_path):
    p = tmp_path / "empty.csv"
    p.write_text("Flow name,Processor,Process Group,Issue,Level,Solution\n", encoding="utf-8")
    with pytest.raises(SystemExit) as exc_info:
        detect_exports_dir(str(p), str(tmp_path))
    assert exc_info.value.code == 1


# ---------------------------------------------------------------------------
# analyze
# ---------------------------------------------------------------------------

def test_analyze_valid_csv_contains_tags(tmp_path, capsys):
    csv_path = _write_csv(tmp_path, [
        _row("flow.json", "proxy properties in InvokeHTTP", proc="InvokeHTTP (p1)"),
    ])
    analyze(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    assert "[AUTO]" in out


def test_analyze_nonexistent_csv_prints_skip(tmp_path, capsys):
    analyze(str(tmp_path / "nonexistent.csv"), str(tmp_path))
    out = capsys.readouterr().out
    assert "skipping" in out.lower() or "no csv" in out.lower()


def test_analyze_all_four_tags(tmp_path, capsys):
    csv_path = _write_csv(tmp_path, [
        _row("flow.json", "proxy properties in InvokeHTTP", proc="P (p1)"),
        _row("flow.json", "Script engine = python", proc="S (p2)"),
        _row("flow.json", "variables are not available", proc="V (p3)"),
        _row("flow.json", "unsupported feature", level="ERROR"),
    ])
    analyze(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    assert "[AUTO]" in out
    assert "[AI Agent]" in out
    assert "[CONTEXT PLAN]" in out
    assert "[MANUAL]" in out


# ---------------------------------------------------------------------------
# --collect-vars CLI path (subprocess)
# ---------------------------------------------------------------------------

def test_collect_vars_cli_outputs_json(tmp_path):
    pg = {
        "identifier": "root", "name": "Root",
        "variables": {"myVar": "myValue"},
        "processors": [], "controllerServices": [],
        "processGroups": [], "connections": [],
    }
    flow_file = tmp_path / "flow.json"
    flow_file.write_text(json.dumps({"flowContents": pg}), encoding="utf-8")

    scripts_dir = Path(__file__).parent.parent / "scripts"
    upgrade_nifi_lib_path = scripts_dir / "upgrade_nifi_lib.py"

    # argparse positional layout: csv_path (nargs=?) then exports_dir (nargs=?)
    # --collect-vars uses args.exports_dir, so pass a dummy first positional
    result = subprocess.run(
        [sys.executable, str(upgrade_nifi_lib_path), "--collect-vars", "dummy_csv", str(tmp_path)],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    assert result.returncode == 0
    data = json.loads(result.stdout)
    assert "myVar" in data
    assert data["myVar"]["occurrences"][0]["value"] == "myValue"


# ---------------------------------------------------------------------------
# detect_standalone_cs
# ---------------------------------------------------------------------------

def _write_standalone_cs(tmp_path: Path, rel_path: str, component: dict) -> None:
    p = tmp_path / rel_path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps({"component": component}), encoding="utf-8")


def test_detect_standalone_cs_prometheus(tmp_path, capsys):
    component = {
        "name": "CommonPrometheusRecordSink",
        "type": "org.apache.nifi.reporting.prometheus.PrometheusRecordSink",
        "properties": {},
    }
    _write_standalone_cs(tmp_path, "cs/prom.json", component)
    csv_path = _write_csv(tmp_path, [
        _row("cs/prom.json", "PrometheusRecordSink Controller Service is not available in Apache NiFi 2.x.")
    ])
    detect_standalone_cs(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    results = json.loads(out)
    assert len(results) == 1
    assert "Qubership" in results[0]["suggested_name"]
    assert results[0]["file"] == "cs/prom.json"


def test_detect_standalone_cs_azure(tmp_path, capsys):
    component = {
        "name": "CommonAzureStorageCredentials",
        "type": "org.apache.nifi.services.azure.storage.AzureStorageCredentialsControllerService",
        "properties": {},
    }
    _write_standalone_cs(tmp_path, "cs/azure.json", component)
    csv_path = _write_csv(tmp_path, [
        _row("cs/azure.json", "AzureStorageCredentialsControllerService Controller Service is not available in Apache NiFi 2.x.")
    ])
    detect_standalone_cs(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    results = json.loads(out)
    assert len(results) == 1
    assert results[0]["suggested_name"].endswith(" v12")


def test_detect_standalone_cs_skips_flow_contents(tmp_path, capsys):
    # File has flowContents — it's a flow, not a standalone CS file
    flow_data = {
        "component": {
            "name": "SomeSvc",
            "type": "org.apache.nifi.reporting.prometheus.PrometheusRecordSink",
            "properties": {},
        },
        "flowContents": {"identifier": "root"},
    }
    p = tmp_path / "cs" / "flow.json"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(flow_data), encoding="utf-8")
    csv_path = _write_csv(tmp_path, [
        _row("cs/flow.json", "PrometheusRecordSink Controller Service is not available in Apache NiFi 2.x.")
    ])
    detect_standalone_cs(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    results = json.loads(out)
    assert results == []


def test_detect_standalone_cs_empty_csv(tmp_path, capsys):
    csv_path = _write_csv(tmp_path, [])
    detect_standalone_cs(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    results = json.loads(out)
    assert results == []


def test_detect_standalone_cs_already_v12(tmp_path, capsys):
    component = {
        "name": "CommonAzureStorageCredentials v12",
        "type": "org.apache.nifi.services.azure.storage.AzureStorageCredentialsControllerService_v12",
        "properties": {},
    }
    _write_standalone_cs(tmp_path, "cs/azure_v12.json", component)
    csv_path = _write_csv(tmp_path, [
        _row("cs/azure_v12.json", "AzureStorageCredentialsControllerService Controller Service is not available in Apache NiFi 2.x.")
    ])
    detect_standalone_cs(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    results = json.loads(out)
    # Type doesn't match the suffix check -> skipped
    assert results == []

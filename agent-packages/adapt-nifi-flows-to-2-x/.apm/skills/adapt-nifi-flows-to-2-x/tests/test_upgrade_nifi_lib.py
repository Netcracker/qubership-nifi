import csv
import json
import sys
import subprocess
import pytest
from pathlib import Path

from upgrade_nifi_lib import detect_exports_dir, analyze, detect_standalone_cs, show_processor_props, show_contexts


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
    # File has flowContents - it's a flow, not a standalone CS file
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


# ---------------------------------------------------------------------------
# Helpers for show_processor_props / show_contexts
# ---------------------------------------------------------------------------

def _write_flow(tmp_path: Path, rel: str, proc_uuid: str, proc_name: str, properties: dict) -> None:
    path = tmp_path / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    data = {
        "flowContents": {
            "identifier": "root",
            "name": "Root PG",
            "processors": [
                {
                    "identifier": proc_uuid,
                    "name": proc_name,
                    "type": f"org.apache.nifi.processors.standard.{proc_name}",
                    "properties": properties,
                }
            ],
            "controllerServices": [],
            "processGroups": [],
            "connections": [],
            "variables": {},
        }
    }
    path.write_text(json.dumps(data), encoding="utf-8")


def _write_flow_with_contexts(
    tmp_path: Path,
    rel: str,
    pg_name: str,
    pg_context,
    contexts: dict,
) -> None:
    path = tmp_path / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    data = {
        "flowContents": {
            "identifier": "root",
            "name": pg_name,
            "parameterContextName": pg_context,
            "processors": [],
            "controllerServices": [],
            "processGroups": [],
            "connections": [],
            "variables": {},
        },
        "parameterContexts": contexts,
    }
    path.write_text(json.dumps(data), encoding="utf-8")


# ---------------------------------------------------------------------------
# analyze -- issue flags
# ---------------------------------------------------------------------------

def test_analyze_includes_issue_flags_proxy_and_aws(tmp_path, capsys):
    csv_path = _write_csv(tmp_path, [
        _row("flow.json", "Proxy properties in InvokeHTTP processor is not available", proc="InvokeHTTP (p1)"),
        _row("flow.json", "The org.apache.nifi.processors.aws.s3.FetchS3Object processor may contain the \"Access Key ID\" and \"Secret Access Key\"", proc="FetchS3Object (p2)"),
    ])
    analyze(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    assert "=== Issue Flags ===" in out
    flags_str = out[out.index("=== Issue Flags ===") + len("=== Issue Flags ==="):]
    flags = json.loads(flags_str.strip())
    assert flags["proxy"] is True
    assert flags["aws"] is True
    assert flags["prometheus"] is False
    assert flags["script_engine"] is False
    assert flags["variables"] is False


def test_analyze_issue_flags_script_engine(tmp_path, capsys):
    csv_path = _write_csv(tmp_path, [
        _row("flow.json", "Script Engine = python is not supported", proc="ExecScript (p1)"),
    ])
    analyze(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    flags_str = out[out.index("=== Issue Flags ===") + len("=== Issue Flags ==="):]
    flags = json.loads(flags_str.strip())
    assert flags["script_engine"] is True
    assert flags["proxy"] is False


def test_analyze_issue_flags_empty_csv(tmp_path, capsys):
    analyze(str(tmp_path / "nonexistent.csv"), str(tmp_path))
    out = capsys.readouterr().out
    assert "=== Issue Flags ===" in out
    flags_str = out[out.index("=== Issue Flags ===") + len("=== Issue Flags ==="):]
    flags = json.loads(flags_str.strip())
    assert flags["proxy"] is False
    assert flags["aws"] is False
    assert flags["all_issues"] == []


# ---------------------------------------------------------------------------
# show_processor_props
# ---------------------------------------------------------------------------

def test_show_processor_props_proxy(tmp_path, capsys):
    uuid = "aaaa-1111"
    _write_flow(
        tmp_path, "flows/myflow.json", uuid, "InvokeHTTP",
        {
            "Proxy Host": "proxy.example.com",
            "Proxy Port": "8080",
            "Proxy Type": "HTTP",
            "invokehttp-proxy-user": "#{proxy.username}",
            "invokehttp-proxy-password": "#{proxy.password}",
            "some-other-prop": "irrelevant",
        },
    )
    csv_path = _write_csv(tmp_path, [
        _row("flows/myflow.json", "Proxy properties in InvokeHTTP processor is not available in Apache NiFi 2.x.", proc=f"InvokeHTTP ({uuid})"),
    ])
    show_processor_props(csv_path, str(tmp_path), "fix_invokehttp_proxy")
    out = capsys.readouterr().out
    results = json.loads(out)
    assert len(results) == 1
    assert results[0]["uuid"] == uuid
    assert results[0]["file"] == "flows/myflow.json"
    assert results[0]["properties"]["Proxy Host"] == "proxy.example.com"
    assert results[0]["properties"]["Proxy Port"] == "8080"
    assert results[0]["properties"]["Proxy Type"] == "HTTP"
    assert results[0]["properties"]["invokehttp-proxy-user"] == "#{proxy.username}"
    assert results[0]["properties"]["invokehttp-proxy-password"] == "#{proxy.password}"
    assert "some-other-prop" not in results[0]["properties"]


def test_show_processor_props_proxy_absent_keys_omitted(tmp_path, capsys):
    uuid = "bbbb-2222"
    _write_flow(tmp_path, "flows/myflow.json", uuid, "InvokeHTTP", {"Proxy Host": "host.example.com"})
    csv_path = _write_csv(tmp_path, [
        _row("flows/myflow.json", "Proxy properties in InvokeHTTP processor is not available in Apache NiFi 2.x.", proc=f"InvokeHTTP ({uuid})"),
    ])
    show_processor_props(csv_path, str(tmp_path), "fix_invokehttp_proxy")
    out = capsys.readouterr().out
    results = json.loads(out)
    assert len(results) == 1
    assert list(results[0]["properties"].keys()) == ["Proxy Host"]


def test_show_processor_props_aws(tmp_path, capsys):
    uuid = "cccc-3333"
    _write_flow(
        tmp_path, "flows/s3flow.json", uuid, "FetchS3Object",
        {"Access Key": "#{access.key.id}", "Secret Key": "#{secret.access.key}", "Bucket": "my-bucket"},
    )
    csv_path = _write_csv(tmp_path, [
        _row("flows/s3flow.json", "The org.apache.nifi.processors.aws.s3.FetchS3Object processor may contain the Access Key ID and Secret Access Key", proc=f"FetchS3Object ({uuid})"),
    ])
    show_processor_props(csv_path, str(tmp_path), "fix_s3_credentials")
    out = capsys.readouterr().out
    results = json.loads(out)
    assert len(results) == 1
    assert results[0]["uuid"] == uuid
    assert results[0]["properties"]["Access Key"] == "#{access.key.id}"
    assert results[0]["properties"]["Secret Key"] == "#{secret.access.key}"
    assert "Bucket" not in results[0]["properties"]


def test_show_processor_props_aws_empty_credentials(tmp_path, capsys):
    uuid = "dddd-4444"
    _write_flow(tmp_path, "flows/s3flow.json", uuid, "PutS3Object", {"Bucket": "my-bucket"})
    csv_path = _write_csv(tmp_path, [
        _row("flows/s3flow.json", "The org.apache.nifi.processors.aws.s3.PutS3Object processor may contain the Access Key ID and Secret Access Key", proc=f"PutS3Object ({uuid})"),
    ])
    show_processor_props(csv_path, str(tmp_path), "fix_s3_credentials")
    out = capsys.readouterr().out
    results = json.loads(out)
    assert len(results) == 1
    assert results[0]["properties"] == {}


def test_show_processor_props_unknown_handler_exits(tmp_path):
    csv_path = _write_csv(tmp_path, [])
    with pytest.raises(SystemExit) as exc_info:
        show_processor_props(csv_path, str(tmp_path), "unknown_handler")
    assert exc_info.value.code == 1


# ---------------------------------------------------------------------------
# show_contexts
# ---------------------------------------------------------------------------

def test_show_contexts_basic(tmp_path, capsys):
    _write_flow_with_contexts(
        tmp_path, "flows/myflow.json", "My Flow PG", "proxy-params",
        {"ctx-uuid-1": {"name": "proxy-params", "parameters": [{"name": "proxy.username"}, {"name": "proxy.password"}], "inheritedParameterContexts": []}},
    )
    show_contexts(str(tmp_path))
    out = capsys.readouterr().out
    data = json.loads(out)
    entry = data["flows/myflow.json"]
    assert entry["pg_name"] == "My Flow PG"
    assert entry["pg_context"] == "proxy-params"
    assert set(entry["contexts"]["proxy-params"]["direct_params"]) == {"proxy.username", "proxy.password"}
    assert entry["contexts"]["proxy-params"]["inherited"] == []


def test_show_contexts_no_pg_context(tmp_path, capsys):
    _write_flow_with_contexts(tmp_path, "flows/parent.json", "Parent PG", None, {})
    show_contexts(str(tmp_path))
    out = capsys.readouterr().out
    data = json.loads(out)
    entry = data["flows/parent.json"]
    assert entry["pg_context"] is None
    assert entry["contexts"] == {}


def test_show_contexts_inherited_resolved_to_name(tmp_path, capsys):
    _write_flow_with_contexts(
        tmp_path, "flows/child.json", "Child PG", "child-ctx",
        {
            "uuid-parent": {"name": "parent-ctx", "parameters": [{"name": "common.param"}], "inheritedParameterContexts": []},
            "uuid-child":  {"name": "child-ctx",  "parameters": [{"name": "child.param"}],  "inheritedParameterContexts": ["uuid-parent"]},
        },
    )
    show_contexts(str(tmp_path))
    out = capsys.readouterr().out
    data = json.loads(out)
    assert data["flows/child.json"]["contexts"]["child-ctx"]["inherited"] == ["parent-ctx"]


def test_show_contexts_skips_standalone_cs(tmp_path, capsys):
    cs_path = tmp_path / "services" / "my_cs.json"
    cs_path.parent.mkdir(parents=True, exist_ok=True)
    cs_path.write_text(json.dumps({"component": {"name": "MyService", "type": "some.Type"}}), encoding="utf-8")
    _write_flow_with_contexts(tmp_path, "flows/myflow.json", "Flow PG", None, {})
    show_contexts(str(tmp_path))
    out = capsys.readouterr().out
    data = json.loads(out)
    assert "flows/myflow.json" in data
    assert not any("my_cs" in k for k in data)


def test_show_contexts_multiple_files(tmp_path, capsys):
    _write_flow_with_contexts(
        tmp_path, "flows/flow1.json", "PG One", "ctx1",
        {"u1": {"name": "ctx1", "parameters": [{"name": "p1"}], "inheritedParameterContexts": []}},
    )
    _write_flow_with_contexts(tmp_path, "flows/flow2.json", "PG Two", None, {})
    show_contexts(str(tmp_path))
    out = capsys.readouterr().out
    data = json.loads(out)
    assert data["flows/flow1.json"]["pg_context"] == "ctx1"
    assert data["flows/flow2.json"]["pg_context"] is None

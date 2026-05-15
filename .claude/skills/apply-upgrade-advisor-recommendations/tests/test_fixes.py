import csv
import json
from pathlib import Path
from unittest.mock import patch

from fixes import (
    fix_invokehttp_proxy,
    fix_s3_credentials,
    fix_convert_json_to_sql,
    fix_type_rename,
    upgrade_azure_credentials_service,
    upgrade_prometheus_record_sink,
    _classify_row,
    _proxy_service_name,
    apply_csv_transforms,
)
from utils import load_json


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _proc(pid: str, properties: dict, proc_type: str = "org.apache.nifi.processors.standard.InvokeHTTP") -> dict:
    return {
        "identifier": pid,
        "instanceIdentifier": pid,
        "name": "MyProc",
        "type": proc_type,
        "properties": dict(properties),
        "propertyDescriptors": {},
        "autoTerminatedRelationships": [],
    }


def _pg(pg_id: str = "pg-1") -> dict:
    return {
        "identifier": pg_id,
        "name": "MyPG",
        "variables": {},
        "processors": [],
        "controllerServices": [],
        "processGroups": [],
        "connections": [],
    }


def _flow(pg_id: str, processors=None, controllerServices=None, connections=None, processGroups=None) -> dict:
    fc = {
        "identifier": pg_id,
        "name": "Root",
        "variables": {},
        "processors": processors or [],
        "controllerServices": controllerServices or [],
        "processGroups": processGroups or [],
        "connections": connections or [],
    }
    return {"flowContents": fc}


def _write_flow(tmp_path: Path, name: str, data: dict) -> Path:
    p = tmp_path / name
    p.write_text(json.dumps(data), encoding="utf-8")
    return p


def _write_csv(tmp_path: Path, rows: list[dict]) -> str:
    p = tmp_path / "report.csv"
    fieldnames = ["Flow name", "Processor", "Process Group", "Issue", "Level", "Solution"]
    with open(p, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for row in rows:
            full = {k: row.get(k, "") for k in fieldnames}
            w.writerow(full)
    return str(p)


# ---------------------------------------------------------------------------
# fix_invokehttp_proxy
# ---------------------------------------------------------------------------

def test_fix_invokehttp_proxy_creates_service():
    proc = _proc("p1", {"Proxy Host": "proxy.example.com", "Proxy Port": "3128"})
    pg = _pg()
    msgs = fix_invokehttp_proxy(proc, pg, {})
    assert len(pg["controllerServices"]) == 1
    assert "StandardProxyConfigurationService" in pg["controllerServices"][0]["type"]
    assert msgs


def test_fix_invokehttp_proxy_sets_reference():
    proc = _proc("p1", {"Proxy Host": "proxy.example.com"})
    pg = _pg()
    fix_invokehttp_proxy(proc, pg, {})
    svc_id = pg["controllerServices"][0]["identifier"]
    assert proc["properties"].get("proxy-configuration-service") == svc_id


def test_fix_invokehttp_proxy_removes_old_props():
    proc = _proc("p1", {"Proxy Host": "h", "Proxy Port": "3128", "Proxy Type": "HTTP"})
    pg = _pg()
    fix_invokehttp_proxy(proc, pg, {})
    assert "Proxy Host" not in proc["properties"]
    assert "Proxy Port" not in proc["properties"]
    assert "Proxy Type" not in proc["properties"]


def test_fix_invokehttp_proxy_partial_props():
    proc = _proc("p1", {"Proxy Host": "h"})
    pg = _pg()
    msgs = fix_invokehttp_proxy(proc, pg, {})
    assert len(pg["controllerServices"]) == 1
    assert msgs


def test_fix_invokehttp_proxy_no_proxy_props():
    proc = _proc("p1", {"HTTP Method": "GET"})
    pg = _pg()
    msgs, _ = fix_invokehttp_proxy(proc, pg, {})
    assert msgs == []
    assert pg["controllerServices"] == []


def test_fix_invokehttp_proxy_proxy_type_normalized():
    proc = _proc("p1", {"Proxy Host": "h", "Proxy Type": "direct"})
    pg = _pg()
    fix_invokehttp_proxy(proc, pg, {})
    svc_props = pg["controllerServices"][0]["properties"]
    assert svc_props.get("proxy-type") == "DIRECT"


def test_fix_invokehttp_proxy_reuse_mode():
    proc = _proc("p1", {"Proxy Host": "h", "Proxy Port": "3128"})
    pg = _pg()
    msgs, svc_id = fix_invokehttp_proxy(proc, pg, {}, reuse_svc_id="existing-uuid")
    assert pg["controllerServices"] == []
    assert proc["properties"].get("proxy-configuration-service") == "existing-uuid"
    assert "Proxy Host" not in proc["properties"]
    assert any("existing" in m.lower() for m in msgs)


def test_fix_invokehttp_proxy_reuse_mode_returns_provided_svc_id():
    proc = _proc("p1", {"Proxy Host": "h"})
    pg = _pg()
    _, svc_id = fix_invokehttp_proxy(proc, pg, {}, reuse_svc_id="my-specific-uuid")
    assert svc_id == "my-specific-uuid"


def test_fix_invokehttp_proxy_cross_file_same_file(tmp_path):
    proc = _proc("p1", {"Proxy Host": "h", "Proxy Port": "3128"})
    flow_file = _write_flow(tmp_path, "flow.json", _flow("root", processors=[proc]))
    pg = _pg()
    fix_invokehttp_proxy(
        proc, pg, {},
        parent_pg_path=str(flow_file),
        child_pg_path=str(flow_file),
    )
    result = load_json(flow_file)
    assert len(result["flowContents"]["controllerServices"]) == 1
    assert "externalControllerServices" not in result
    proc_on_disk = result["flowContents"]["processors"][0]
    assert "proxy-configuration-service" in proc_on_disk["properties"]


def test_fix_invokehttp_proxy_cross_file_different_files(tmp_path):
    proc = _proc("p1", {"Proxy Host": "h"})
    parent_file = _write_flow(tmp_path, "parent.json", _flow("pg-parent"))
    child_file = _write_flow(tmp_path, "child.json", _flow("pg-child", processors=[proc]))
    pg = _pg()
    msgs, svc_id = fix_invokehttp_proxy(
        proc, pg, {},
        parent_pg_path=str(parent_file),
        child_pg_path=str(child_file),
    )
    parent_result = load_json(parent_file)
    assert len(parent_result["flowContents"]["controllerServices"]) == 1
    child_result = load_json(child_file)
    assert svc_id in child_result.get("externalControllerServices", {})
    proc_on_disk = child_result["flowContents"]["processors"][0]
    assert proc_on_disk["properties"].get("proxy-configuration-service") == svc_id


def test_fix_invokehttp_proxy_service_name_includes_host_port():
    proc = _proc("p1", {"Proxy Host": "proxy.example.com", "Proxy Port": "3128"})
    pg = _pg()
    fix_invokehttp_proxy(proc, pg, {})
    assert pg["controllerServices"][0]["name"] == "ProxyConfigurationService-proxy.example.com-3128"


def test_fix_invokehttp_proxy_two_services_have_unique_names():
    proc1 = _proc("p1", {"Proxy Host": "host-a", "Proxy Port": "8080"})
    proc2 = _proc("p2", {"Proxy Host": "host-b", "Proxy Port": "9090"})
    pg = _pg()
    pg["processors"] = [proc1, proc2]
    fix_invokehttp_proxy(proc1, pg, {})
    fix_invokehttp_proxy(proc2, pg, {})
    names = [svc["name"] for svc in pg["controllerServices"]]
    assert names[0] != names[1]
    assert names[0] == "ProxyConfigurationService-host-a-8080"
    assert names[1] == "ProxyConfigurationService-host-b-9090"


def test_proxy_service_name_host_only():
    assert _proxy_service_name({"Proxy Host": "myhost"}) == "ProxyConfigurationService-myhost"


def test_proxy_service_name_no_props():
    assert _proxy_service_name({}) == "ProxyConfigurationService"


# ---------------------------------------------------------------------------
# fix_s3_credentials
# ---------------------------------------------------------------------------

def test_fix_s3_credentials_creates_service():
    proc = _proc("p1", {"Access Key": "AKID", "Secret Key": "SECRET"})
    pg = _pg()
    msgs, _ = fix_s3_credentials(proc, pg, {})
    assert len(pg["controllerServices"]) == 1
    assert "AWSCredentialsProviderControllerService" in pg["controllerServices"][0]["type"]
    assert msgs


def test_fix_s3_credentials_only_access_key():
    proc = _proc("p1", {"Access Key": "AKID"})
    pg = _pg()
    msgs, _ = fix_s3_credentials(proc, pg, {})
    assert len(pg["controllerServices"]) == 1
    assert pg["controllerServices"][0]["properties"].get("Access Key") == "AKID"
    assert msgs


def test_fix_s3_credentials_no_creds():
    proc = _proc("p1", {"Bucket": "my-bucket"})
    pg = _pg()
    msgs, svc_id = fix_s3_credentials(proc, pg, {})
    assert msgs == []
    assert svc_id == ""
    assert pg["controllerServices"] == []


def test_fix_s3_credentials_sets_reference():
    proc = _proc("p1", {"Access Key": "AK", "Secret Key": "SK"})
    pg = _pg()
    fix_s3_credentials(proc, pg, {})
    svc_id = pg["controllerServices"][0]["identifier"]
    assert proc["properties"].get("AWS Credentials Provider service") == svc_id


def test_fix_s3_credentials_removes_old_props():
    proc = _proc("p1", {"Access Key": "AK", "Secret Key": "SK", "Bucket": "my-bucket"})
    pg = _pg()
    fix_s3_credentials(proc, pg, {})
    assert "Access Key" not in proc["properties"]
    assert "Secret Key" not in proc["properties"]


def test_fix_s3_credentials_cross_file_different_files(tmp_path):
    proc = _proc("p1", {"Access Key": "AK", "Secret Key": "SK"})
    parent_file = _write_flow(tmp_path, "parent.json", _flow("pg-parent"))
    child_file = _write_flow(tmp_path, "child.json", _flow("pg-child", processors=[proc]))
    pg = _pg()
    fix_s3_credentials(
        proc, pg, {},
        parent_pg_path=str(parent_file),
        child_pg_path=str(child_file),
    )
    parent_result = load_json(parent_file)
    assert len(parent_result["flowContents"]["controllerServices"]) == 1
    svc_id = parent_result["flowContents"]["controllerServices"][0]["identifier"]
    child_result = load_json(child_file)
    assert svc_id in child_result.get("externalControllerServices", {})
    proc_on_disk = child_result["flowContents"]["processors"][0]
    assert proc_on_disk["properties"].get("AWS Credentials Provider service") == svc_id


def test_fix_s3_credentials_no_creds_cross_file(tmp_path):
    proc = _proc("p1", {"Bucket": "my-bucket"})
    parent_file = _write_flow(tmp_path, "parent.json", _flow("pg-parent"))
    child_file = _write_flow(tmp_path, "child.json", _flow("pg-child", processors=[proc]))
    pg = _pg()
    msgs, svc_id = fix_s3_credentials(
        proc, pg, {},
        parent_pg_path=str(parent_file),
        child_pg_path=str(child_file),
    )
    assert msgs == []
    assert svc_id == ""
    parent_result = load_json(parent_file)
    assert len(parent_result["flowContents"]["controllerServices"]) == 0


# ---------------------------------------------------------------------------
# fix_convert_json_to_sql
# ---------------------------------------------------------------------------

def _sql_pg(proc_id: str, putsql_id: str, extra_putsql_props: dict | None = None) -> dict:
    convert_proc = _proc(proc_id, {"JDBC Connection Pool": "pool-svc", "Table Name": "MY_TABLE"},
                         proc_type="org.apache.nifi.processors.standard.ConvertJSONToSQL")
    putsql_proc = _proc(putsql_id, extra_putsql_props or {}, proc_type="org.apache.nifi.processors.jdbc.PutSQL")
    sql_conn = {
        "identifier": "conn-1",
        "source": {"id": proc_id, "name": "Convert"},
        "destination": {"id": putsql_id, "name": "PutSQL"},
        "selectedRelationships": ["sql"],
    }
    pg = _pg()
    pg["processors"] = [convert_proc, putsql_proc]
    pg["connections"] = [sql_conn]
    return pg, convert_proc, putsql_proc


def test_fix_convert_json_to_sql_happy():
    pg, convert_proc, _ = _sql_pg("c1", "s1")
    msgs, reader_id = fix_convert_json_to_sql(convert_proc, pg, {})
    assert any("[FIXED]" in m for m in msgs)
    assert convert_proc["type"].endswith("PutDatabaseRecord")
    assert reader_id


def test_fix_convert_json_to_sql_creates_reader_service():
    pg, convert_proc, _ = _sql_pg("c1", "s1")
    root_pg = _pg("root")
    _, reader_id = fix_convert_json_to_sql(convert_proc, pg, {}, root_pg=root_pg)
    assert any(svc["identifier"] == reader_id for svc in root_pg["controllerServices"])


def test_fix_convert_json_to_sql_reuses_existing_reader():
    pg, convert_proc, _ = _sql_pg("c1", "s1")
    root_pg = _pg("root")
    _, reader_id = fix_convert_json_to_sql(convert_proc, pg, {}, root_pg=root_pg, existing_reader_id="existing-reader-id")
    assert reader_id == "existing-reader-id"
    assert root_pg["controllerServices"] == []  # no new service created


def test_fix_convert_json_to_sql_no_sql_connection():
    convert_proc = _proc("c1", {"JDBC Connection Pool": "pool"},
                         proc_type="org.apache.nifi.processors.standard.ConvertJSONToSQL")
    pg = _pg()
    pg["processors"] = [convert_proc]
    msgs, reader_id = fix_convert_json_to_sql(convert_proc, pg, {})
    assert reader_id == ""
    assert any("[MANUAL]" in m for m in msgs)


def test_fix_convert_json_to_sql_downstream_not_putsql():
    convert_proc = _proc("c1", {"JDBC Connection Pool": "pool"},
                         proc_type="org.apache.nifi.processors.standard.ConvertJSONToSQL")
    other_proc = _proc("o1", {}, proc_type="org.apache.nifi.processors.standard.PutFile")
    sql_conn = {
        "identifier": "conn-1",
        "source": {"id": "c1", "name": "Convert"},
        "destination": {"id": "o1", "name": "Other"},
        "selectedRelationships": ["sql"],
    }
    pg = _pg()
    pg["processors"] = [convert_proc, other_proc]
    pg["connections"] = [sql_conn]
    msgs, reader_id = fix_convert_json_to_sql(convert_proc, pg, {})
    assert reader_id == ""
    assert any("[MANUAL]" in m for m in msgs)


def test_fix_convert_json_to_sql_obtain_generated_keys_warning():
    pg, convert_proc, _ = _sql_pg("c1", "s1", extra_putsql_props={"Obtain Generated Keys": "true"})
    msgs, _ = fix_convert_json_to_sql(convert_proc, pg, {})
    assert any("Obtain Generated Keys" in m for m in msgs)


def test_fix_convert_json_to_sql_rewires_connections():
    pg, convert_proc, putsql_proc = _sql_pg("c1", "s1")
    # Add a downstream connection from PutSQL
    downstream_conn = {
        "identifier": "conn-2",
        "source": {"id": "s1", "name": "PutSQL"},
        "destination": {"id": "next-proc", "name": "Next"},
        "selectedRelationships": ["success"],
    }
    pg["connections"].append(downstream_conn)
    fix_convert_json_to_sql(convert_proc, pg, {})
    # The downstream connection source should now point to the ConvertJSONToSQL proc
    rewired = next((c for c in pg["connections"] if c["identifier"] == "conn-2"), None)
    assert rewired is not None
    assert rewired["source"]["id"] == "c1"


# ---------------------------------------------------------------------------
# fix_type_rename
# ---------------------------------------------------------------------------

def test_fix_type_rename_kafka():
    proc = _proc("p1", {}, proc_type="org.apache.nifi.processors.kafka.pubsub.ConsumeKafka_2_0")
    proc["bundle"] = {"group": "org.apache.nifi", "artifact": "nifi-kafka-2-0-nar", "version": "1.28.1"}
    applied, manual = fix_type_rename(proc, _pg(), {})
    assert applied
    assert "ConsumeKafka_2_6" in proc["type"]
    assert proc["bundle"]["artifact"] == "nifi-kafka-2-6-nar"


def test_fix_type_rename_azure_put_blob():
    proc = _proc("p1", {"blob": "myblob", "storage-account-name": None, "storage-account-key": None},
                 proc_type="org.apache.nifi.processors.azure.storage.PutAzureBlobStorage")
    proc["propertyDescriptors"] = {"blob": {"name": "blob"}}
    applied, manual = fix_type_rename(proc, _pg(), {})
    assert applied
    assert "PutAzureBlobStorage_v12" in proc["type"]


def test_fix_type_rename_azure_credential_prop_dropped_manual_warning():
    proc = _proc("p1", {"blob": "myblob", "storage-account-key": "secret123"},
                 proc_type="org.apache.nifi.processors.azure.storage.PutAzureBlobStorage")
    proc["propertyDescriptors"] = {}
    applied, manual = fix_type_rename(proc, _pg(), {})
    # storage-account-key had a value and is dropped -> manual warning expected
    assert any("storage-account-key" in m for m in manual)


def test_fix_type_rename_already_v12():
    proc = _proc("p1", {}, proc_type="org.example.PutAzureBlobStorage_v12")
    proc["propertyDescriptors"] = {}
    applied, manual = fix_type_rename(proc, _pg(), {})
    assert applied == []
    assert manual == []


def test_fix_type_rename_property_descriptors_updated():
    proc = _proc("p1", {"blob": "myblob"},
                 proc_type="org.apache.nifi.processors.azure.storage.PutAzureBlobStorage")
    proc["propertyDescriptors"] = {"blob": {"name": "blob", "displayName": "Blob"}}
    fix_type_rename(proc, _pg(), {})
    assert "blob-name" in proc["propertyDescriptors"]
    assert "blob" not in proc["propertyDescriptors"]


# ---------------------------------------------------------------------------
# upgrade_azure_credentials_service
# ---------------------------------------------------------------------------

def _flow_with_azure_cred_svc(props: dict) -> dict:
    svc = {
        "identifier": "svc-1",
        "name": "AzureCreds",
        "type": "org.apache.nifi.services.azure.storage.AzureStorageCredentialsControllerService",
        "properties": props,
        "controllerServiceApis": [
            {"type": "org.apache.nifi.services.azure.storage.AzureStorageCredentialsService"}
        ],
    }
    return {"identifier": "root", "controllerServices": [svc], "processGroups": []}


def test_upgrade_azure_credentials_service_account_key():
    fc = _flow_with_azure_cred_svc({"storage-account-key": "mykey"})
    applied, manual = upgrade_azure_credentials_service(fc)
    svc = fc["controllerServices"][0]
    assert "_v12" in svc["type"]
    assert svc["properties"].get("credentials-type") == "ACCOUNT_KEY"
    assert not manual


def test_upgrade_azure_credentials_service_sas_token():
    fc = _flow_with_azure_cred_svc({"storage-sas-token": "mytoken"})
    applied, manual = upgrade_azure_credentials_service(fc)
    svc = fc["controllerServices"][0]
    assert svc["properties"].get("credentials-type") == "SAS_TOKEN"
    assert not manual


def test_upgrade_azure_credentials_service_both_ambiguous():
    fc = _flow_with_azure_cred_svc({"storage-account-key": "k", "storage-sas-token": "t"})
    applied, manual = upgrade_azure_credentials_service(fc)
    assert manual
    assert "credentials-type" not in fc["controllerServices"][0]["properties"]


def test_upgrade_azure_credentials_service_neither():
    fc = _flow_with_azure_cred_svc({})
    applied, manual = upgrade_azure_credentials_service(fc)
    assert manual


def test_upgrade_azure_credentials_service_already_v12():
    svc = {
        "identifier": "s1", "name": "AzureCreds",
        "type": "org.example.AzureStorageCredentialsControllerService_v12",
        "properties": {}, "controllerServiceApis": [],
    }
    fc = {"identifier": "root", "controllerServices": [svc], "processGroups": []}
    applied, manual = upgrade_azure_credentials_service(fc)
    assert applied == []
    assert manual == []


def test_upgrade_azure_credentials_service_api_type_updated():
    fc = _flow_with_azure_cred_svc({"storage-account-key": "k"})
    upgrade_azure_credentials_service(fc)
    api_type = fc["controllerServices"][0]["controllerServiceApis"][0]["type"]
    assert "_v12" in api_type


# ---------------------------------------------------------------------------
# upgrade_prometheus_record_sink
# ---------------------------------------------------------------------------

def _flow_with_prometheus_svc(props: dict) -> dict:
    svc = {
        "identifier": "prom-1",
        "name": "PromSink",
        "type": "org.apache.nifi.reporting.prometheus.PrometheusRecordSink",
        "properties": props,
    }
    return {"identifier": "root", "controllerServices": [svc], "processGroups": []}


def test_upgrade_prometheus_record_sink_happy():
    fc = _flow_with_prometheus_svc({"prometheus-reporting-task-metrics-endpoint-port": "9092"})
    applied, manual = upgrade_prometheus_record_sink(fc)
    svc = fc["controllerServices"][0]
    assert "qubership" in svc["type"].lower()
    assert "prometheus-sink-metrics-endpoint-port" in svc["properties"]
    assert applied


def test_upgrade_prometheus_record_sink_ssl_manual_warning():
    fc = _flow_with_prometheus_svc({"prometheus-reporting-task-ssl-context": "ssl-svc-id"})
    applied, manual = upgrade_prometheus_record_sink(fc)
    assert any("SSL" in m for m in manual)


def test_upgrade_prometheus_record_sink_already_qubership():
    svc = {
        "identifier": "s1", "name": "QS",
        "type": "org.qubership.nifi.service.QubershipPrometheusRecordSink",
        "properties": {},
    }
    fc = {"identifier": "root", "controllerServices": [svc], "processGroups": []}
    applied, manual = upgrade_prometheus_record_sink(fc)
    assert applied == []
    assert manual == []


def test_upgrade_prometheus_record_sink_custom_overrides():
    fc = _flow_with_prometheus_svc({"old-port-prop": "9999"})
    applied, manual = upgrade_prometheus_record_sink(
        fc,
        new_type="org.custom.CustomSink",
        new_bundle={"group": "org.custom", "artifact": "custom-nar", "version": "1.0.0"},
        prop_map={"old-port-prop": "new-port-prop"},
    )
    svc = fc["controllerServices"][0]
    assert svc["type"] == "org.custom.CustomSink"
    assert "new-port-prop" in svc["properties"]


# ---------------------------------------------------------------------------
# _classify_row
# ---------------------------------------------------------------------------

def _row(issue: str, level: str = "WARNING") -> dict:
    return {"Issue": issue, "Level": level, "Processor": "", "Process Group": "", "Flow name": "f"}


def test_classify_row_error_level():
    assert _classify_row(_row("anything", level="ERROR")) == "manual"


def test_classify_row_invokehttp_proxy():
    assert _classify_row(_row("proxy properties in InvokeHTTP")) == "fix_invokehttp_proxy"


def test_classify_row_kafka():
    assert _classify_row(_row("ConsumeKafka_2_0 is deprecated")) == "fix_type_rename"


def test_classify_row_azure_blob():
    assert _classify_row(_row("PutAzureBlobStorage not supported")) == "fix_type_rename"


def test_classify_row_variables():
    assert _classify_row(_row("variables are not available in NiFi 2")) == "fix_variables"


def test_classify_row_s3_access_key():
    assert _classify_row(_row("Access Key ID should not be hardcoded")) == "fix_s3_credentials"


def test_classify_row_convertjsontosql():
    assert _classify_row(_row("ConvertJSONToSQL is removed")) == "fix_convert_json_to_sql"


def test_classify_row_script_engine_python():
    assert _classify_row(_row("Script engine = python is not supported")) == "fix_script_engine"


# ---------------------------------------------------------------------------
# apply_csv_transforms  (orchestration path)
# ---------------------------------------------------------------------------

def _csv_row(flow_name: str, issue: str, proc: str = "", level: str = "WARNING") -> dict:
    return {"Flow name": flow_name, "Processor": proc, "Process Group": "", "Issue": issue, "Level": level, "Solution": "fix it"}


PROC_UUID = "aaaabbbb-0000-0000-0000-000000000001"


def test_apply_csv_transforms_auto_fix_applied(tmp_path):
    proc = {
        "identifier": PROC_UUID, "instanceIdentifier": PROC_UUID,
        "name": "InvokeHTTP", "type": "org.apache.nifi.processors.standard.InvokeHTTP",
        "properties": {"Proxy Host": "proxy.example.com"},
        "propertyDescriptors": {}, "autoTerminatedRelationships": [],
    }
    flow = _flow("root", processors=[proc])
    _write_flow(tmp_path, "flow.json", flow)
    csv_path = _write_csv(tmp_path, [
        _csv_row("flow.json", "proxy properties in InvokeHTTP", proc=f"InvokeHTTP ({PROC_UUID})")
    ])
    apply_csv_transforms(csv_path, str(tmp_path))
    result = load_json(tmp_path / "flow.json")
    assert len(result["flowContents"]["controllerServices"]) == 1
    # summary output should contain [FIXED] line
    # (re-read capsys after the call above was not captured; use a separate capsys-capturing test below)


def test_apply_csv_transforms_summary_output(tmp_path, capsys):
    proc = {
        "identifier": PROC_UUID, "instanceIdentifier": PROC_UUID,
        "name": "InvokeHTTP", "type": "org.apache.nifi.processors.standard.InvokeHTTP",
        "properties": {"Proxy Host": "proxy.example.com"},
        "propertyDescriptors": {}, "autoTerminatedRelationships": [],
    }
    flow = _flow("root", processors=[proc])
    _write_flow(tmp_path, "flow.json", flow)
    csv_path = _write_csv(tmp_path, [
        _csv_row("flow.json", "proxy properties in InvokeHTTP", proc=f"InvokeHTTP ({PROC_UUID})"),
        _csv_row("flow.json", "unsupported feature", level="ERROR"),
    ])
    apply_csv_transforms(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    assert "[FIXED]" in out
    assert "[MANUAL]" in out


def test_apply_csv_transforms_manual_row_no_mutation(tmp_path, capsys):
    proc = _proc("p1", {})
    flow = _flow("root", processors=[proc])
    _write_flow(tmp_path, "flow.json", flow)
    csv_path = _write_csv(tmp_path, [
        _csv_row("flow.json", "something unsupported", level="ERROR")
    ])
    apply_csv_transforms(csv_path, str(tmp_path))
    # File should not be modified (no dirty flag set for manual rows)
    result = load_json(tmp_path / "flow.json")
    assert result["flowContents"]["processors"][0]["type"] == proc["type"]
    out = capsys.readouterr().out
    assert "[MANUAL]" in out


def test_apply_csv_transforms_per_file_caching(tmp_path):
    proc = _proc("p1", {"Proxy Host": "h"})
    flow = _flow("root", processors=[proc])
    _write_flow(tmp_path, "flow.json", flow)
    csv_path = _write_csv(tmp_path, [
        _csv_row("flow.json", "proxy properties in InvokeHTTP", proc="P (p1)"),
        _csv_row("flow.json", "proxy properties in InvokeHTTP", proc="P (p1)"),
    ])
    call_count = {"n": 0}
    original_load = __import__("utils").load_json

    def counting_load(path):
        call_count["n"] += 1
        return original_load(path)

    with patch("fixes.load_json", side_effect=counting_load):
        apply_csv_transforms(csv_path, str(tmp_path))

    assert call_count["n"] == 1


def test_apply_csv_transforms_azure_prepass(tmp_path):
    svc = {
        "identifier": "az-svc", "name": "AzureCreds",
        "type": "org.apache.nifi.services.azure.storage.AzureStorageCredentialsControllerService",
        "properties": {"storage-account-key": "mykey"},
        "controllerServiceApis": [],
    }
    flow = _flow("root", controllerServices=[svc])
    _write_flow(tmp_path, "flow.json", flow)
    csv_path = _write_csv(tmp_path, [
        _csv_row("flow.json", "some other warning")
    ])
    apply_csv_transforms(csv_path, str(tmp_path))
    result = load_json(tmp_path / "flow.json")
    assert "_v12" in result["flowContents"]["controllerServices"][0]["type"]


def test_apply_csv_transforms_prometheus_prepass(tmp_path):
    svc = {
        "identifier": "prom-1", "name": "PromSink",
        "type": "org.apache.nifi.reporting.prometheus.PrometheusRecordSink",
        "properties": {},
    }
    flow = _flow("root", controllerServices=[svc])
    _write_flow(tmp_path, "flow.json", flow)
    csv_path = _write_csv(tmp_path, [_csv_row("flow.json", "some issue")])
    apply_csv_transforms(csv_path, str(tmp_path), prometheus_params={})
    result = load_json(tmp_path / "flow.json")
    assert "qubership" in result["flowContents"]["controllerServices"][0]["type"].lower()


C1_UUID = "ccccaaaa-0000-0000-0000-000000000001"
S1_UUID = "ddddbbbb-0000-0000-0000-000000000001"
C2_UUID = "ccccaaaa-0000-0000-0000-000000000002"
S2_UUID = "ddddbbbb-0000-0000-0000-000000000002"


def test_apply_csv_transforms_reader_reuse(tmp_path):
    """Two ConvertJSONToSQL rows in same file -> reader service created once."""
    def _make_convert(proc_id: str, putsql_id: str) -> tuple:
        convert = {
            "identifier": proc_id, "instanceIdentifier": proc_id,
            "name": "Convert", "type": "org.apache.nifi.processors.standard.ConvertJSONToSQL",
            "properties": {"JDBC Connection Pool": "pool"},
            "propertyDescriptors": {}, "autoTerminatedRelationships": [],
        }
        putsql = {
            "identifier": putsql_id, "instanceIdentifier": putsql_id,
            "name": "PutSQL", "type": "org.apache.nifi.processors.jdbc.PutSQL",
            "properties": {}, "propertyDescriptors": {}, "autoTerminatedRelationships": [],
        }
        conn = {
            "identifier": f"conn-{proc_id}",
            "source": {"id": proc_id, "name": "Convert", "instanceIdentifier": proc_id},
            "destination": {"id": putsql_id, "name": "PutSQL"},
            "selectedRelationships": ["sql"],
        }
        return convert, putsql, conn

    c1, s1, conn1 = _make_convert(C1_UUID, S1_UUID)
    c2, s2, conn2 = _make_convert(C2_UUID, S2_UUID)
    flow = _flow("root", processors=[c1, s1, c2, s2], connections=[conn1, conn2])
    _write_flow(tmp_path, "flow.json", flow)
    csv_path = _write_csv(tmp_path, [
        _csv_row("flow.json", "ConvertJSONToSQL is removed", proc=f"Convert ({C1_UUID})"),
        _csv_row("flow.json", "ConvertJSONToSQL is removed", proc=f"Convert ({C2_UUID})"),
    ])
    apply_csv_transforms(csv_path, str(tmp_path))
    result = load_json(tmp_path / "flow.json")
    reader_services = [
        svc for svc in result["flowContents"]["controllerServices"]
        if "JsonTreeReader" in svc.get("type", "")
    ]
    assert len(reader_services) == 1


def test_apply_csv_transforms_missing_proc_uuid(tmp_path, capsys):
    flow = _flow("root")
    _write_flow(tmp_path, "flow.json", flow)
    csv_path = _write_csv(tmp_path, [
        _csv_row("flow.json", "proxy properties in InvokeHTTP", proc="InvokeHTTP")  # no (uuid)
    ])
    apply_csv_transforms(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    assert "WARN" in out


NONEXISTENT_UUID = "ffffffff-0000-0000-0000-000000000099"


def test_apply_csv_transforms_proc_uuid_not_found(tmp_path, capsys):
    flow = _flow("root")
    _write_flow(tmp_path, "flow.json", flow)
    csv_path = _write_csv(tmp_path, [
        _csv_row("flow.json", "proxy properties in InvokeHTTP", proc=f"InvokeHTTP ({NONEXISTENT_UUID})")
    ])
    apply_csv_transforms(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    assert "WARN" in out
    # File should be unchanged: no processors mutated, no services added
    result = load_json(tmp_path / "flow.json")
    assert result["flowContents"]["processors"] == []
    assert result["flowContents"]["controllerServices"] == []


def test_apply_csv_transforms_missing_flow_file(tmp_path, capsys):
    # One missing flow, one real flow — only the real file should be processed
    proc = {
        "identifier": PROC_UUID, "instanceIdentifier": PROC_UUID,
        "name": "InvokeHTTP", "type": "org.apache.nifi.processors.standard.InvokeHTTP",
        "properties": {"Proxy Host": "proxy.example.com"},
        "propertyDescriptors": {}, "autoTerminatedRelationships": [],
    }
    _write_flow(tmp_path, "real.json", _flow("root", processors=[proc]))
    csv_path = _write_csv(tmp_path, [
        _csv_row("nonexistent.json", "proxy properties in InvokeHTTP"),
        _csv_row("real.json", "proxy properties in InvokeHTTP", proc=f"InvokeHTTP ({PROC_UUID})"),
    ])
    apply_csv_transforms(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    assert "WARN" in out or "not found" in out.lower()
    # Real file was still processed
    result = load_json(tmp_path / "real.json")
    assert len(result["flowContents"]["controllerServices"]) == 1


def test_apply_csv_transforms_script_engine_ai_agent(tmp_path, capsys):
    flow = _flow("root")
    _write_flow(tmp_path, "flow.json", flow)
    csv_path = _write_csv(tmp_path, [
        _csv_row("flow.json", "Script engine = python is not supported")
    ])
    apply_csv_transforms(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    assert "AI Agent" in out


P1_UUID = "eeeeaaaa-0000-0000-0000-000000000001"
P2_UUID = "eeeeaaaa-0000-0000-0000-000000000002"
S3_PROC_UUID = "ffff1111-0000-0000-0000-000000000001"


def test_apply_csv_transforms_invokehttp_cross_file(tmp_path):
    proc = {
        "identifier": PROC_UUID, "instanceIdentifier": PROC_UUID,
        "name": "InvokeHTTP", "type": "org.apache.nifi.processors.standard.InvokeHTTP",
        "properties": {"Proxy Host": "proxy.example.com"},
        "propertyDescriptors": {}, "autoTerminatedRelationships": [],
    }
    parent_file = _write_flow(tmp_path, "parent.json", _flow("pg-parent"))
    child_file = _write_flow(tmp_path, "child.json", _flow("pg-child", processors=[proc]))
    csv_path = _write_csv(tmp_path, [
        _csv_row("child.json", "proxy properties in InvokeHTTP", proc=f"InvokeHTTP ({PROC_UUID})")
    ])
    apply_csv_transforms(
        csv_path, str(tmp_path),
        invokehttp_cross_file={
            PROC_UUID: {
                "parent_pg_path": str(parent_file),
                "child_pg_path": str(child_file),
            }
        },
    )
    parent_result = load_json(parent_file)
    assert len(parent_result["flowContents"]["controllerServices"]) == 1
    child_result = load_json(child_file)
    svc_id = parent_result["flowContents"]["controllerServices"][0]["identifier"]
    assert svc_id in child_result.get("externalControllerServices", {})


def test_apply_csv_transforms_proxy_group_cache(tmp_path):
    def _invoke_proc(pid):
        return {
            "identifier": pid, "instanceIdentifier": pid,
            "name": f"InvokeHTTP-{pid[:4]}", "type": "org.apache.nifi.processors.standard.InvokeHTTP",
            "properties": {"Proxy Host": "proxy.example.com"},
            "propertyDescriptors": {}, "autoTerminatedRelationships": [],
        }

    parent_file = _write_flow(tmp_path, "parent.json", _flow("pg-parent"))
    child_file = _write_flow(tmp_path, "child.json", _flow("pg-child", processors=[
        _invoke_proc(P1_UUID), _invoke_proc(P2_UUID),
    ]))
    csv_path = _write_csv(tmp_path, [
        _csv_row("child.json", "proxy properties in InvokeHTTP", proc=f"P1 ({P1_UUID})"),
        _csv_row("child.json", "proxy properties in InvokeHTTP", proc=f"P2 ({P2_UUID})"),
    ])
    cross = {
        P1_UUID: {"group_key": "shared-pg", "parent_pg_path": str(parent_file), "child_pg_path": str(child_file)},
        P2_UUID: {"group_key": "shared-pg", "parent_pg_path": str(parent_file), "child_pg_path": str(child_file)},
    }
    apply_csv_transforms(csv_path, str(tmp_path), invokehttp_cross_file=cross)
    parent_result = load_json(parent_file)
    assert len(parent_result["flowContents"]["controllerServices"]) == 1


def test_apply_csv_transforms_s3_manual_message_routing(tmp_path, capsys):
    proc = {
        "identifier": S3_PROC_UUID, "instanceIdentifier": S3_PROC_UUID,
        "name": "PutS3Object", "type": "org.apache.nifi.processors.aws.s3.PutS3Object",
        "properties": {"Bucket": "my-bucket", "Access Key": "", "Secret Key": ""},
        "propertyDescriptors": {}, "autoTerminatedRelationships": [],
    }
    _write_flow(tmp_path, "flow.json", _flow("root", processors=[proc]))
    csv_path = _write_csv(tmp_path, [
        _csv_row("flow.json", "Access Key ID should be moved to AWSCredentialsProvider",
                 proc=f"PutS3Object ({S3_PROC_UUID})")
    ])
    apply_csv_transforms(csv_path, str(tmp_path))
    out = capsys.readouterr().out
    applied_section = out.split("=== Applied Changes ===")[1].split("===")[0]
    manual_section = out.split("=== Manual Action Required ===")[1].split("===")[0]
    assert "[MANUAL]" not in applied_section
    assert "[MANUAL]" in manual_section


# ---------------------------------------------------------------------------
# Standalone controller-service file tests (REST API format)
# ---------------------------------------------------------------------------

def _write_standalone_service(tmp_path: Path, rel_path: str, component: dict) -> Path:
    """Write a NiFi REST API format controller-service file."""
    svc_file = tmp_path / rel_path
    svc_file.parent.mkdir(parents=True, exist_ok=True)
    svc_file.write_text(json.dumps({"component": component}))
    return svc_file


def test_apply_csv_transforms_standalone_azure_credentials(tmp_path, capsys):
    component = {
        "name": "CommonAzureStorageCredentialsControllerService",
        "type": "org.apache.nifi.services.azure.storage.AzureStorageCredentialsControllerService",
        "bundle": {"group": "org.apache.nifi", "artifact": "nifi-azure-nar", "version": "1.28.1"},
        "controllerServiceApis": [
            {"type": "org.apache.nifi.services.azure.storage.AzureStorageCredentialsService",
             "bundle": {"group": "org.apache.nifi", "artifact": "nifi-azure-services-api-nar", "version": "1.28.1"}}
        ],
        "properties": {"storage-account-key": "key123", "storage-sas-token": None},
    }
    svc_file = _write_standalone_service(tmp_path, "controller-services/MyAzureSvc.json", component)
    csv_path = _write_csv(tmp_path, [
        _csv_row(
            "controller-services/MyAzureSvc.json",
            "The AzureStorageCredentialsControllerService Controller Service is not available in Apache NiFi 2.x.",
            proc="AzureStorageCredentialsControllerService ()",
        )
    ])
    apply_csv_transforms(csv_path, str(tmp_path))

    result = json.loads(svc_file.read_text())
    comp = result["component"]
    assert comp["type"].endswith("AzureStorageCredentialsControllerService_v12")
    assert comp["controllerServiceApis"][0]["type"].endswith("AzureStorageCredentialsService_v12")
    assert comp["properties"]["credentials-type"] == "ACCOUNT_KEY"

    out = capsys.readouterr().out
    assert "[FIXED]" in out
    assert "[MANUAL]" not in out.split("=== Manual Action Required ===")[1].split("===")[0]


def test_apply_csv_transforms_standalone_prometheus(tmp_path, capsys):
    component = {
        "name": "CommonPrometheusRecordSink",
        "type": "org.apache.nifi.reporting.prometheus.PrometheusRecordSink",
        "bundle": {"group": "org.apache.nifi", "artifact": "nifi-prometheus-nar", "version": "1.28.1"},
        "properties": {
            "prometheus-reporting-task-metrics-endpoint-port": "19192",
            "prometheus-reporting-task-instance-id": "${hostname(true)}",
            "prometheus-reporting-task-client-auth": "No Authentication",
        },
    }
    svc_file = _write_standalone_service(tmp_path, "controller-services/MyPromSvc.json", component)
    csv_path = _write_csv(tmp_path, [
        _csv_row(
            "controller-services/MyPromSvc.json",
            "The PrometheusRecordSink Controller Service is not available in Apache NiFi 2.x.",
            proc="CommonPrometheusRecordSink ()",
        )
    ])
    apply_csv_transforms(csv_path, str(tmp_path))

    result = json.loads(svc_file.read_text())
    comp = result["component"]
    assert "qubership" in comp["type"].lower()
    assert "prometheus-sink-metrics-endpoint-port" in comp["properties"]
    assert comp["properties"]["prometheus-sink-metrics-endpoint-port"] == "19192"
    assert "prometheus-sink-instance-id" in comp["properties"]
    assert "prometheus-reporting-task-metrics-endpoint-port" not in comp["properties"]
    assert "prometheus-reporting-task-client-auth" not in comp["properties"]

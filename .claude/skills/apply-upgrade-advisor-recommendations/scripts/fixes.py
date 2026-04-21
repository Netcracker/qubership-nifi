"""
fixes.py  —  Individual fix handlers, rename tables, and the main
              apply_csv_transforms entry point.
"""

import re
from collections import defaultdict
from pathlib import Path

from utils import load_json, save_json, new_uuid, find_component, parse_csv, _make_service


# ---------------------------------------------------------------------------
# Individual fix handlers  (called by apply_csv_transforms)
# ---------------------------------------------------------------------------

def fix_invokehttp_proxy(proc: dict, pg: dict, row: dict) -> list[str]:
    """
    Create a StandardProxyConfigurationService from InvokeHTTP Proxy* properties,
    reference it from the processor, remove the old inline properties.
    """
    prop_map = {
        "Proxy Host":     "proxy-configuration-service.proxy-server-host",
        "Proxy Port":     "proxy-configuration-service.proxy-server-port",
        "Proxy Type":     "proxy-configuration-service.proxy-type",
        "Proxy Username": "proxy-configuration-service.proxy-user-name",
        "Proxy Password": "proxy-configuration-service.proxy-user-password",
    }
    props = proc.get("properties", {})
    svc_props = {}
    removed_keys = []
    for old_key, new_key in prop_map.items():
        if old_key in props:
            svc_props[new_key] = props.pop(old_key)
            removed_keys.append(old_key)

    if not removed_keys:
        return []

    svc = _make_service(
        name="ProxyConfigurationService",
        svc_type="org.apache.nifi.proxy.StandardProxyConfigurationService",
        bundle={
            "group": "org.apache.nifi",
            "artifact": "nifi-standard-services-api-nar",
            "version": "1.28.1",
        },
        properties=svc_props,
    )
    pg.setdefault("controllerServices", []).append(svc)
    props["Proxy Configuration Service"] = svc["identifier"]

    proc_label = f"{proc.get('name', '?')} ({proc.get('identifier', '?')})"
    return [
        f"[FIXED] {proc_label} — Proxy properties migrated to new "
        f"StandardProxyConfigurationService ({svc['identifier']}); "
        f"removed: {', '.join(removed_keys)}"
    ]


def fix_s3_credentials(proc: dict, pg: dict, row: dict) -> list[str]:
    """
    Move hardcoded Access Key ID / Secret Access Key into a new
    AWSCredentialsProviderControllerService.
    """
    props = proc.get("properties", {})
    access_key = props.pop("Access Key ID", None)
    secret_key = props.pop("Secret Access Key", None)

    if not access_key and not secret_key:
        return []

    svc_props = {}
    if access_key:
        svc_props["Access Key ID"] = access_key
    if secret_key:
        svc_props["Secret Access Key"] = secret_key

    svc = _make_service(
        name="AWSCredentialsProviderService",
        svc_type=(
            "org.apache.nifi.processors.aws.credentials.provider.service"
            ".AWSCredentialsProviderControllerService"
        ),
        bundle={
            "group": "org.apache.nifi",
            "artifact": "nifi-aws-nar",
            "version": "1.28.1",
        },
        properties=svc_props,
    )
    pg.setdefault("controllerServices", []).append(svc)
    props["AWS Credentials Provider service"] = svc["identifier"]

    proc_label = f"{proc.get('name', '?')} ({proc.get('identifier', '?')})"
    return [
        f"[FIXED] {proc_label} — AWS credentials moved to new "
        f"AWSCredentialsProviderControllerService ({svc['identifier']})"
    ]


def fix_convert_json_to_sql(proc: dict, pg: dict, row: dict) -> list[str]:
    """
    Replace ConvertJSONToSQL with PutDatabaseRecord + a new JsonTreeReader service.
    """
    props = proc.get("properties", {})

    # Create JsonTreeReader service
    svc = _make_service(
        name="JsonTreeReader",
        svc_type="org.apache.nifi.json.JsonTreeReader",
        bundle={
            "group": "org.apache.nifi",
            "artifact": "nifi-record-serialization-services-nar",
            "version": "1.28.1",
        },
        properties={},
    )
    pg.setdefault("controllerServices", []).append(svc)

    # Property rename map
    rename = {
        "JDBC Connection Pool": "Database Connection Pooling Service",
    }
    # Keys to drop
    drop = {
        "Update Keys",
        "jts-quoted-identifiers",
        "jts-quoted-table-identifiers",
        "jts-sql-param-attr-prefix",
        "table-schema-cache-size",
    }
    # Keys that carry over as-is
    keep = {
        "Statement Type",
        "Table Name",
        "Catalog Name",
        "Schema Name",
        "Translate Field Names",
        "Unmatched Field Behavior",
        "Unmatched Column Behavior",
    }

    new_props = {}
    for k, v in list(props.items()):
        if k in drop:
            continue
        if k in rename:
            new_props[rename[k]] = v
        elif k in keep:
            new_props[k] = v
        # else: drop unknown props silently

    new_props["Record Reader"] = svc["identifier"]
    proc["properties"] = new_props

    old_type = proc.get("type", "")
    proc["type"] = "org.apache.nifi.processors.standard.PutDatabaseRecord"

    proc_label = f"{proc.get('name', '?')} ({proc.get('identifier', '?')})"
    return [
        f"[FIXED] {proc_label} — ConvertJSONToSQL -> PutDatabaseRecord; "
        f"JsonTreeReader service created ({svc['identifier']}). "
        f"Old type: {old_type}"
    ]


# ---------------------------------------------------------------------------
# Azure and Kafka rename tables
# ---------------------------------------------------------------------------

# Format: { "OldProcessorSimpleName": (new_simple_name, {old_prop: new_prop | None}) }
# None means remove the property.
AZURE_RENAME_TABLE: dict[str, tuple[str, dict]] = {
    "GetAzureQueueStorage": (
        "GetAzureQueueStorage_v12",
        {
            "storage-queue-name":        "Queue Name",
            "auto-delete-messages":      "Auto Delete Messages",
            "batch-size":                "Message Batch Size",
            "visibility-timeout":        "Visibility Timeout",
            "storage-credentials-service": None,
            "storage-account-name":      None,
            "storage-account-key":       None,
            "storage-sas-token":         None,
        },
    ),
    "PutAzureQueueStorage": (
        "PutAzureQueueStorage_v12",
        {
            "storage-queue-name":        "Queue Name",
            "time-to-live":              "Message Time To Live",
            "visibility-delay":          "Visibility Timeout",
            "storage-credentials-service": "Credentials Service",
            "storage-account-name":      None,
            "storage-account-key":       None,
            "storage-sas-token":         None,
        },
    ),
    "DeleteAzureBlobStorage": (
        "DeleteAzureBlobStorage_v12",
        {
            "blob":                      "blob-name",
            "storage-account-name":      None,
            "storage-account-key":       None,
            "storage-sas-token":         None,
            "storage-endpoint-suffix":   None,
        },
    ),
    "FetchAzureBlobStorage": (
        "FetchAzureBlobStorage_v12",
        {
            "blob":                      "blob-name",
            "cse-key-type":              "Client-Side Encryption Key Type",
            "cse-key-id":                "Client-Side Encryption Key ID",
            "cse-symmetric-key-hex":     "Client-Side Encryption Local Key",
            "storage-account-name":      None,
            "storage-account-key":       None,
            "storage-sas-token":         None,
            "storage-endpoint-suffix":   None,
        },
    ),
    "ListAzureBlobStorage": (
        "ListAzureBlobStorage_v12",
        {
            "prefix":                    "blob-name-prefix",
            "storage-account-name":      None,
            "storage-account-key":       None,
            "storage-sas-token":         None,
            "storage-endpoint-suffix":   None,
        },
    ),
    "PutAzureBlobStorage": (
        "PutAzureBlobStorage_v12",
        {
            "blob":                      "blob-name",
            "azure-create-container":    "create-container",
            "cse-key-type":              "Client-Side Encryption Key Type",
            "cse-key-id":                "Client-Side Encryption Key ID",
            "cse-symmetric-key-hex":     "Client-Side Encryption Local Key",
            "storage-account-name":      None,
            "storage-account-key":       None,
            "storage-sas-token":         None,
            "storage-endpoint-suffix":   None,
        },
    ),
}

# Kafka class-name patterns -> target suffix
KAFKA_RENAME_TABLE = {
    re.compile(r'ConsumeKafka_[12]_0$'):        "ConsumeKafka_2_6",
    re.compile(r'PublishKafka_[12]_0$'):         "PublishKafka_2_6",
    re.compile(r'ConsumeKafkaRecord_[12]_0$'):   "ConsumeKafkaRecord_2_6",
    re.compile(r'PublishKafkaRecord_[12]_0$'):   "PublishKafkaRecord_2_6",
}


def fix_type_rename(proc: dict, pg: dict, row: dict) -> tuple[list[str], list[str]]:
    """
    Rename Kafka or Azure processor types; apply Azure property renames.
    Returns (applied_messages, manual_messages).
    """
    applied = []
    manual = []
    old_type = proc.get("type", "")
    proc_label = f"{proc.get('name', '?')} ({proc.get('identifier', '?')})"

    # --- Kafka ---
    for pattern, new_suffix in KAFKA_RENAME_TABLE.items():
        if pattern.search(old_type):
            old_suffix = old_type.rsplit(".", 1)[-1]
            proc["type"] = old_type[:old_type.rfind(old_suffix)] + new_suffix
            applied.append(
                f"[FIXED] {proc_label} — type renamed: {old_suffix} -> {new_suffix}"
            )
            return applied, manual

    # --- Azure ---
    old_suffix = old_type.rsplit(".", 1)[-1]
    if old_suffix in AZURE_RENAME_TABLE and "_v12" not in old_suffix:
        new_suffix, prop_map = AZURE_RENAME_TABLE[old_suffix]
        proc["type"] = old_type[:old_type.rfind(old_suffix)] + new_suffix

        props = proc.get("properties", {})
        new_props = {}
        for k, v in list(props.items()):
            if k in prop_map:
                target = prop_map[k]
                if target is None:
                    pass  # drop
                else:
                    new_props[target] = v
            else:
                new_props[k] = v
        proc["properties"] = new_props

        applied.append(
            f"[FIXED] {proc_label} — type renamed: {old_suffix} -> {new_suffix}; "
            "properties migrated per rename table"
        )
        manual.append(
            f"[MANUAL] {proc_label} — credentials service must be updated to "
            "AzureStorageCredentialsService_v12 (interface changed)"
        )

    return applied, manual


# ---------------------------------------------------------------------------
# Dispatch table and main entry point
# ---------------------------------------------------------------------------

def _classify_row(row: dict) -> str:
    """Return a handler key or 'manual'."""
    issue = row.get("Issue", "").lower()
    level = row.get("Level", "").lower()

    if level == "error":
        return "manual"

    if re.search(r"script engine\s*=\s*(python|ruby|lua)", issue):
        return "fix_script_engine"  # handled by AI Agent, not this script
    if "proxy properties in invokehttp" in issue:
        return "fix_invokehttp_proxy"
    if "variables are not available" in issue:
        return "fix_variables"       # handled via PARAMETER_CONTEXT_PLAN
    if re.search(r"access key id|secret access key", issue):
        return "fix_s3_credentials"
    if "convertjsontosql" in issue:
        return "fix_convert_json_to_sql"
    if re.search(r"consume ?kafka_[12]_0|publish ?kafka_[12]_0|"
                 r"consume ?kafkarecord_[12]_0|publish ?kafkarecord_[12]_0", issue):
        return "fix_type_rename"
    if re.search(r"(get|put|fetch|list|delete)azure(queue|blob)storage(?!_v12)", issue):
        return "fix_type_rename"

    return "manual"


def apply_csv_transforms(csv_path: str, exports_dir: str) -> None:
    """
    Main entry point for applying CSV-driven transforms.
    Skips fix_script_engine (handled by AI Agent) and fix_variables (handled by
    apply_variable_contexts). Reports both applied and manual items.
    """
    rows = parse_csv(csv_path)
    exports = Path(exports_dir)

    # Group rows by flow file path
    by_file: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        by_file[row["Flow name"].strip()].append(row)

    applied: list[str] = []
    manual: list[str] = []
    skipped_for_ai_agent: list[str] = []

    # Load all affected JSON files once
    file_cache: dict[str, dict] = {}
    file_dirty: dict[str, bool] = {}
    for rel_path in by_file:
        abs_path = exports / rel_path
        if abs_path.exists():
            file_cache[rel_path] = load_json(abs_path)
            file_dirty[rel_path] = False

    for rel_path, rows_for_file in by_file.items():
        if rel_path not in file_cache:
            manual.append(f"[WARN] File not found: {rel_path}")
            continue

        root = file_cache[rel_path]
        flow_contents = root.get("flowContents", root)

        for row in rows_for_file:
            handler = _classify_row(row)
            proc_uuid = row.get("_proc_uuid")

            if handler == "manual":
                manual.append(
                    f"[MANUAL][{row.get('Level','?')}] {rel_path} — "
                    f"{row.get('Processor', row.get('Process Group', '?'))} — "
                    f"{row.get('Issue','?')}\n"
                    f"    Solution: {row.get('Solution','?')}"
                )
                continue

            if handler == "fix_script_engine":
                skipped_for_ai_agent.append(
                    f"[AI Agent] {rel_path} — "
                    f"{row.get('Processor','?')} — Script engine rewrite needed"
                )
                continue

            if handler == "fix_variables":
                # Handled separately via apply_variable_contexts
                continue

            if not proc_uuid:
                manual.append(
                    f"[WARN] {rel_path} — no processor UUID in row: {row.get('Processor')}"
                )
                continue

            comp, pg = find_component(flow_contents, proc_uuid)
            if comp is None:
                manual.append(
                    f"[WARN] {rel_path} — processor {proc_uuid} not found in JSON"
                )
                continue

            if handler == "fix_invokehttp_proxy":
                msgs = fix_invokehttp_proxy(comp, pg, row)
            elif handler == "fix_s3_credentials":
                msgs = fix_s3_credentials(comp, pg, row)
            elif handler == "fix_convert_json_to_sql":
                msgs = fix_convert_json_to_sql(comp, pg, row)
            elif handler == "fix_type_rename":
                app_msgs, man_msgs = fix_type_rename(comp, pg, row)
                msgs = app_msgs
                manual.extend([f"{rel_path} — {m}" for m in man_msgs])
            else:
                msgs = []

            if msgs:
                applied.extend([f"{rel_path} — {m}" for m in msgs])
                file_dirty[rel_path] = True

    # Write modified files
    for rel_path, dirty in file_dirty.items():
        if dirty:
            save_json(exports / rel_path, file_cache[rel_path])

    # --- Report ---
    print("\n=== Applied Changes ===")
    if applied:
        for m in applied:
            print(" ", m)
    else:
        print("  (none)")

    if skipped_for_ai_agent:
        print("\n=== Script Engine Rewrites (handled by AI Agent after this script) ===")
        for m in skipped_for_ai_agent:
            print(" ", m)

    print("\n=== Manual Action Required ===")
    if manual:
        for m in manual:
            print(" ", m)
    else:
        print("  (none)")

    print(f"\n=== Summary ===")
    print(f"  Fixes applied : {len(applied)}")
    print(f"  Script rewrites (AI Agent) : {len(skipped_for_ai_agent)}")
    print(f"  Manual items  : {len(manual)}")

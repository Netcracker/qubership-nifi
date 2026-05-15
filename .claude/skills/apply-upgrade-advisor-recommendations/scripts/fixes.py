"""
fixes.py   --  Individual fix handlers, rename tables, and the main
              apply_csv_transforms entry point.
"""

import re
from collections import defaultdict
from pathlib import Path

from utils import (
    load_json,
    save_json,
    find_component,
    find_services_by_type_suffix,
    parse_csv,
    _make_service,
)


# ---------------------------------------------------------------------------
# Individual fix handlers  (called by apply_csv_transforms)
# ---------------------------------------------------------------------------


def _proxy_service_name(props: dict) -> str:
    """Derive a unique service name from Proxy Host / Proxy Port properties."""
    host = props.get("Proxy Host", "")
    port = props.get("Proxy Port", "")
    parts = [p for p in [host, port] if p]
    return "ProxyConfigurationService-" + "-".join(parts) if parts else "ProxyConfigurationService"


def _aws_credentials_service_name(props: dict) -> str:
    """Derive a unique service name from Bucket / Region processor properties."""
    bucket = props.get("Bucket", "")
    region = props.get("Region", "")
    parts = [p for p in [bucket, region] if p]
    return "AWSCredentialsProviderService-" + "-".join(parts) if parts else "AWSCredentialsProviderService"


def fix_invokehttp_proxy(
    proc: dict,
    pg: dict,
    _row: dict,
    nifi_version: str = "1.28.1",
    parent_pg_path: str | None = None,
    child_pg_path: str | None = None,
    reuse_svc_id: str | None = None,
    reuse_svc_name: str | None = None,
) -> tuple[list[str], str]:
    """Migrate InvokeHTTP Proxy* inline properties to a StandardProxyConfigurationService.

    Returns (messages, svc_id).  svc_id is the UUID of the created or reused service
    (empty string when nothing was done).  Pass reuse_svc_id to skip service creation
    and wire the processor to an already-created service instead.
    """
    proc_label = f"{proc.get('name', '?')} ({proc.get('identifier', '?')})"
    proc_id = proc["identifier"]

    prop_map = {
        "Proxy Host":     "proxy-server-host",
        "Proxy Port":     "proxy-server-port",
        "Proxy Type":     "proxy-type",
        "invokehttp-proxy-user": "proxy-user-name",
        "invokehttp-proxy-password": "proxy-user-password",
    }
    proxy_type_map = {
        "DIRECT": "DIRECT", "direct": "DIRECT",
        "HTTP":   "HTTP",   "http":   "HTTP",
        "SOCKS":  "SOCKS",  "socks":  "SOCKS",
    }

    props = proc.get("properties", {})
    removed_keys = [k for k in prop_map if k in props]

    # --- Reuse mode: service already created by a previous call ---
    if reuse_svc_id:
        if parent_pg_path and child_pg_path and Path(parent_pg_path) != Path(child_pg_path):
            child_data = load_json(Path(child_pg_path))
            child_data.setdefault("externalControllerServices", {})[reuse_svc_id] = {
                "identifier": reuse_svc_id,
                "name":       reuse_svc_name or "ProxyConfigurationService",
            }
            child_proc, _ = find_component(child_data.get("flowContents", child_data), proc_id)
            if child_proc is not None:
                child_props = child_proc.get("properties", {})
                child_props["proxy-configuration-service"] = reuse_svc_id
                for key in removed_keys:
                    child_props.pop(key, None)
            save_json(Path(child_pg_path), child_data)

        props["proxy-configuration-service"] = reuse_svc_id
        for key in removed_keys:
            props.pop(key, None)

        return [
            f"[FIXED] {proc_label}  -- Proxy properties migrated to existing "
            f"StandardProxyConfigurationService ({reuse_svc_id}); "
            f"removed: {', '.join(removed_keys)}"
        ], reuse_svc_id

    # --- Collect proxy properties for new service ---
    svc_props = {}
    for old_key, new_key in prop_map.items():
        if old_key in props:
            value = props[old_key]
            if old_key == "Proxy Type":
                value = proxy_type_map.get(value, value)
            svc_props[new_key] = value

    if not removed_keys:
        return [], ""

    # --- Build the controller service ---
    svc = _make_service(
        name=_proxy_service_name(props),
        svc_type="org.apache.nifi.proxy.StandardProxyConfigurationService",
        bundle={
            "group": "org.apache.nifi",
            "artifact": "nifi-proxy-configuration-nar",
            "version": nifi_version,
        },
        properties=svc_props,
    )

    # --- Cross-file mode: CS lives in a different PG file from the processor ---
    if parent_pg_path and child_pg_path:
        same_file = Path(parent_pg_path) == Path(child_pg_path)

        parent_data = load_json(Path(parent_pg_path))
        parent_data.get("flowContents", parent_data).setdefault("controllerServices", []).append(svc)
        save_json(Path(parent_pg_path), parent_data)

        child_data = load_json(Path(child_pg_path))
        if not same_file:
            child_data.setdefault("externalControllerServices", {})[svc["identifier"]] = {
                "identifier": svc["identifier"],
                "name":       svc["name"],
            }
        child_proc, _ = find_component(child_data.get("flowContents", child_data), proc_id)
        if child_proc is not None:
            child_props = child_proc.get("properties", {})
            child_props["proxy-configuration-service"] = svc["identifier"]
            for key in removed_keys:
                child_props.pop(key, None)
        save_json(Path(child_pg_path), child_data)

        props["proxy-configuration-service"] = svc["identifier"]
        for key in removed_keys:
            props.pop(key, None)

        return [
            f"[FIXED] {proc_label}  -- Proxy properties migrated to new "
            f"StandardProxyConfigurationService ({svc['identifier']}) "
            f"in {Path(parent_pg_path).name}; "
            + (f"externalControllerServices updated in {Path(child_pg_path).name}; " if not same_file else "")
            + f"removed: {', '.join(removed_keys)}"
        ], svc["identifier"]

    # --- Default mode: CS in the same PG as InvokeHTTP ---
    pg.setdefault("controllerServices", []).append(svc)
    props["proxy-configuration-service"] = svc["identifier"]
    for key in removed_keys:
        props.pop(key, None)

    return [
        f"[FIXED] {proc_label}  -- Proxy properties migrated to new "
        f"StandardProxyConfigurationService ({svc['identifier']}); "
        f"removed: {', '.join(removed_keys)}"
    ], svc["identifier"]

def fix_s3_credentials(
    proc: dict,
    pg: dict,
    _row: dict,
    nifi_version: str = "1.28.1",
    parent_pg_path: str | None = None,
    child_pg_path: str | None = None,
    reuse_svc_id: str | None = None,
    reuse_svc_name: str | None = None,
) -> tuple[list[str], str]:
    """Create AWSCredentialsProviderControllerService from processor credentials (empty if absent).

    Returns (messages, svc_id).  svc_id is the UUID of the created or reused service
    (empty string when nothing was done).  Pass reuse_svc_id to skip service creation
    and wire the processor to an already-created service instead (mirrors fix_invokehttp_proxy).
    """
    proc_label = f"{proc.get('name', '?')} ({proc.get('identifier', '?')})"
    proc_id = proc["identifier"]

    props = proc.get("properties", {})

    if "Access Key" not in props and "Secret Key" not in props:
        return [], ""
    access_key = props.get("Access Key")
    secret_key = props.get("Secret Key")

    svc_props = {}
    removed_keys = []
    if access_key:
        svc_props["Access Key"] = access_key
        removed_keys.append("Access Key")
    if secret_key:
        svc_props["Secret Key"] = secret_key
        removed_keys.append("Secret Key")

    # --- Reuse mode: service already created by a previous call ---
    if reuse_svc_id:
        if parent_pg_path and child_pg_path and Path(parent_pg_path) != Path(child_pg_path):
            child_data = load_json(Path(child_pg_path))
            child_data.setdefault("externalControllerServices", {})[reuse_svc_id] = {
                "identifier": reuse_svc_id,
                "name":       reuse_svc_name or "AWSCredentialsProviderService",
            }
            child_proc, _ = find_component(child_data.get("flowContents", child_data), proc_id)
            if child_proc is not None:
                child_props = child_proc.get("properties", {})
                child_props["AWS Credentials Provider service"] = reuse_svc_id
                for key in removed_keys:
                    child_props.pop(key, None)
            save_json(Path(child_pg_path), child_data)

        props["AWS Credentials Provider service"] = reuse_svc_id
        for key in removed_keys:
            props.pop(key, None)

        return [
            f"[FIXED] {proc_label}  -- AWS credentials migrated to existing "
            f"AWSCredentialsProviderControllerService ({reuse_svc_id}); "
            + (f"removed: {', '.join(removed_keys)}" if removed_keys else "empty credentials")
        ], reuse_svc_id

    # --- Build the controller service ---
    svc = _make_service(
        name=_aws_credentials_service_name(props),
        svc_type=(
            "org.apache.nifi.processors.aws.credentials.provider.service"
            ".AWSCredentialsProviderControllerService"
        ),
        bundle={
            "group": "org.apache.nifi",
            "artifact": "nifi-aws-nar",
            "version": nifi_version,
        },
        properties=svc_props,
    )

    msgs = []
    if not access_key and not secret_key:
        msgs.append(
            f"[MANUAL] {proc_label}  -- AWSCredentialsProviderControllerService "
            f"({svc['identifier']}) created with empty credentials.\n"
            f"    To complete this step manually in NiFi UI:\n"
            f"    1. Open the process group that contains the service\n"
            f"    2. Go to Controller Services and find '{svc['name']}' "
            f"(ID: {svc['identifier']})\n"
            f"    3. Click Edit, then set:\n"
            f"       - Access Key ID      = <your AWS Access Key ID>\n"
            f"       - Secret Access Key  = <your AWS Secret Access Key>\n"
            f"    4. Save and enable the service"
        )

    # --- Cross-file mode: CS lives in a different file from the processor ---
    if parent_pg_path and child_pg_path:
        parent_data = load_json(Path(parent_pg_path))
        parent_data.get("flowContents", parent_data).setdefault("controllerServices", []).append(svc)
        save_json(Path(parent_pg_path), parent_data)

        child_data = load_json(Path(child_pg_path))
        child_data.setdefault("externalControllerServices", {})[svc["identifier"]] = {
            "identifier": svc["identifier"],
            "name":       svc["name"],
        }
        child_proc, _ = find_component(child_data.get("flowContents", child_data), proc_id)
        if child_proc is not None:
            child_props = child_proc.get("properties", {})
            child_props["AWS Credentials Provider service"] = svc["identifier"]
            for key in removed_keys:
                child_props.pop(key, None)
        save_json(Path(child_pg_path), child_data)

        props["AWS Credentials Provider service"] = svc["identifier"]
        for key in removed_keys:
            props.pop(key, None)

        msgs.insert(0,
            f"[FIXED] {proc_label}  -- AWSCredentialsProviderControllerService "
            f"({svc['identifier']}) created in {Path(parent_pg_path).name}; "
            f"externalControllerServices updated in {Path(child_pg_path).name}"
        )
        return msgs, svc["identifier"]

    # --- Default mode: CS in the same PG as the processor ---
    pg.setdefault("controllerServices", []).append(svc)
    props["AWS Credentials Provider service"] = svc["identifier"]
    for key in removed_keys:
        props.pop(key, None)

    msgs.insert(0,
        f"[FIXED] {proc_label}  -- AWSCredentialsProviderControllerService "
        f"({svc['identifier']}) created"
    )
    return msgs, svc["identifier"]


def fix_convert_json_to_sql(
    proc: dict,
    pg: dict,
    _row: dict,
    nifi_version: str = "1.28.1",
    root_pg: dict | None = None,
    existing_reader_id: str | None = None,
) -> tuple[list[str], str]:
    """
    Replace ConvertJSONToSQL + downstream PutSQL with PutDatabaseRecord + a DefaultJsonTreeReader service.
    Returns (messages, reader_service_id). reader_service_id is "" on early-exit paths.
    """
    proc_label = f"{proc.get('name', '?')} ({proc.get('identifier', '?')})"
    proc_id = proc["identifier"]

    # --- Find the downstream PutSQL connected via the "sql" relationship ---
    connections = pg.get("connections", [])
    sql_conn = next(
        (c for c in connections
         if c.get("source", {}).get("id") == proc_id
         and "sql" in c.get("selectedRelationships", [])),
        None,
    )
    if sql_conn is None:
        return [
            f"[MANUAL] {proc_label}  -- ConvertJSONToSQL has no outgoing 'sql' connection; "
            f"cannot safely replace with PutDatabaseRecord. Fix the flow manually."
        ], ""

    putsql_id = sql_conn["destination"]["id"]
    putsql_proc = next(
        (p for p in pg.get("processors", []) if p.get("identifier") == putsql_id),
        None,
    )
    if putsql_proc is None or not putsql_proc.get("type", "").endswith("PutSQL"):
        return [
            f"[MANUAL] {proc_label}  -- downstream processor on 'sql' connection is not PutSQL "
            f"(found: {putsql_proc.get('type') if putsql_proc else 'not found'}); "
            f"cannot safely replace with PutDatabaseRecord. Fix the flow manually."
        ], ""

    # --- Build new properties from ConvertJSONToSQL ---
    # Maps ConvertJSONToSQL property key -> PutDatabaseRecord internal key (None = drop)
    prop_map = {
        "JDBC Connection Pool":        "put-db-record-dcbp-service",
        "Statement Type":              "put-db-record-statement-type",
        "Table Name":                  "put-db-record-table-name",
        "Catalog Name":                "put-db-record-catalog-name",
        "Schema Name":                 "put-db-record-schema-name",
        "Translate Field Names":       "put-db-record-translate-field-names",
        "Unmatched Field Behavior":    "put-db-record-unmatched-field-behavior",
        "Unmatched Column Behavior":   "put-db-record-unmatched-column-behavior",
        "Update Keys":                 "put-db-record-update-keys",
        "jts-quoted-identifiers":      "put-db-record-quoted-identifiers",
        "jts-quoted-table-identifiers": "put-db-record-quoted-table-identifiers",
        "table-schema-cache-size":     "table-schema-cache-size",
        "jts-sql-param-attr-prefix":   None,  # drop — no equivalent
    }

    msgs = []
    new_props = {}
    for k, v in list(proc.get("properties", {}).items()):
        if k in prop_map:
            target = prop_map[k]
            if target is not None:
                new_props[target] = v
            # else: drop
        # else: unknown ConvertJSONToSQL prop — drop silently

    # --- Migrate relevant PutSQL properties ---
    putsql_props = putsql_proc.get("properties", {})
    for putsql_key, pdr_key in [
        ("rollback-on-failure",       "rollback-on-failure"),
        ("database-session-autocommit", "database-session-autocommit"),
        ("Batch Size",                "put-db-record-max-batch-size"),
    ]:
        if putsql_key in putsql_props:
            new_props[pdr_key] = putsql_props[putsql_key]

    obtain_keys = putsql_props.get("Obtain Generated Keys")
    if obtain_keys and obtain_keys.lower() == "true":
        msgs.append(
            f"[MANUAL] {proc_label}  -- PutSQL had 'Obtain Generated Keys = true'; "
            f"PutDatabaseRecord does not support this feature — handle manually."
        )

    # --- Create or reuse DefaultJsonTreeReader service at the root PG level ---
    target_pg = root_pg if root_pg is not None else pg
    if existing_reader_id:
        reader_id = existing_reader_id
    else:
        svc = _make_service(
            name="DefaultJsonTreeReader",
            svc_type="org.apache.nifi.json.JsonTreeReader",
            bundle={
                "group": "org.apache.nifi",
                "artifact": "nifi-record-serialization-services-nar",
                "version": nifi_version,
            },
            properties={},
        )
        target_pg.setdefault("controllerServices", []).append(svc)
        reader_id = svc["identifier"]
    new_props["put-db-record-record-reader"] = reader_id

    # --- Apply new properties to processor ---
    old_type = proc.get("type", "")
    proc["properties"] = new_props
    proc["propertyDescriptors"] = {}
    proc["type"] = "org.apache.nifi.processors.standard.PutDatabaseRecord"

    # --- Rewire connections: point former PutSQL outgoing connections to PutDatabaseRecord ---
    for conn in connections:
        if conn.get("source", {}).get("id") == putsql_id:
            conn["source"]["id"] = proc_id
            conn["source"]["instanceIdentifier"] = proc.get("instanceIdentifier", proc_id)
            conn["source"]["name"] = proc.get("name", "ConvertJSONToSQL")

    # Remove ConvertJSONToSQL to PutSQL "sql" connection and PutSQL processor
    pg["connections"] = [c for c in connections if c is not sql_conn]
    pg["processors"] = [p for p in pg.get("processors", []) if p.get("identifier") != putsql_id]

    # Inherit autoTerminatedRelationships from PutSQL (filtered to valid PutDatabaseRecord names)
    valid_rels = {"success", "retry", "failure"}
    proc["autoTerminatedRelationships"] = [
        r for r in putsql_proc.get("autoTerminatedRelationships", [])
        if r in valid_rels
    ]

    action = "reused" if existing_reader_id else "created"
    msgs.insert(0,
        f"[FIXED] {proc_label}  -- ConvertJSONToSQL -> PutDatabaseRecord; "
        f"PutSQL ({putsql_id}) absorbed; DefaultJsonTreeReader service {action} ({reader_id}). "
        f"Old type: {old_type}"
    )
    return msgs, reader_id


# ---------------------------------------------------------------------------
# Azure and Kafka rename tables
# ---------------------------------------------------------------------------

# Format: { "OldProcessorSimpleName": (new_simple_name, {old_prop: new_prop | None}) }
# None means remove the property.
AZURE_RENAME_TABLE: dict[str, tuple[str, dict]] = {
    "GetAzureQueueStorage": (
        "GetAzureQueueStorage_v12",
        {
            "storage-queue-name": "Queue Name",
            "auto-delete-messages": "Auto Delete Messages",
            "batch-size": "Message Batch Size",
            "visibility-timeout": "Visibility Timeout",
            "storage-credentials-service": "Credentials Service",
            "storage-account-name": None,
            "storage-account-key": None,
            "storage-sas-token": None,
            "storage-endpoint-suffix": None,
        },
    ),
    "PutAzureQueueStorage": (
        "PutAzureQueueStorage_v12",
        {
            "storage-queue-name": "Queue Name",
            "time-to-live": "Message Time To Live",
            "visibility-delay": "Visibility Timeout",
            "storage-credentials-service": "Credentials Service",
            "storage-account-name": None,
            "storage-account-key": None,
            "storage-sas-token": None,
            "storage-endpoint-suffix": None,
        },
    ),
    "DeleteAzureBlobStorage": (
        "DeleteAzureBlobStorage_v12",
        {
            "blob": "blob-name",
            "storage-account-name": None,
            "storage-account-key": None,
            "storage-sas-token": None,
            "storage-endpoint-suffix": None,
        },
    ),
    "FetchAzureBlobStorage": (
        "FetchAzureBlobStorage_v12",
        {
            "blob": "blob-name",
            "cse-key-type": "Client-Side Encryption Key Type",
            "cse-key-id": "Client-Side Encryption Key ID",
            "cse-symmetric-key-hex": "Client-Side Encryption Local Key",
            "storage-account-name": None,
            "storage-account-key": None,
            "storage-sas-token": None,
            "storage-endpoint-suffix": None,
        },
    ),
    "ListAzureBlobStorage": (
        "ListAzureBlobStorage_v12",
        {
            "prefix": "blob-name-prefix",
            "storage-account-name": None,
            "storage-account-key": None,
            "storage-sas-token": None,
            "storage-endpoint-suffix": None,
        },
    ),
    "PutAzureBlobStorage": (
        "PutAzureBlobStorage_v12",
        {
            "blob": "blob-name",
            "azure-create-container": "create-container",
            "cse-key-type": "Client-Side Encryption Key Type",
            "cse-key-id": "Client-Side Encryption Key ID",
            "cse-symmetric-key-hex": "Client-Side Encryption Local Key",
            "storage-account-name": None,
            "storage-account-key": None,
            "storage-sas-token": None,
            "storage-endpoint-suffix": None,
        },
    ),
}

# Kafka class-name patterns -> target suffix
KAFKA_RENAME_TABLE = {
    re.compile(r"ConsumeKafka_[12]_0$"): "ConsumeKafka_2_6",
    re.compile(r"PublishKafka_[12]_0$"): "PublishKafka_2_6",
    re.compile(r"ConsumeKafkaRecord_[12]_0$"): "ConsumeKafkaRecord_2_6",
    re.compile(r"PublishKafkaRecord_[12]_0$"): "PublishKafkaRecord_2_6",
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
            proc["type"] = old_type[: old_type.rfind(old_suffix)] + new_suffix
            if "bundle" in proc:
                proc["bundle"]["artifact"] = "nifi-kafka-2-6-nar"
            applied.append(
                f"[FIXED] {proc_label}  -- type renamed: {old_suffix} -> {new_suffix}; "
                f"bundle.artifact -> nifi-kafka-2-6-nar"
            )
            return applied, manual

    # --- Azure ---
    old_suffix = old_type.rsplit(".", 1)[-1]
    if old_suffix in AZURE_RENAME_TABLE and "_v12" not in old_suffix:
        new_suffix, prop_map = AZURE_RENAME_TABLE[old_suffix]
        proc["type"] = old_type[: old_type.rfind(old_suffix)] + new_suffix

        props = proc.get("properties", {})

        uses_credentials_service = bool(props.get("storage-credentials-service"))
        if not uses_credentials_service:
            dropped_with_values = [
                k for k, target in prop_map.items()
                if target is None and props.get(k)
            ]
            if dropped_with_values:
                manual.append(
                    f"[MANUAL] {proc_label}  -- processor-level Azure credentials were "
                    f"removed during rename to {new_suffix}. Configure a "
                    f"AzureStorageCredentialsControllerService_v12 and set 'Credentials "
                    f"Service'. Previously non-empty properties: "
                    f"{', '.join(dropped_with_values)}"
                )

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

        old_descriptors = proc.get("propertyDescriptors", {})
        new_descriptors = {}
        for k, v in old_descriptors.items():
            if k in prop_map:
                target = prop_map[k]
                if target is not None:
                    new_descriptors[target] = {**v, "name": target}
            else:
                new_descriptors[k] = v
        proc["propertyDescriptors"] = new_descriptors

        applied.append(
            f"[FIXED] {proc_label}  -- type renamed: {old_suffix} -> {new_suffix}; "
            "properties migrated per rename table; propertyDescriptors updated"
        )

    return applied, manual


def upgrade_azure_credentials_service(flow_contents: dict) -> tuple[list[str], list[str]]:
    """
    Find AzureStorageCredentialsControllerService in the flow tree and upgrade
    its type and controllerServiceApis interface type to the _v12 variants.
    Also sets credentials-type based on which credential property is present.
    """
    applied = []
    manual = []
    old_suffix = "AzureStorageCredentialsControllerService"
    new_suffix = "AzureStorageCredentialsControllerService_v12"
    old_api_suffix = "AzureStorageCredentialsService"

    for svc, _pg in find_services_by_type_suffix(flow_contents, old_suffix):
        if "_v12" in svc.get("type", ""):
            continue  # already upgraded
        old_t = svc["type"]
        svc["type"] = old_t[: old_t.rfind(old_suffix)] + new_suffix
        for api in svc.get("controllerServiceApis", []):
            t = api.get("type", "")
            if t.endswith(old_api_suffix) and "_v12" not in t:
                api["type"] = t + "_v12"
        label = f"{svc.get('name', '?')} ({svc.get('identifier', '?')})"
        applied.append(f"[FIXED] {label}  -- type upgraded to {new_suffix}")

        props = svc.setdefault("properties", {})
        has_key = bool(props.get("storage-account-key"))
        has_sas = bool(props.get("storage-sas-token"))

        if has_key and has_sas:
            manual.append(
                f"[MANUAL] {label}  -- both storage-account-key and storage-sas-token are set; "
                f"configuration is ambiguous  -- set credentials-type to ACCOUNT_KEY or SAS_TOKEN "
                f"manually and remove the unused credential"
            )
        elif has_key:
            props["credentials-type"] = "ACCOUNT_KEY"
            applied.append(f"[FIXED] {label}  -- credentials-type set to ACCOUNT_KEY")
        elif has_sas:
            props["credentials-type"] = "SAS_TOKEN"
            applied.append(f"[FIXED] {label}  -- credentials-type set to SAS_TOKEN")
        else:
            manual.append(
                f"[MANUAL] {label}  -- credentials-type not set; "
                f"set it manually in NiFi (ACCOUNT_KEY, SAS_TOKEN, MANAGED_IDENTITY, or SERVICE_PRINCIPAL)"
            )

    return applied, manual


def upgrade_prometheus_record_sink(
    flow_contents: dict,
    new_type: str | None = None,
    new_bundle: dict | None = None,
    prop_map: dict | None = None,
) -> tuple[list[str], list[str]]:
    """
    Find PrometheusRecordSink controller services and replace them with
    QubershipPrometheusRecordSink, remapping properties accordingly.
    SSL Context and Client Authentication are dropped (not supported in target).
    Optional new_type/new_bundle/prop_map override the defaults for older NiFi versions.
    """
    applied = []
    manual = []
    old_suffix = "PrometheusRecordSink"
    if new_type is None:
        new_type = "org.qubership.nifi.service.QubershipPrometheusRecordSink"
    if new_bundle is None:
        new_bundle = {
            "group": "org.qubership.nifi",
            "artifact": "qubership-service-nar",
            "version": "1.0.7",
        }
    if prop_map is None:
        prop_map = {
            "prometheus-reporting-task-metrics-endpoint-port": "prometheus-sink-metrics-endpoint-port",
            "prometheus-reporting-task-instance-id": "prometheus-sink-instance-id",
            "prometheus-reporting-task-ssl-context": None,
            "prometheus-reporting-task-client-auth": None,
        }

    for svc, _pg in find_services_by_type_suffix(flow_contents, old_suffix):
        if "qubership" in svc.get("type", "").lower():
            continue  # already upgraded

        label = f"{svc.get('name', '?')} ({svc.get('identifier', '?')})"

        svc["type"] = new_type
        svc["bundle"] = new_bundle

        props = svc.get("properties", {})
        ssl_ctx = props.get("prometheus-reporting-task-ssl-context")
        if ssl_ctx:
            manual.append(
                f"[MANUAL] {label}  -- SSL Context Service was set to '{ssl_ctx}'; "
                f"QubershipPrometheusRecordSink does not support SSL  -- configure manually"
            )

        new_props = {}
        for k, v in list(props.items()):
            if k in prop_map:
                target = prop_map[k]
                if target is not None:
                    new_props[target] = v
                # else: drop
            else:
                new_props[k] = v
        svc["properties"] = new_props

        applied.append(
            f"[FIXED] {label}  -- PrometheusRecordSink upgraded to QubershipPrometheusRecordSink; "
            f"properties remapped"
        )

    return applied, manual


def rename_standalone_controller_services(
    exports_dir: str,
    rename_plan: list[dict],
) -> None:
    """
    Rename standalone controller service files and update their name field.
    Each entry: {"file": rel_path, "new_name": str, "new_file": rel_path}

    Also scans every other JSON file under exports_dir for externalControllerServices
    entries whose name matches the old name, and updates them to the new name.
    Matching by name rather than UUID because UUIDs may differ across NiFi environments.

    Must run AFTER apply_csv_transforms so CSV-based file lookups still resolve.
    """
    exports = Path(exports_dir)

    for entry in rename_plan:
        old_rel = entry["file"]
        new_name = entry["new_name"]
        new_rel = entry["new_file"]
        old_abs = exports / old_rel
        new_abs = exports / new_rel

        if not old_abs.exists():
            print(f"[WARN] {old_rel} not found — skipping rename")
            continue

        data = load_json(old_abs)
        old_name = None
        if "component" in data and "flowContents" not in data:
            old_name = data["component"].get("name")
            data["component"]["name"] = new_name

        save_json(old_abs, data)
        old_abs.rename(new_abs)
        print(f"[FIXED] Standalone CS renamed: {old_rel} to {new_rel} (service name: {new_name})")

        if old_name:
            for json_file in exports.rglob("*.json"):
                if json_file.resolve() == new_abs.resolve():
                    continue
                try:
                    other = load_json(json_file)
                except Exception:
                    continue
                ext_svcs = other.get("externalControllerServices", {})
                changed = False
                for ref in ext_svcs.values():
                    if ref.get("name") == old_name:
                        ref["name"] = new_name
                        changed = True
                if changed:
                    save_json(json_file, other)
                    rel = json_file.relative_to(exports).as_posix()
                    print(f"[FIXED] Updated externalControllerServices reference in {rel}")


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
        return "fix_variables"  # handled via PARAMETER_CONTEXT_PLAN
    if re.search(r"access key id|secret access key", issue):
        return "fix_s3_credentials"
    if "convertjsontosql" in issue:
        return "fix_convert_json_to_sql"
    if re.search(
        r"consume ?kafka_[12]_0|publish ?kafka_[12]_0|"
        r"consume ?kafkarecord_[12]_0|publish ?kafkarecord_[12]_0",
        issue,
    ):
        return "fix_type_rename"
    if re.search(r"(get|put|fetch|list|delete)azure(queue|blob)storage(?!_v12)", issue):
        return "fix_type_rename"
    if "prometheusrecordsink" in issue:
        return "fix_prometheus"  # handled by upgrade_prometheus_record_sink()
    if re.search(r"azurestoragecredentialscontrollerservice", issue.replace(" ", "")):
        return "fix_azure_credentials"  # handled by upgrade_azure_credentials_service()

    return "manual"


def apply_csv_transforms(
    csv_path: str,
    exports_dir: str,
    nifi_version: str = "1.28.1",
    prometheus_params: dict | None = None,
    invokehttp_cross_file: dict | None = None,
    s3_cross_file: dict | None = None,
) -> None:
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
    proxy_group_cache: dict[str, tuple[str, str]] = {}  # group_key -> (svc_id, svc_name)
    s3_group_cache: dict[str, tuple[str, str]] = {}     # group_key -> (svc_id, svc_name)

    # Load all affected JSON files once
    file_cache: dict[str, dict] = {}
    file_dirty: dict[str, bool] = {}
    reader_ids_per_file: dict[str, str] = {}
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
        # REST API format (standalone controller-service exports) has "component" but not "flowContents"
        if "component" in root and "flowContents" not in root:
            flow_contents = {"controllerServices": [root["component"]]}
        else:
            flow_contents = root.get("flowContents", root)

        svc_applied, svc_manual = upgrade_azure_credentials_service(flow_contents)
        if svc_applied:
            applied.extend([f"{rel_path}  -- {m}" for m in svc_applied])
            file_dirty[rel_path] = True
        if svc_manual:
            manual.extend([f"{rel_path}  -- {m}" for m in svc_manual])

        svc_applied, svc_manual = upgrade_prometheus_record_sink(
            flow_contents, **(prometheus_params or {})
        )
        if svc_applied:
            applied.extend([f"{rel_path}  -- {m}" for m in svc_applied])
            file_dirty[rel_path] = True
        if svc_manual:
            manual.extend([f"{rel_path}  -- {m}" for m in svc_manual])

        for row in rows_for_file:
            handler = _classify_row(row)
            proc_uuid = row.get("_proc_uuid")

            if handler == "manual":
                manual.append(
                    f"[MANUAL][{row.get('Level', '?')}] {rel_path}  -- "
                    f"{row.get('Processor', row.get('Process Group', '?'))}  -- "
                    f"{row.get('Issue', '?')}\n"
                    f"    Solution: {row.get('Solution', '?')}"
                )
                continue

            if handler == "fix_script_engine":
                skipped_for_ai_agent.append(
                    f"[AI Agent] {rel_path}  -- "
                    f"{row.get('Processor', '?')}  -- Script engine rewrite needed"
                )
                continue

            if handler == "fix_variables":
                # Handled separately via apply_variable_contexts
                continue

            if handler == "fix_prometheus":
                # Already handled by upgrade_prometheus_record_sink() above
                continue

            if handler == "fix_azure_credentials":
                # Already handled by upgrade_azure_credentials_service() above
                continue

            if not proc_uuid:
                manual.append(
                    f"[WARN] {rel_path}  -- no processor UUID in row: {row.get('Processor')}"
                )
                continue

            comp, pg = find_component(flow_contents, proc_uuid)
            if comp is None:
                manual.append(
                    f"[WARN] {rel_path}  -- processor {proc_uuid} not found in JSON"
                )
                continue

            if handler == "fix_invokehttp_proxy":
                cross = (invokehttp_cross_file or {}).get(proc_uuid, {})
                group_key = cross.get("group_key")
                cached = proxy_group_cache.get(group_key) if group_key else None
                reuse_svc_id = cached[0] if cached else None
                reuse_svc_name = cached[1] if cached else None
                msgs, svc_id = fix_invokehttp_proxy(
                    comp, pg, row, nifi_version,
                    parent_pg_path=cross.get("parent_pg_path"),
                    child_pg_path=cross.get("child_pg_path"),
                    reuse_svc_id=reuse_svc_id,
                    reuse_svc_name=reuse_svc_name,
                )
                if group_key and svc_id and group_key not in proxy_group_cache:
                    proxy_group_cache[group_key] = (svc_id, _proxy_service_name(comp.get("properties", {})))
            elif handler == "fix_s3_credentials":
                cross = (s3_cross_file or {}).get(proc_uuid, {})
                s3_group_key = cross.get("group_key")
                s3_cached = s3_group_cache.get(s3_group_key) if s3_group_key else None
                s3_reuse_svc_id = s3_cached[0] if s3_cached else None
                s3_reuse_svc_name = s3_cached[1] if s3_cached else None
                all_s3_msgs, s3_svc_id = fix_s3_credentials(
                    comp, pg, row, nifi_version,
                    parent_pg_path=cross.get("parent_pg_path"),
                    child_pg_path=cross.get("child_pg_path"),
                    reuse_svc_id=s3_reuse_svc_id,
                    reuse_svc_name=s3_reuse_svc_name,
                )
                if s3_group_key and s3_svc_id and s3_group_key not in s3_group_cache:
                    s3_group_cache[s3_group_key] = (s3_svc_id, _aws_credentials_service_name(comp.get("properties", {})))
                msgs = [m for m in all_s3_msgs if not m.startswith("[MANUAL]")]
                manual.extend([f"{rel_path}  -- {m}" for m in all_s3_msgs if m.startswith("[MANUAL]")])
            elif handler == "fix_convert_json_to_sql":
                all_msgs, reader_id = fix_convert_json_to_sql(
                    comp, pg, row, nifi_version,
                    root_pg=flow_contents,
                    existing_reader_id=reader_ids_per_file.get(rel_path),
                )
                if reader_id:
                    reader_ids_per_file[rel_path] = reader_id
                msgs = [m for m in all_msgs if not m.startswith("[MANUAL]")]
                manual.extend([f"{rel_path}  -- {m}" for m in all_msgs if m.startswith("[MANUAL]")])
            elif handler == "fix_type_rename":
                app_msgs, man_msgs = fix_type_rename(comp, pg, row)
                msgs = app_msgs
                manual.extend([f"{rel_path}  -- {m}" for m in man_msgs])
            else:
                msgs = []

            if msgs:
                applied.extend([f"{rel_path}  -- {m}" for m in msgs])
                file_dirty[rel_path] = True

    # Reload child files that were saved directly to disk by cross-file operations so
    # that externalControllerServices entries are not overwritten by the stale cache.
    for cross_map in (invokehttp_cross_file, s3_cross_file):
        for _uuid, cross in (cross_map or {}).items():
            parent_path = cross.get("parent_pg_path")
            child_path = cross.get("child_pg_path")
            if parent_path and child_path and Path(parent_path) != Path(child_path):
                try:
                    # Use as_posix() so the key matches CSV-sourced forward-slash paths
                    child_rel = Path(child_path).relative_to(exports).as_posix()
                    if child_rel in file_cache and (exports / child_rel).exists():
                        file_cache[child_rel] = load_json(exports / child_rel)
                except ValueError:
                    pass

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
        print(
            "\n=== Script Engine Rewrites (handled by AI Agent after this script) ==="
        )
        for m in skipped_for_ai_agent:
            print(" ", m)

    print("\n=== Manual Action Required ===")
    if manual:
        for m in manual:
            print(" ", m)
    else:
        print("  (none)")

    print("\n=== Summary ===")
    print(f"  Fixes applied : {len(applied)}")
    print(f"  Script rewrites (AI Agent) : {len(skipped_for_ai_agent)}")
    print(f"  Manual items  : {len(manual)}")

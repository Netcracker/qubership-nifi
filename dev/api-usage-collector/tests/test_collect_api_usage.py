import json

import pytest

import collect_api_usage as collector


def _processor(type_name, properties=None, name=None, identifier="p-1", instance_id=None, state="ENABLED"):
    processor = {"identifier": identifier, "name": name or type_name, "scheduledState": state,
                 "type": "org.apache.nifi.processors.example." + type_name, "properties": properties or {}}
    if instance_id:
        processor["instanceIdentifier"] = instance_id
    return processor


def _group(name="Top", processors=(), groups=(), identifier="g-top", instance_id=None, **extra):
    group = {"identifier": identifier, "name": name, "processors": list(processors), "processGroups": list(groups)}
    if instance_id:
        group["instanceIdentifier"] = instance_id
    group.update(extra)
    return group


def _flow(top, parameter_contexts=None):
    return {"flowContents": top, "parameterContexts": parameter_contexts or {}}


def _only(result):
    """The single entry the analysis found, in either direction."""
    entries = result[collector.INBOUND] + result[collector.OUTBOUND]
    assert len(entries) == 1, entries
    return entries[0]


# ---------------------------------------------------------------------------
# Processor table
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("type_name, api_type, direction", [
    ("HandleHttpRequest", "REST", "inbound"),
    ("ListenHTTP", "REST", "inbound"),
    ("GetHTTP", "REST", "inbound"),
    ("InvokeHTTP", "REST", "outbound"),
    ("PostHTTP", "REST", "outbound"),
    ("ConsumeKafka", "Kafka", "inbound"),
    ("ConsumeKafkaRecord_2_6", "Kafka", "inbound"),
    ("ConsumeKafka_1_0", "Kafka", "inbound"),
    ("PublishKafka", "Kafka", "outbound"),
    ("PublishKafkaRecord_2_0", "Kafka", "outbound"),
    ("ListS3", "S3", "inbound"),
    ("FetchS3Object", "S3", "inbound"),
    ("GetS3ObjectTags", "S3", "inbound"),
    ("PutS3Object", "S3", "outbound"),
    ("CopyS3Object", "S3", "outbound"),
    ("DeleteS3Object", "S3", "outbound"),
    ("ConsumeAMQP", "RabbitMQ", "inbound"),
    ("PublishAMQP", "RabbitMQ", "outbound"),
])
def test_known_processor_is_reported_with_its_type_and_direction(type_name, api_type, direction):
    result = collector.analyze_flow(_flow(_group(processors=[_processor(type_name)])))

    assert [entry["type"] for entry in result[direction]] == [api_type]


@pytest.mark.parametrize("type_name", ["HandleHttpResponse", "UpdateAttribute", "ExecuteSQL", "ConsumeKafka_0_10"])
def test_processor_outside_the_table_is_not_reported(type_name):
    result = collector.analyze_flow(_flow(_group(processors=[_processor(type_name)])))

    assert result == {"inbound": [], "outbound": []}


def test_processor_id_is_the_instance_id_when_the_download_has_one():
    processor = _processor("InvokeHTTP", identifier="versioned-id", instance_id="instance-id")

    entry = _only(collector.analyze_flow(_flow(_group(processors=[processor]))))

    assert entry["processorId"] == "instance-id"


def test_processor_path_lists_the_groups_below_the_top_level_group():
    inner = _group("Inner", processors=[_processor("InvokeHTTP", name="Call")], identifier="v-inner",
                   instance_id="i-inner")
    outer = _group("Outer", groups=[inner], identifier="v-outer")
    top = _group("Top", groups=[outer], instance_id="i-top")

    entry = _only(collector.analyze_flow(_flow(top)))

    assert entry["processorPath"] == [{"name": "Outer", "id": "v-outer"}, {"name": "Inner", "id": "i-inner"}]
    assert collector.processor_path(entry) == "Outer/Inner/Call"


def test_processor_state_is_reported():
    processor = _processor("InvokeHTTP", state="DISABLED")

    entry = _only(collector.analyze_flow(_flow(_group(processors=[processor]))))

    assert entry["state"] == "DISABLED"


# ---------------------------------------------------------------------------
# Additional info per type
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("url_key", ["HTTP URL", "Remote URL"], ids=["2.x key", "1.x key"])
def test_invoke_http_reports_url_and_method(url_key):
    processor = _processor("InvokeHTTP", {url_key: "https://api.example.com/v1", "HTTP Method": "PUT"})

    entry = _only(collector.analyze_flow(_flow(_group(processors=[processor]))))

    assert entry["additionalInfo"] == {"url": "https://api.example.com/v1", "path": "/v1", "method": ["PUT"]}


def test_handle_http_request_reports_allowed_methods_path_and_port():
    processor = _processor("HandleHttpRequest", {
        "Listening Port": "8081", "Allowed Paths": "/orders", "Allow GET": "true", "Allow POST": "false",
        "Allow PUT": "true", "Additional HTTP Methods": "PATCH"})

    entry = _only(collector.analyze_flow(_flow(_group(processors=[processor]))))

    assert entry["additionalInfo"] == {"url": "/orders", "path": "/orders", "method": ["GET", "PUT", "PATCH"],
                                       "port": "8081"}


def test_listen_http_reports_its_base_path_as_url_and_path():
    processor = _processor("ListenHTTP", {"Base Path": "contentListener", "Listening Port": "9090"})

    entry = _only(collector.analyze_flow(_flow(_group(processors=[processor]))))

    assert entry["additionalInfo"] == {"url": "contentListener", "path": "contentListener", "method": ["POST"],
                                       "port": "9090"}


@pytest.mark.parametrize("url, path", [
    ("https://api.example.com/v1/orders", "/v1/orders"),
    ("https://api.example.com", "/"),
    ("http://api.example.com:8080/v1?limit=10#top", "/v1"),
    ("https://${host}:#{port}/v1/#{tenant}/orders", "/v1/#{tenant}/orders"),
    ("${base.url}/orders", None),
    ("", None),
], ids=["path", "no path", "query and fragment", "references", "no scheme", "empty"])
def test_url_path(url, path):
    assert collector.url_path(url) == path


def test_invoke_http_without_a_schemed_url_reports_no_path():
    processor = _processor("InvokeHTTP", {"HTTP URL": "${base.url}/orders", "HTTP Method": "GET"})

    entry = _only(collector.analyze_flow(_flow(_group(processors=[processor]))))

    assert entry["additionalInfo"] == {"url": "${base.url}/orders", "method": ["GET"]}


def test_kafka_consumer_reports_topics_group_and_brokers():
    processor = _processor("ConsumeKafka_2_6", {
        "topic": "orders,refunds", "topic_type": "names", "group.id": "billing",
        "bootstrap.servers": "kafka:9092"})

    entry = _only(collector.analyze_flow(_flow(_group(processors=[processor]))))

    assert entry["additionalInfo"] == {"topics": "orders,refunds", "topicType": "names", "groupId": "billing",
                                       "bootstrapServers": "kafka:9092"}


def test_kafka_brokers_come_from_the_connection_service_in_the_download():
    service = {"identifier": "cs-kafka", "type": "org.apache.nifi.kafka.service.Kafka3ConnectionService",
               "properties": {"bootstrap.servers": "broker-1:9092,broker-2:9092"}}
    processor = _processor("PublishKafka", {"Topic Name": "events", "Kafka Connection Service": "cs-kafka"})
    child = _group("Child", processors=[processor], identifier="g-child")

    entry = _only(collector.analyze_flow(_flow(_group(groups=[child], controllerServices=[service]))))

    assert entry["additionalInfo"] == {"topics": "events", "bootstrapServers": "broker-1:9092,broker-2:9092"}


def test_s3_custom_region_replaces_the_region_marker():
    processor = _processor("PutS3Object", {
        "Bucket": "archive", "Object Key": "${filename}", "Region": "use-custom-region",
        "Custom Region": "eu-central-7", "Endpoint Override URL": "https://minio:9000"})

    entry = _only(collector.analyze_flow(_flow(_group(processors=[processor]))))

    assert entry["additionalInfo"] == {"bucket": "archive", "key": "${filename}", "region": "eu-central-7",
                                       "endpointOverride": "https://minio:9000"}


def test_amqp_publisher_reports_exchange_and_routing_key():
    processor = _processor("PublishAMQP", {
        "Exchange Name": "orders", "Routing Key": "created", "Host Name": "rabbit", "Port": "5672",
        "Virtual Host": "/prod"})

    entry = _only(collector.analyze_flow(_flow(_group(processors=[processor]))))

    assert entry["additionalInfo"] == {"exchange": "orders", "routingKey": "created", "host": "rabbit",
                                       "port": "5672", "virtualHost": "/prod"}


# ---------------------------------------------------------------------------
# Parameter and variable resolution
# ---------------------------------------------------------------------------


def _invoke_url(url, **group_fields):
    return _group(processors=[_processor("InvokeHTTP", {"HTTP URL": url})], **group_fields)


CONTEXTS = {
    "base": {"name": "base", "parameters": [
        {"name": "host", "sensitive": False, "value": "base.example.com"},
        {"name": "token", "sensitive": True, "value": "s3cr3t"},
        {"name": "path", "sensitive": False, "value": "base-orders"},
    ]},
    "app": {"name": "app", "inheritedParameterContexts": ["base"], "parameters": [
        {"name": "path", "sensitive": False, "value": "orders"},
    ]},
}


def test_parameters_resolve_through_an_inherited_context():
    flow = _flow(_invoke_url("https://#{host}/#{'path'}", parameterContextName="app"), CONTEXTS)

    entry = _only(collector.analyze_flow(flow))

    assert entry["additionalInfo"]["url"] == "https://base.example.com/orders"


def test_sensitive_and_unknown_parameters_stay_as_references():
    flow = _flow(_invoke_url("https://x/#{token}/#{missing}", parameterContextName="app"), CONTEXTS)

    entry = _only(collector.analyze_flow(flow))

    assert entry["additionalInfo"]["url"] == "https://x/#{token}/#{missing}"


def test_child_group_without_a_context_does_not_use_its_parents_context():
    child = _invoke_url("https://#{host}/", identifier="g-child", name="Child")
    flow = _flow(_group(groups=[child], parameterContextName="app"), CONTEXTS)

    entry = _only(collector.analyze_flow(flow))

    assert entry["additionalInfo"]["url"] == "https://#{host}/"


def test_variables_on_1x_overlay_down_the_group_tree():
    child = _invoke_url("https://${host}:${port}/${path}", identifier="g-child", name="Child",
                        variables={"port": "8443"})
    top = _group(groups=[child], variables={"host": "top.example.com", "port": "80"})

    entry = _only(collector.analyze_flow(_flow(top), root_variables={"host": "root.example.com", "path": "api"}))

    assert entry["additionalInfo"]["url"] == "https://top.example.com:8443/api"


def test_expression_with_a_function_stays_as_written_on_1x():
    flow = _flow(_invoke_url("https://${host:toUpper()}/${filename}"))

    entry = _only(collector.analyze_flow(flow, root_variables={"host": "example.com"}))

    assert entry["additionalInfo"]["url"] == "https://${host:toUpper()}/${filename}"


def test_variable_references_stay_as_written_on_2x():
    flow = _flow(_invoke_url("https://${host}/", variables={"host": "example.com"}))

    entry = _only(collector.analyze_flow(flow, root_variables=None))

    assert entry["additionalInfo"]["url"] == "https://${host}/"


def test_parameter_declared_by_the_context_wins_over_the_inherited_one():
    flow = _flow(_invoke_url("https://x/#{path}", parameterContextName="app"), CONTEXTS)

    entry = _only(collector.analyze_flow(flow))

    assert entry["additionalInfo"]["url"] == "https://x/orders"


def test_escaped_variable_reference_is_not_resolved_on_1x():
    flow = _flow(_invoke_url("https://x/$${host}"))

    entry = _only(collector.analyze_flow(flow, root_variables={"host": "example.com"}))

    assert entry["additionalInfo"]["url"] == "https://x/$${host}"


def test_escaped_parameter_reference_is_not_resolved():
    flow = _flow(_invoke_url("https://x/##{host}", parameterContextName="app"), CONTEXTS)

    entry = _only(collector.analyze_flow(flow))

    assert entry["additionalInfo"]["url"] == "https://x/##{host}"


# ---------------------------------------------------------------------------
# Report output
# ---------------------------------------------------------------------------


def _report_entry(inbound=(), outbound=()):
    return {"pgId": "pg-1", "pgName": "Orders", "inbound": list(inbound), "outbound": list(outbound)}


def _item(api_type, info, name="Proc", path=()):
    return {"type": api_type, "processorId": "p-1", "processorName": name, "state": "ENABLED",
            "processorPath": list(path), "additionalInfo": info}


def test_markdown_has_one_table_per_type_in_each_direction():
    report = [_report_entry(
        inbound=[_item("Kafka", {"topics": "orders", "groupId": "billing"}, name="Consume",
                       path=[{"name": "In", "id": "g-in"}])],
        outbound=[_item("S3", {"bucket": "archive", "key": "${filename}"}, name="Store"),
                  _item("REST", {"url": "https://api/v1", "path": "/v1", "method": ["POST"]}, name="Call")])]

    assert collector.render_markdown(report) == (
        "# Orders\n"
        "\n"
        "## Outbound\n"
        "\n"
        "### REST\n"
        "\n"
        "| Method | URL | Path | Processor identifier | Processor relative path |\n"
        "| --- | --- | --- | --- | --- |\n"
        "| POST | https://api/v1 | /v1 | p-1 | Call |\n"
        "\n"
        "### S3\n"
        "\n"
        "| Bucket | Key or prefix | Region | Endpoint | Processor identifier | Processor relative path |\n"
        "| --- | --- | --- | --- | --- | --- |\n"
        "| archive | ${filename} |  |  | p-1 | Store |\n"
        "\n"
        "## Inbound\n"
        "\n"
        "### Kafka\n"
        "\n"
        "| Topics | Topic type | Group ID | Bootstrap servers | Processor identifier | Processor relative path |\n"
        "| --- | --- | --- | --- | --- | --- |\n"
        "| orders |  | billing |  | p-1 | In/Consume |\n"
    )


def test_markdown_section_without_apis_says_none_found():
    markdown = collector.render_markdown([_report_entry()])

    assert markdown == "# Orders\n\n## Outbound\n\nNone found.\n\n## Inbound\n\nNone found.\n"


def test_markdown_escapes_pipes_in_cells():
    report = [_report_entry(outbound=[_item("REST", {"url": "https://x/?q=a|b", "method": ["GET"]}, name="A|B")])]

    assert "| GET | https://x/?q=a\\|b |  | p-1 | A\\|B |" in collector.render_markdown(report)


def _api_cells(api_type, direction, info):
    """The API cells of the one table row rendered for an item, without the processor cells."""
    report = [_report_entry(**{direction: [_item(api_type, info)]})]
    row = [line for line in collector.render_markdown(report).splitlines() if line.startswith("|")][-1]
    return [cell.strip() for cell in row.split("|")[1:-1]][:-2]


@pytest.mark.parametrize("api_type, direction, info, cells", [
    ("REST", "inbound", {"url": "/orders", "path": "/orders", "method": ["GET", "POST"], "port": "8081"},
     ["GET, POST", "/orders", "8081"]),
    ("Kafka", "outbound", {"topics": "events", "bootstrapServers": "kafka:9092"}, ["events", "kafka:9092"]),
    ("Kafka", "inbound", {"topics": "orders.*", "topicType": "pattern"}, ["orders.*", "pattern", "", ""]),
    ("S3", "inbound", {"bucket": "archive", "prefix": "in/", "region": "eu-west-1"},
     ["archive", "in/", "eu-west-1", ""]),
    ("S3", "outbound", {"bucket": "a", "key": "k", "destinationBucket": "b", "destinationKey": "k2"},
     ["a -> b", "k -> k2", "", ""]),
    ("RabbitMQ", "outbound", {"routingKey": "created", "host": "rabbit", "port": "5672", "virtualHost": "/"},
     ["", "created", "rabbit:5672", "/"]),
    ("RabbitMQ", "inbound", {"queue": "orders", "brokers": "r1:5672,r2:5672"}, ["orders", "r1:5672,r2:5672", ""]),
], ids=["REST inbound", "Kafka outbound", "Kafka inbound", "S3 list", "S3 copy", "RabbitMQ publish",
        "RabbitMQ consume"])
def test_markdown_api_cells_per_type_and_direction(api_type, direction, info, cells):
    assert _api_cells(api_type, direction, info) == cells


def test_json_entry_has_the_documented_keys():
    processor = _processor("InvokeHTTP", {"HTTP URL": "https://x", "HTTP Method": "GET"})
    entry = {"pgId": "pg-1", "pgName": "Top"}
    entry.update(collector.analyze_flow(_flow(_group(processors=[processor]))))

    document = json.loads(json.dumps([entry]))

    assert sorted(document[0]) == ["inbound", "outbound", "pgId", "pgName"]
    assert sorted(document[0]["outbound"][0]) == [
        "additionalInfo", "processorId", "processorName", "processorPath", "state", "type"]


def test_markdown_for_an_empty_report_says_no_group_has_apis():
    assert collector.render_markdown([]) == "No process group has inbound or outbound APIs.\n"


# ---------------------------------------------------------------------------
# Collection from NiFi
# ---------------------------------------------------------------------------


class FakeClient:
    """Serves a NiFi 2.x root group with the given top-level groups and their downloads."""

    def __init__(self, downloads):
        self.downloads = downloads

    def get(self, path, params=None):
        if path == "/flow/about":
            return {"about": {"version": "2.10.0"}}
        if path == "/flow/process-groups/root":
            groups = [{"id": group_id, "component": {"name": name}} for group_id, (name, _) in self.downloads.items()]
            return {"processGroupFlow": {"flow": {"processGroups": groups}}}
        raise AssertionError("unexpected GET " + path)

    def get_bytes(self, path, params=None):
        group_id = path.split("/")[2]
        return json.dumps(self.downloads[group_id][1]).encode()


def test_group_without_apis_is_left_out_of_the_report_and_logged(tmp_path, capsys):
    client = FakeClient({
        "pg-api": ("With API", _flow(_group(processors=[_processor("InvokeHTTP")]))),
        "pg-none": ("Without API", _flow(_group(processors=[_processor("UpdateAttribute")]))),
    })

    report = collector.collect(client, tmp_path)

    assert [entry["pgId"] for entry in report] == ["pg-api"]
    assert "No inbound or outbound APIs in Without API (pg-none)" in capsys.readouterr().out


# ---------------------------------------------------------------------------
# HTTP client connection reuse
# ---------------------------------------------------------------------------


@pytest.fixture
def https_server(tmp_path):
    """A local HTTPS server that answers every GET with its path and counts accepted connections.

    `server.mode` controls the connection after each request: "keep" keeps it open, "drop" closes
    it after the response, as a server does with an idle keep-alive connection, and "abort"
    closes it before any response.
    """
    pytest.importorskip("cryptography")
    import datetime
    import ipaddress
    import ssl
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "test-server")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=5)).not_valid_after(now + datetime.timedelta(hours=1))
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), False)
            .add_extension(x509.BasicConstraints(ca=True, path_length=None), True)
            .sign(key, hashes.SHA256()))
    cert_pem = tmp_path / "server-cert.pem"
    key_pem = tmp_path / "server-key.pem"
    cert_pem.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_pem.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption()))

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            if self.server.mode == "abort":
                self.close_connection = True
                return
            body = json.dumps({"path": self.path}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = self.server.mode == "drop"

        def log_message(self, *args):
            pass

    class Server(ThreadingHTTPServer):
        connections = 0
        mode = "keep"

        def get_request(self):
            request = super().get_request()
            Server.connections += 1
            return request

    server = Server(("127.0.0.1", 0), Handler)
    server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server_context.load_cert_chain(str(cert_pem), str(key_pem))
    server.socket = server_context.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
    thread.start()
    server.client_context = ssl.create_default_context(cafile=str(cert_pem))
    server.url = "https://127.0.0.1:%d" % server.server_address[1]
    yield server
    server.shutdown()
    server.server_close()


def test_client_sends_every_request_on_one_connection(https_server):
    client = collector.Client(https_server.url, https_server.client_context, {})

    paths = [client.get("/flow/about")["path"], client.get("/flow/process-groups/root")["path"],
             client.get("/process-groups/pg-1/download", {"includeReferencedServices": "true"})["path"]]
    client.close()

    assert paths == ["/nifi-api/flow/about", "/nifi-api/flow/process-groups/root",
                     "/nifi-api/process-groups/pg-1/download?includeReferencedServices=true"]
    assert https_server.connections == 1


def test_client_resends_a_request_when_the_server_dropped_the_idle_connection(https_server):
    https_server.mode = "drop"
    client = collector.Client(https_server.url, https_server.client_context, {})

    client.get("/flow/about")
    second = client.get("/flow/process-groups/root")
    client.close()

    assert second == {"path": "/nifi-api/flow/process-groups/root"}
    assert https_server.connections == 2


def test_client_does_not_resend_when_a_new_connection_is_dropped(https_server):
    https_server.mode = "abort"
    client = collector.Client(https_server.url, https_server.client_context, {})

    with pytest.raises(ConnectionError):
        client.get("/flow/about")

    assert https_server.connections == 1

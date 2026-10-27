import json
import re

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
    ("GetS3ObjectMetadata", "S3", "inbound"),
    ("PutS3Object", "S3", "outbound"),
    ("TagS3Object", "S3", "outbound"),
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


def test_kafka_consumer_on_nifi_2x_reports_topics_group_and_brokers():
    processor = _processor("ConsumeKafka", {
        "Topics": "orders.*", "Topic Format": "pattern", "Group ID": "billing", "bootstrap.servers": "kafka:9092"})

    entry = _only(collector.analyze_flow(_flow(_group(processors=[processor]))))

    assert entry["additionalInfo"] == {"topics": "orders.*", "topicType": "pattern", "groupId": "billing",
                                       "bootstrapServers": "kafka:9092"}


def test_list_s3_reports_its_prefix():
    processor = _processor("ListS3", {"Bucket": "inbox", "prefix": "in/", "Region": "eu-west-1"})

    entry = _only(collector.analyze_flow(_flow(_group(processors=[processor]))))

    assert entry["additionalInfo"] == {"bucket": "inbox", "prefix": "in/", "region": "eu-west-1"}


def test_copy_s3_object_reports_source_and_destination():
    processor = _processor("CopyS3Object", {
        "Source Bucket": "inbox", "Source Key": "${filename}", "Destination Bucket": "archive",
        "Destination Key": "done/${filename}"})

    entry = _only(collector.analyze_flow(_flow(_group(processors=[processor]))))

    assert entry["additionalInfo"] == {"bucket": "inbox", "key": "${filename}", "destinationBucket": "archive",
                                       "destinationKey": "done/${filename}"}


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


def test_contexts_that_inherit_from_each_other_resolve_without_looping():
    contexts = {
        "a": {"name": "a", "inheritedParameterContexts": ["b"], "parameters": [{"name": "host", "value": "a.example"}]},
        "b": {"name": "b", "inheritedParameterContexts": ["a"], "parameters": [{"name": "path", "value": "orders"}]},
    }
    flow = _flow(_invoke_url("https://#{host}/#{path}", parameterContextName="a"), contexts)

    entry = _only(collector.analyze_flow(flow))

    assert entry["additionalInfo"]["url"] == "https://a.example/orders"


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


@pytest.mark.parametrize("name, heading", [
    ("Orders\nand refunds", "# Orders and refunds"),
    ("Orders #", "# Orders \\#"),
    ("C:\\in", "# C:\\\\in"),
], ids=["line break", "trailing hash", "backslash"])
def test_markdown_group_heading_keeps_the_whole_group_name(name, heading):
    report = [dict(_report_entry(), pgName=name)]

    assert collector.render_markdown(report).splitlines()[0] == heading


def test_markdown_lists_skipped_groups_after_the_report():
    skipped = [{"pgId": "pg-2", "pgName": "Hidden", "reason": "GET /x -> HTTP 403: forbidden"}]

    markdown = collector.render_markdown([], skipped)

    assert markdown == (
        "No analyzed process group has inbound or outbound APIs.\n"
        "\n"
        "# Skipped process groups\n"
        "\n"
        "These groups were not analyzed, so their APIs are missing from this report.\n"
        "\n"
        "| Process group | Identifier | Reason |\n"
        "| --- | --- | --- |\n"
        "| Hidden | pg-2 | GET /x -> HTTP 403: forbidden |\n"
    )


# ---------------------------------------------------------------------------
# Collection from NiFi
# ---------------------------------------------------------------------------


class FakeClient:
    """Serves a NiFi root group with the given top-level groups and their downloads.

    A group whose name is None is listed without its component, as NiFi lists a group the user
    cannot read. A download that is a CollectorError is raised; one that is bytes is returned as is.
    """

    def __init__(self, downloads, version="2.10.0", root_variables=None):
        self.downloads = downloads
        self.version = version
        self.root_variables = root_variables or {}

    def get(self, path, params=None):
        if path == "/flow/about":
            return {"about": {"version": self.version}}
        if path == "/flow/process-groups/root":
            groups = []
            for group_id, (name, _) in self.downloads.items():
                group = {"id": group_id, "permissions": {"canRead": name is not None}}
                if name is not None:
                    group["component"] = {"name": name}
                groups.append(group)
            return {"processGroupFlow": {"flow": {"processGroups": groups}}}
        if path == "/process-groups/root/variable-registry" and self.version.startswith("1."):
            return {"variableRegistry": {"variables": [
                {"variable": {"name": name, "value": value}} for name, value in self.root_variables.items()]}}
        raise AssertionError("unexpected GET " + path)

    def get_bytes(self, path, params=None):
        download = self.downloads[path.split("/")[2]][1]
        if isinstance(download, collector.CollectorError):
            raise download
        if isinstance(download, bytes):
            return download
        return json.dumps(download).encode()

    def close(self):
        pass


def test_group_without_apis_is_left_out_of_the_report_and_logged(tmp_path, capsys):
    client = FakeClient({
        "pg-api": ("With API", _flow(_group(processors=[_processor("InvokeHTTP")]))),
        "pg-none": ("Without API", _flow(_group(processors=[_processor("UpdateAttribute")]))),
    })

    report, skipped = collector.collect(client, tmp_path)

    assert [entry["pgId"] for entry in report] == ["pg-api"]
    assert skipped == []
    assert "No inbound or outbound APIs in Without API (pg-none)" in capsys.readouterr().out


def test_group_the_user_cannot_read_is_skipped_and_the_others_are_reported(tmp_path, capsys):
    client = FakeClient({
        "pg-hidden": (None, None),
        "pg-api": ("With API", _flow(_group(processors=[_processor("InvokeHTTP")]))),
    })

    report, skipped = collector.collect(client, tmp_path)

    assert [entry["pgId"] for entry in report] == ["pg-api"]
    assert skipped == [{"pgId": "pg-hidden", "pgName": "", "reason": "no read permission on the process group"}]
    assert ("Warning: skipped process group <unreadable> (pg-hidden): no read permission on the process group"
            in capsys.readouterr().err)


@pytest.mark.parametrize("download, reason", [
    (collector.CollectorError("GET /process-groups/pg-bad/download -> HTTP 403: forbidden"),
     "GET /process-groups/pg-bad/download -> HTTP 403: forbidden"),
    (b"<html>Sign in</html>",
     "GET /process-groups/pg-bad/download returned a body that is not JSON: <html>Sign in</html>"),
    ({"processGroups": []},
     "GET /process-groups/pg-bad/download returned an unexpected body: no 'flowContents' key"),
], ids=["HTTP error", "HTML page", "JSON without a flow"])
def test_group_whose_download_fails_is_skipped_and_the_others_are_reported(tmp_path, download, reason):
    client = FakeClient({
        "pg-bad": ("Bad", download),
        "pg-api": ("With API", _flow(_group(processors=[_processor("InvokeHTTP")]))),
    })

    report, skipped = collector.collect(client, tmp_path)

    assert [entry["pgId"] for entry in report] == ["pg-api"]
    assert skipped == [{"pgId": "pg-bad", "pgName": "Bad", "reason": reason}]


def test_root_variables_of_nifi_1x_resolve_in_a_downloaded_group(tmp_path):
    processor = _processor("InvokeHTTP", {"Remote URL": "https://${api.host}/v1"})
    client = FakeClient({"pg-api": ("With API", _flow(_group(processors=[processor])))},
                        version="1.28.1", root_variables={"api.host": "root.example.com"})

    report, _ = collector.collect(client, tmp_path)

    assert report[0]["outbound"][0]["additionalInfo"]["url"] == "https://root.example.com/v1"


def test_about_response_without_the_about_key_is_reported_as_an_error(tmp_path):
    class NoAbout(FakeClient):
        def get(self, path, params=None):
            return {"error": "maintenance"} if path == "/flow/about" else super().get(path, params)

    with pytest.raises(collector.CollectorError, match="GET /flow/about returned an unexpected body: no 'about' key"):
        collector.collect(NoAbout({}), tmp_path)


class RootClient:
    """Answers GET /flow/process-groups/root with a fixed body."""

    def __init__(self, body):
        self.body = body

    def get(self, path, params=None):
        assert path == "/flow/process-groups/root"
        return self.body


def test_group_listed_with_its_component_but_without_read_permission_has_no_name():
    groups = [{"id": "pg-1", "permissions": {"canRead": False}, "component": {"name": "Hidden"}},
              {"id": "pg-2", "permissions": {"canRead": True}, "component": {"name": "Visible"}}]

    top = collector.top_level_groups(RootClient({"processGroupFlow": {"flow": {"processGroups": groups}}}))

    assert top == [("pg-1", None), ("pg-2", "Visible")]


@pytest.mark.parametrize("body, key", [
    ({"error": "maintenance"}, "processGroupFlow"),
    ({"processGroupFlow": {"id": "root"}}, "flow"),
], ids=["no processGroupFlow", "no flow"])
def test_root_response_without_the_flow_is_reported_as_an_error(body, key):
    with pytest.raises(collector.CollectorError) as excinfo:
        collector.top_level_groups(RootClient(body))

    assert str(excinfo.value) == "GET /flow/process-groups/root returned an unexpected body: no '%s' key" % key


def test_excluded_entries_are_left_out_of_the_report_and_logged(tmp_path, capsys):
    internal = _processor("InvokeHTTP", {"HTTP URL": "https://Internal.example.com/v1"}, name="Internal",
                          identifier="p-internal")
    health = _processor("HandleHttpRequest", {"Allowed Paths": "/health"}, name="Health", identifier="p-health")
    external = _processor("InvokeHTTP", {"HTTP URL": "https://api.example.com/v1"}, name="External",
                          identifier="p-external")
    client = FakeClient({
        "pg-mixed": ("Mixed", _flow(_group(processors=[internal, health, external]))),
        "pg-internal": ("Only internal", _flow(_group(processors=[internal]))),
    })

    report, _ = collector.collect(client, tmp_path, frozenset({"internal.example.com"}), re.compile("/health"))

    assert [(entry["pgId"], [item["processorName"] for item in entry["outbound"] + entry["inbound"]])
            for entry in report] == [("pg-mixed", ["External"])]
    out = capsys.readouterr().out
    assert ("Excluded outbound REST processor 'Internal' (p-internal) at Internal in Mixed (pg-mixed): "
            "host internal.example.com is in --exclude-hosts") in out
    assert ("Excluded inbound REST processor 'Health' (p-health) at Health in Mixed (pg-mixed): "
            "path /health matches --exclude-paths-regex") in out
    assert "No inbound or outbound APIs in Only internal (pg-internal)" in out


# ---------------------------------------------------------------------------
# Command line run
# ---------------------------------------------------------------------------


MAIN_ARGS = ["--url", "https://nifi.example.com", "--auth", "cookie", "--ca-cert", "ca.cer"]


@pytest.fixture
def run_main(tmp_path, monkeypatch):
    """Runs main() in an empty working directory against a FakeClient built from `downloads`."""
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(collector, "build_context", lambda args, workdir: None)
    monkeypatch.setattr(collector, "auth_headers", lambda args: {})

    def run(downloads, extra_args=()):
        monkeypatch.setattr(collector, "Client", lambda url, context, headers: FakeClient(downloads))
        return collector.main(MAIN_ARGS + list(extra_args))
    return run


API_GROUP = ("With API", _flow(_group(processors=[_processor("InvokeHTTP", {"HTTP URL": "https://x/v1"})])))


def test_main_writes_both_reports_and_removes_the_downloads(tmp_path, run_main):
    code = run_main({"pg-api": API_GROUP})

    assert code == 0
    assert [entry["pgId"] for entry in json.loads((tmp_path / "api-usage.json").read_text())] == ["pg-api"]
    assert (tmp_path / "api-usage.md").read_text().startswith("# With API\n")
    assert sorted(path.name for path in tmp_path.iterdir()) == ["api-usage.json", "api-usage.md"]


def test_main_keeps_the_downloads_with_keep_temp(tmp_path, run_main):
    run_main({"pg-api": API_GROUP}, ["--keep-temp", "--output-prefix", "report"])

    [download_dir] = [path for path in tmp_path.iterdir() if path.is_dir()]
    assert download_dir.name.startswith("api-usage-tmp-")
    assert [path.name for path in download_dir.iterdir()] == ["With_API-pg-api.json"]


def test_main_reports_skipped_groups_and_exits_with_the_partial_code(tmp_path, run_main, capsys):
    code = run_main({"pg-hidden": (None, None), "pg-api": API_GROUP})

    assert code == collector.EXIT_PARTIAL
    assert [entry["pgId"] for entry in json.loads((tmp_path / "api-usage.json").read_text())] == ["pg-api"]
    assert "|  | pg-hidden | no read permission on the process group |" in (tmp_path / "api-usage.md").read_text()
    assert "skipped process groups: 1" in capsys.readouterr().err


def test_main_exits_with_1_and_writes_no_report_on_a_collector_error(tmp_path, run_main, monkeypatch, capsys):
    def missing_cookie(args):
        raise collector.CollectorError("Cookie mode reads the cookie from NIFI_AUTHORIZATION_BEARER_COOKIE.")
    monkeypatch.setattr(collector, "auth_headers", missing_cookie)

    code = run_main({"pg-api": API_GROUP})

    assert code == 1
    assert "Error: Cookie mode reads the cookie" in capsys.readouterr().err
    assert list(tmp_path.iterdir()) == []


# ---------------------------------------------------------------------------
# Exclusions
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("url, host", [
    ("https://api.example.com/v1", "api.example.com"),
    ("http://API.Example.com:8080/v1", "api.example.com"),
    ("https://10.0.0.1:8443", "10.0.0.1"),
    ("https://[::1]:8443/v1", "::1"),
    ("https://${host}/v1", None),
    ("https://#{host}:8443/v1", None),
    ("${base.url}/orders", None),
    ("", None),
], ids=["host", "upper case with port", "IPv4", "IPv6", "variable", "parameter", "no scheme", "empty"])
def test_url_host(url, host):
    assert collector.url_host(url) == host


def _excluded(item, direction, hosts=frozenset(), path_pattern=None):
    """The names of the processors that apply_exclusions excludes from one entry in `direction`."""
    apis = {collector.INBOUND: [], collector.OUTBOUND: []}
    apis[direction].append(item)
    _, excluded = collector.apply_exclusions(apis, hosts, path_pattern)
    return [excluded_item["processorName"] for _, excluded_item, _ in excluded]


@pytest.mark.parametrize("url, excluded", [
    ("https://internal.example.com/v1", ["Proc"]),
    ("https://INTERNAL.example.com:8443/v1", ["Proc"]),
    ("https://api.example.com/v1", []),
    ("https://sub.internal.example.com/v1", []),
    ("https://#{host}/v1", []),
], ids=["listed host", "listed host in upper case with port", "other host", "subdomain", "unresolved host"])
def test_outbound_rest_is_excluded_by_the_host_of_its_url(url, excluded):
    item = _item("REST", {"url": url, "path": "/v1"})

    assert _excluded(item, collector.OUTBOUND, hosts=frozenset({"internal.example.com"})) == excluded


def test_get_http_is_excluded_by_the_host_of_its_url():
    processor = _processor("GetHTTP", {"URL": "https://internal.example.com/feed"}, name="Fetch feed")
    apis = collector.analyze_flow(_flow(_group(processors=[processor])))

    _, excluded = collector.apply_exclusions(apis, frozenset({"internal.example.com"}), None)

    assert [(direction, item["processorName"]) for direction, item, _ in excluded] == [("inbound", "Fetch feed")]


def test_listening_rest_is_not_excluded_by_its_hostname():
    processor = _processor("HandleHttpRequest", {"Allowed Paths": "/orders", "Hostname": "internal.example.com"})
    apis = collector.analyze_flow(_flow(_group(processors=[processor])))

    _, excluded = collector.apply_exclusions(apis, frozenset({"internal.example.com"}), None)

    assert excluded == []


@pytest.mark.parametrize("api_type, info", [
    ("Kafka", {"topics": "health", "bootstrapServers": "internal.example.com:9092"}),
    ("S3", {"bucket": "health", "endpointOverride": "https://internal.example.com/health"}),
    ("RabbitMQ", {"exchange": "health", "host": "internal.example.com"}),
], ids=["Kafka", "S3", "RabbitMQ"])
def test_entries_other_than_rest_are_never_excluded(api_type, info):
    item = _item(api_type, info)

    assert _excluded(item, collector.OUTBOUND, frozenset({"internal.example.com"}), re.compile(".*")) == []


@pytest.mark.parametrize("path, excluded", [
    ("/health", ["Proc"]),
    ("/health/live", []),
    ("/api/health", []),
], ids=["whole path", "longer path", "path with a prefix"])
@pytest.mark.parametrize("direction", ["outbound", "inbound"])
def test_rest_is_excluded_when_the_whole_path_matches_the_regex(direction, path, excluded):
    item = _item("REST", {"url": path, "path": path})

    assert _excluded(item, direction, path_pattern=re.compile("/health")) == excluded


def test_rest_without_a_path_is_not_excluded_by_the_regex():
    item = _item("REST", {"url": "${base.url}/health", "method": ["GET"]})

    assert _excluded(item, collector.OUTBOUND, path_pattern=re.compile(".*")) == []


def test_exclude_hosts_is_parsed_as_a_lowercase_set():
    args = collector.parse_args(["--url", "https://x", "--auth", "cookie", "--ca-cert", "ca.cer",
                                 "--exclude-hosts", " A.example.com,, b.example.com ,"])

    assert args.exclude_hosts == frozenset({"a.example.com", "b.example.com"})


def test_invalid_exclude_paths_regex_is_refused(capsys):
    with pytest.raises(SystemExit):
        collector.parse_args(["--url", "https://x", "--auth", "cookie", "--ca-cert", "ca.cer",
                              "--exclude-paths-regex", "["])

    assert "invalid regular expression '['" in capsys.readouterr().err


# ---------------------------------------------------------------------------
# HTTP client connection reuse
# ---------------------------------------------------------------------------


@pytest.fixture
def https_server(tmp_path):
    """A local HTTPS server that answers every GET with its path and counts accepted connections.

    `server.mode` controls the connection after each request: "keep" keeps it open, "drop" closes
    it after the response, as a server does with an idle keep-alive connection, and "abort"
    closes it before any response. "html" answers with an HTML page and status 200, as a login
    proxy does, and "forbidden" answers with status 403.
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
            status = 200
            if self.server.mode == "html":
                body = b"<html>Sign in</html>"
            elif self.server.mode == "forbidden":
                body, status = b"Unable to view the process group.", 403
            self.send_response(status)
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
    server_context.minimum_version = ssl.TLSVersion.TLSv1_2
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


def test_client_reports_a_body_that_is_not_json(https_server):
    https_server.mode = "html"
    client = collector.Client(https_server.url, https_server.client_context, {})

    with pytest.raises(collector.CollectorError) as excinfo:
        client.get("/flow/about")
    client.close()

    assert str(excinfo.value) == "GET /flow/about returned a body that is not JSON: <html>Sign in</html>"


def test_client_reports_an_http_error_status(https_server):
    https_server.mode = "forbidden"
    client = collector.Client(https_server.url, https_server.client_context, {})

    with pytest.raises(collector.CollectorError) as excinfo:
        client.get_bytes("/process-groups/pg-1/download")
    client.close()

    assert str(excinfo.value) == "GET /process-groups/pg-1/download -> HTTP 403: Unable to view the process group."


@pytest.mark.parametrize("url, message", [
    ("https://nifi.example.com:84a3", "URL has an invalid port; got 'https://nifi.example.com:84a3'."),
    ("https://nifi.example.com:99999", "URL has an invalid port; got 'https://nifi.example.com:99999'."),
    ("https:///nifi", "URL has no host; got 'https:///nifi'."),
    ("http://nifi.example.com", "URL must use HTTPS; got 'http://nifi.example.com'."),
], ids=["port with a letter", "port out of range", "no host", "HTTP"])
def test_client_refuses_an_unusable_url(url, message):
    with pytest.raises(collector.CollectorError) as excinfo:
        collector.Client(url, None, {})

    assert str(excinfo.value) == message


# ---------------------------------------------------------------------------
# Authentication
# ---------------------------------------------------------------------------


def _args(*argv):
    return collector.parse_args(["--url", "https://x", "--ca-cert", "ca.cer"] + list(argv))


def test_cookie_is_sent_as_the_nifi_bearer_cookie(monkeypatch):
    monkeypatch.setenv("NIFI_AUTHORIZATION_BEARER_COOKIE", "eyJ.token")

    assert collector.auth_headers(_args("--auth", "cookie")) == {"Cookie": "__Secure-Authorization-Bearer=eyJ.token"}


def test_cookie_mode_without_the_cookie_variable_is_refused(monkeypatch):
    monkeypatch.delenv("NIFI_AUTHORIZATION_BEARER_COOKIE", raising=False)

    with pytest.raises(collector.CollectorError, match="NIFI_AUTHORIZATION_BEARER_COOKIE"):
        collector.auth_headers(_args("--auth", "cookie"))


def test_cookie_with_a_line_break_is_refused(monkeypatch):
    monkeypatch.setenv("NIFI_AUTHORIZATION_BEARER_COOKIE", "eyJ.token\r\nX-Injected: 1")

    with pytest.raises(collector.CollectorError, match="control character"):
        collector.auth_headers(_args("--auth", "cookie"))


def test_certificate_mode_sends_no_auth_header():
    assert collector.auth_headers(_args("--auth", "cert", "--client-cert", "client.p12")) == {}


def test_certificate_mode_without_a_client_certificate_is_refused(tmp_path):
    args = _args("--auth", "cert")
    args.ca_cert = None

    with pytest.raises(collector.CollectorError, match="--auth cert requires --client-cert"):
        collector.build_context(args, tmp_path)


def test_certificate_mode_without_the_password_variable_is_refused(tmp_path, monkeypatch):
    monkeypatch.delenv("NIFI_PKCS12_PASSWORD", raising=False)
    args = _args("--auth", "cert", "--client-cert", str(tmp_path / "client.p12"))
    args.ca_cert = None

    with pytest.raises(collector.CollectorError, match="NIFI_PKCS12_PASSWORD"):
        collector.build_context(args, tmp_path)


@pytest.fixture
def client_p12(tmp_path):
    """A PKCS#12 file with a self-signed client certificate and its key, protected by "changeit"."""
    pytest.importorskip("cryptography")
    import datetime

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.serialization import BestAvailableEncryption, pkcs12
    from cryptography.x509.oid import NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "test-client")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=5)).not_valid_after(now + datetime.timedelta(hours=1))
            .sign(key, hashes.SHA256()))
    path = tmp_path / "client.p12"
    path.write_bytes(pkcs12.serialize_key_and_certificates(b"client", key, cert, None,
                                                           BestAvailableEncryption(b"changeit")))
    return path


def test_pkcs12_is_split_into_a_certificate_and_a_key(client_p12, tmp_path):
    cert_pem, key_pem = collector.unpack_pkcs12(client_p12, "changeit", tmp_path)

    assert cert_pem.read_text().startswith("-----BEGIN CERTIFICATE-----")
    assert key_pem.read_text().startswith("-----BEGIN PRIVATE KEY-----")


def test_pkcs12_with_a_wrong_password_is_refused(client_p12, tmp_path):
    with pytest.raises(collector.CollectorError, match="Check the path and the password"):
        collector.unpack_pkcs12(client_p12, "wrong", tmp_path)


class FakeOpenssl:
    """Stands in for subprocess.run.

    An openssl command without -legacy fails with `error` on stderr, and one with -legacy fails
    with `legacy_error`; a None error makes the command succeed.
    """

    def __init__(self, error, legacy_error=None):
        self.error = error
        self.legacy_error = legacy_error
        self.commands = []

    def __call__(self, command, capture_output, env):
        import subprocess
        self.commands.append(command)
        error = self.legacy_error if "-legacy" in command else self.error
        if error is not None:
            return subprocess.CompletedProcess(command, 1, b"", error.encode())
        return subprocess.CompletedProcess(command, 0, ("PEM for %s\n" % command[6]).encode(), b"")


def _unpack_with_fake_openssl(monkeypatch, tmp_path, openssl):
    monkeypatch.setattr(collector.shutil, "which", lambda name: "/usr/bin/openssl")
    monkeypatch.setattr(collector.subprocess, "run", openssl)
    return collector._unpack_pkcs12_with_openssl(tmp_path / "legacy.p12", "changeit", tmp_path / "cert.pem",
                                                 tmp_path / "key.pem")


def test_openssl_retries_a_legacy_pkcs12_with_the_legacy_option(monkeypatch, tmp_path):
    openssl = FakeOpenssl(error="unsupported algorithm RC2-40-CBC")

    cert_pem, key_pem = _unpack_with_fake_openssl(monkeypatch, tmp_path, openssl)

    assert [("-legacy" in command, command[6]) for command in openssl.commands] == [
        (False, "-clcerts"), (True, "-clcerts"), (True, "-cacerts"), (True, "-nocerts")]
    assert cert_pem.read_text() == "PEM for -clcerts\nPEM for -cacerts\n"
    assert key_pem.read_text() == "PEM for -nocerts\n"


def test_openssl_does_not_use_the_legacy_option_for_a_current_pkcs12(monkeypatch, tmp_path):
    openssl = FakeOpenssl(error=None)

    _unpack_with_fake_openssl(monkeypatch, tmp_path, openssl)

    assert ["-legacy" in command for command in openssl.commands] == [False, False, False]


def test_openssl_failure_reports_the_errors_with_and_without_the_legacy_option(monkeypatch, tmp_path):
    openssl = FakeOpenssl(error="Mac verify error: invalid password?", legacy_error="unknown option -legacy")

    with pytest.raises(collector.CollectorError) as excinfo:
        _unpack_with_fake_openssl(monkeypatch, tmp_path, openssl)

    assert str(excinfo.value) == ("openssl could not read %s: Mac verify error: invalid password?; "
                                  "with -legacy: unknown option -legacy" % (tmp_path / "legacy.p12"))

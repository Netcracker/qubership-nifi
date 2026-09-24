import argparse
import json
from pathlib import Path

import pytest

import kb

API_BUNDLE = {"group": "org.apache.nifi", "artifact": "nifi-standard-services-api-nar",
              "version": "1.28.1"}


def _descriptor_1x(**fields):
    descriptor = {"name": "p", "displayName": "P", "required": False, "sensitive": False,
                  "supportsEl": False, "expressionLanguageScope": "Not Supported",
                  "dependencies": []}
    descriptor.update(fields)
    return descriptor


def _processor_1x(**fields):
    definition = {"type": "org.example.P", "inputRequirement": "INPUT_REQUIRED",
                  "supportsParallelProcessing": True, "supportsEventDriven": False,
                  "supportsBatching": True, "executionNodeRestricted": False,
                  "supportedRelationships": [{"name": "success", "description": ""}],
                  "propertyDescriptors": {}}
    definition.update(fields)
    return definition


def _adapted_descriptor(**fields):
    definition = _processor_1x(propertyDescriptors={"p": _descriptor_1x(**fields)})
    return kb.adapt_1x(definition, "PROCESSOR")["propertyDescriptors"]["p"]


def _write_kb(root, version, components):
    """Write a Knowledge Base holding `components`.

    Each is a (kind, type, component.json) triple, or a quadruple whose fourth item overrides the
    bundle group or artifact in the index.
    """
    (root / "components").mkdir(parents=True)
    index = []
    for number, (kind, type_name, component, *bundle) in enumerate(components):
        path = "processors/C%d" % number
        (root / "components" / path).mkdir(parents=True)
        (root / "components" / path / "component.json").write_text(
            json.dumps(component), encoding="utf-8")
        index.append(dict({"kind": kind, "group": "org.apache.nifi",
                           "artifact": "nifi-standard-nar", "version": version,
                           "type": type_name, "tags": [], "deprecated": False,
                           "controllerServiceApis": [], "path": path,
                           "additionalDetailsAvailable": False}, **(bundle[0] if bundle else {})))
    (root / "components" / "index.json").write_text(json.dumps(index), encoding="utf-8")
    (root / "manifest.json").write_text(
        json.dumps({"schemaVersion": "2", "nifi": {"version": version}}), encoding="utf-8")
    return root


def _flow(processors, version="1.28.1"):
    for processor in processors:
        processor.setdefault("bundle", {"group": "org.apache.nifi",
                                        "artifact": "nifi-standard-nar", "version": version})
    return {"flowContents": {"identifier": "b051f24c-f9ff-4fde-b160-9b0b84af3e96",
                             "processors": processors}}


# ---------------------------------------------------------------------------
# adapt_1x: one test per field the 1.x dialect names or shapes differently
# ---------------------------------------------------------------------------


def test_allowable_values_are_flattened_to_value_display_name_and_description():
    descriptor = _adapted_descriptor(allowableValues=[
        {"allowableValue": {"displayName": "Binary", "value": "Binary"}, "canRead": True},
        {"allowableValue": {"displayName": "Text", "value": "text", "description": "UTF-8"},
         "canRead": True},
    ])
    assert descriptor["allowableValues"] == [
        {"value": "Binary", "displayName": "Binary", "description": ""},
        {"value": "text", "displayName": "Text", "description": "UTF-8"},
    ]


def test_a_service_typed_property_gets_the_api_it_requires_as_type_provided_by_value():
    descriptor = _adapted_descriptor(
        identifiesControllerService="org.apache.nifi.serialization.RecordReaderFactory",
        identifiesControllerServiceBundle=API_BUNDLE)
    assert descriptor["typeProvidedByValue"] == {
        "group": "org.apache.nifi", "artifact": "nifi-standard-services-api-nar",
        "version": "1.28.1", "type": "org.apache.nifi.serialization.RecordReaderFactory"}


def test_a_plain_property_gets_no_type_provided_by_value():
    assert "typeProvidedByValue" not in _adapted_descriptor()


@pytest.mark.parametrize("label, supports_el, scope", [
    pytest.param("Not Supported", False, "NONE", id="not supported"),
    pytest.param("Variable Registry Only", True, "ENVIRONMENT", id="variable registry only"),
    pytest.param("Variable Registry and FlowFile Attributes", True, "FLOWFILE_ATTRIBUTES",
                 id="variable registry and flowfile attributes"),
    pytest.param("true (undefined scope)", True, "UNDEFINED", id="undefined scope"),
])
def test_el_scope_label_maps_to_the_2x_scope_name(label, supports_el, scope):
    descriptor = _adapted_descriptor(expressionLanguageScope=label, supportsEl=supports_el)
    assert (descriptor["expressionLanguageScope"],
            descriptor["expressionLanguageScopeDescription"]) == (scope, label)


def test_a_2x_el_scope_name_is_kept():
    descriptor = _adapted_descriptor(expressionLanguageScope="ENVIRONMENT", supportsEl=True)
    assert descriptor["expressionLanguageScope"] == "ENVIRONMENT"
    assert "expressionLanguageScopeDescription" not in descriptor


@pytest.mark.parametrize("dynamic_properties, supported", [
    pytest.param([{"name": "Attribute", "value": "Value", "description": ""}], True,
                 id="documented dynamic property"),
    pytest.param(None, False, id="no dynamic property section"),
])
def test_dynamic_property_support_follows_the_documented_list(dynamic_properties, supported):
    definition = _processor_1x()
    if dynamic_properties is not None:
        definition["dynamicProperties"] = dynamic_properties
    adapted = kb.adapt_1x(definition, "PROCESSOR")
    assert adapted["supportsDynamicProperties"] is supported


@pytest.mark.parametrize("documentation, supported", [
    pytest.param("## Relationships:\n\n### Dynamic Relationships:\n\nA Dynamic Relationship ...",
                 True, id="page has a dynamic relationships section"),
    pytest.param("## Relationships:\n\n| success | ... |", False,
                 id="page has no dynamic relationships section"),
])
def test_dynamic_relationship_support_follows_the_component_page(documentation, supported):
    adapted = kb.adapt_1x(_processor_1x(), "PROCESSOR", documentation)
    assert adapted["supportsDynamicRelationships"] is supported


def test_execution_node_restricted_means_primary_node_only():
    adapted = kb.adapt_1x(_processor_1x(executionNodeRestricted=True), "PROCESSOR")
    assert adapted["primaryNodeOnly"] is True


def test_no_parallel_processing_means_trigger_serially():
    adapted = kb.adapt_1x(_processor_1x(supportsParallelProcessing=False), "PROCESSOR")
    assert adapted["triggerSerially"] is True


def test_an_event_driven_processor_supports_all_three_1x_strategies():
    adapted = kb.adapt_1x(_processor_1x(supportsEventDriven=True), "PROCESSOR")
    assert adapted["supportedSchedulingStrategies"] == [
        "TIMER_DRIVEN", "CRON_DRIVEN", "EVENT_DRIVEN"]
    assert adapted["defaultConcurrentTasksBySchedulingStrategy"] == {
        "TIMER_DRIVEN": 1, "CRON_DRIVEN": 1, "EVENT_DRIVEN": 0}
    assert adapted["defaultSchedulingPeriodBySchedulingStrategy"] == {
        "TIMER_DRIVEN": "0 sec", "CRON_DRIVEN": "* * * * * ?"}
    assert adapted["defaultSchedulingStrategy"] == "TIMER_DRIVEN"
    assert adapted["schedulingDefaultsFromFramework"] is True


def test_a_processor_that_is_not_event_driven_supports_timer_and_cron_only():
    adapted = kb.adapt_1x(_processor_1x(supportsEventDriven=False), "PROCESSOR")
    assert adapted["supportedSchedulingStrategies"] == ["TIMER_DRIVEN", "CRON_DRIVEN"]


def test_controller_service_apis_are_flattened_to_provided_api_implementations():
    definition = {"type": "org.apache.nifi.json.JsonTreeReader", "propertyDescriptors": {},
                  "controllerServiceApis": [
                      {"type": "org.apache.nifi.serialization.RecordReaderFactory",
                       "bundle": API_BUNDLE}]}
    adapted = kb.adapt_1x(definition, "CONTROLLER_SERVICE")
    assert adapted["providedApiImplementations"] == [
        {"group": "org.apache.nifi", "artifact": "nifi-standard-services-api-nar",
         "version": "1.28.1", "type": "org.apache.nifi.serialization.RecordReaderFactory"}]
    assert "supportedSchedulingStrategies" not in adapted


def test_description_becomes_type_description():
    adapted = kb.adapt_1x(_processor_1x(description="Routes FlowFiles"), "PROCESSOR")
    assert adapted["typeDescription"] == "Routes FlowFiles"


def test_kb_adapts_a_1x_definition_and_leaves_a_2x_definition_as_written(tmp_path):
    native_2x = {"definitionFormat": "native-nifi-2x", "definition": {
        "type": "org.example.New", "supportedSchedulingStrategies": ["TIMER_DRIVEN"],
        "propertyDescriptors": {"p": {"name": "p", "expressionLanguageScope": "NONE"}}}}
    normalized_1x = {"definitionFormat": "normalized-nifi-1x",
                     "definition": _processor_1x(type="org.example.Old")}
    knowledge_base = kb.Kb(_write_kb(tmp_path, "1.28.1", [
        ("PROCESSOR", "org.example.New", native_2x),
        ("PROCESSOR", "org.example.Old", normalized_1x),
    ]))
    new = knowledge_base.definition(knowledge_base.lookup("PROCESSOR", "org.example.New"))
    old = knowledge_base.definition(knowledge_base.lookup("PROCESSOR", "org.example.Old"))
    assert new == native_2x
    assert old["definition"]["supportedSchedulingStrategies"] == ["TIMER_DRIVEN", "CRON_DRIVEN"]


# ---------------------------------------------------------------------------
# Choosing a Knowledge Base by the flow's NiFi version
# ---------------------------------------------------------------------------


def test_the_nifi_version_of_a_flow_is_its_most_common_org_apache_nifi_bundle(tmp_path):
    flow = _flow([{"type": "a"}, {"type": "b"}], version="1.28.1")
    custom = {"group": "org.qubership.nifi", "artifact": "custom-nar", "version": "2.10.0"}
    flow["flowContents"]["controllerServices"] = [
        {"type": "c1", "bundle": custom}, {"type": "c2", "bundle": custom},
        {"type": "c3", "bundle": custom}]
    path = tmp_path / "flow.json"
    path.write_text(json.dumps(flow), encoding="utf-8")
    assert kb.flow_nifi_version(path) == "1.28.1"


def test_a_flow_with_no_org_apache_nifi_bundle_has_no_version(tmp_path):
    path = tmp_path / "flow.json"
    path.write_text(json.dumps({"flowContents": {"processors": []}}), encoding="utf-8")
    assert kb.flow_nifi_version(path) is None


@pytest.fixture
def workspace(tmp_path, monkeypatch):
    """A workspace rooted at tmp_path/ws, with no KB path or project dir from the environment."""
    root = tmp_path / "ws"
    (root / ".git").mkdir(parents=True)
    monkeypatch.delenv("NIFI_KB_PATH", raising=False)
    monkeypatch.delenv("CLAUDE_PROJECT_DIR", raising=False)
    monkeypatch.chdir(root)
    return root.resolve()


def test_the_knowledge_base_matching_the_flow_version_is_chosen(workspace):
    _write_kb(workspace / "kb-1", "1.28.1", [])
    _write_kb(workspace / "kb-2", "2.10.0", [])
    chosen = kb.discover(search_from=workspace, flow_version="1.28.1")
    assert chosen == workspace / "kb-1"


def test_several_knowledge_bases_and_no_version_match_is_an_error(workspace):
    _write_kb(workspace / "kb-1", "1.28.1", [])
    _write_kb(workspace / "kb-2", "2.10.0", [])
    with pytest.raises(kb.KbError, match="Several Knowledge Bases"):
        kb.discover(search_from=workspace, flow_version="1.27.0")


def test_a_knowledge_base_next_to_a_flow_inside_the_workspace_is_found(workspace):
    # Three levels below the workspace root, so only the scan of the flow's directory reaches it.
    flows = workspace / "a" / "b" / "flows"
    _write_kb(flows / "kb", "2.10.0", [])
    assert kb.discover(search_from=flows) == flows / "kb"


def test_a_knowledge_base_next_to_a_flow_outside_the_workspace_is_not_found(workspace, tmp_path):
    outside = tmp_path / "elsewhere"
    _write_kb(outside / "kb", "2.10.0", [])
    with pytest.raises(kb.KbError, match="does not leave the workspace"):
        kb.discover(search_from=outside)


def test_claude_project_dir_sets_the_workspace_boundary(workspace, tmp_path, monkeypatch):
    project = tmp_path / "project"
    _write_kb(project / "kb", "2.10.0", [])
    _write_kb(workspace / "kb", "1.28.1", [])
    monkeypatch.setenv("CLAUDE_PROJECT_DIR", str(project))
    assert kb.discover() == (project / "kb").resolve()


def test_the_workspace_is_the_nearest_ancestor_of_the_current_directory_with_git(
        workspace, monkeypatch):
    deeper = workspace / "sub" / "dir"
    deeper.mkdir(parents=True)
    monkeypatch.chdir(deeper)
    assert kb._workspace_root() == workspace


def test_the_workspace_is_the_current_directory_when_no_ancestor_has_git(
        tmp_path, monkeypatch):
    # The drive or filesystem root has no ancestors, so its own .git is the only one checked.
    anchor = Path(tmp_path.anchor)
    if (anchor / ".git").exists():
        pytest.skip("%s holds a .git entry, so it is a repository root" % anchor)
    monkeypatch.delenv("CLAUDE_PROJECT_DIR", raising=False)
    monkeypatch.setattr(kb.Path, "cwd", classmethod(lambda cls: anchor))
    assert kb._workspace_root() == anchor.resolve()


# ---------------------------------------------------------------------------
# Checks whose outcome depends on the version or on the property
# ---------------------------------------------------------------------------


class _OneDefinitionKb:
    """Serves one definition for any entry, for checks that read nothing else."""

    def __init__(self, definition):
        self._definition = definition

    def definition(self, entry):
        return {"definition": self._definition}


@pytest.mark.parametrize("sensitive, warnings, errors", [
    pytest.param(True, 1, 0, id="sensitive"),
    pytest.param(False, 0, 1, id="not sensitive"),
])
def test_a_required_property_with_no_value_is_a_warning_only_when_sensitive(
        sensitive, warnings, errors):
    definition = {"propertyDescriptors": {
        "Password": {"name": "Password", "required": True, "sensitive": sensitive}}}
    report = kb.Report()
    kb._check_properties(_OneDefinitionKb(definition), report, "where", {"properties": {}},
                         {"type": "org.example.P"})
    assert (len(report.warnings), len(report.errors)) == (warnings, errors)
    assert "required property 'Password'" in (report.warnings + report.errors)[0][1]


class _VersionOnlyKb:
    def __init__(self, version):
        self.nifi_version = version


@pytest.mark.parametrize("version, expected", [
    pytest.param("1.28.1", None, id="1.x port has no portFunction"),
    pytest.param("2.10.0", "STANDARD", id="2.x port is a standard port"),
])
def test_normalize_writes_port_function_only_for_2x(version, expected):
    group = {"identifier": "g", "inputPorts": [{"identifier": "p", "name": "In"}]}
    kb.normalize_group(_VersionOnlyKb(version), group)
    assert group["inputPorts"][0].get("portFunction") == expected


def _validate(tmp_path, capsys, kb_version, processor, type_name="org.example.P",
              input_requirement="INPUT_ALLOWED", flow=None):
    definition = {"definitionFormat": "normalized-nifi-1x",
                  "definition": _processor_1x(type=type_name, supportedRelationships=[],
                                              inputRequirement=input_requirement)}
    knowledge_base = kb.Kb(
        _write_kb(tmp_path / "kb", kb_version, [("PROCESSOR", type_name, definition)]))
    if flow is None:
        flow = _flow([dict(processor, type=type_name)], version=kb_version)
    kb.normalize_group(knowledge_base, flow["flowContents"])
    path = tmp_path / "flow.json"
    path.write_text(json.dumps(flow), encoding="utf-8")
    capsys.readouterr()
    kb.cmd_validate(knowledge_base, argparse.Namespace(flow=str(path)))
    return capsys.readouterr().out


def test_primary_node_only_is_a_deprecation_warning_on_1x(tmp_path, capsys):
    out = _validate(tmp_path, capsys, "1.28.1", {
        "identifier": "43c7acbe-5c5a-431e-980e-caf91e2ac6cf", "name": "P",
        "schedulingStrategy": "PRIMARY_NODE_ONLY", "runDurationMillis": 25})
    assert "WARN   flowContents.processors[P]: schedulingStrategy 'PRIMARY_NODE_ONLY' is " \
           "deprecated in NiFi 1.x" in out
    assert "0 error(s)" in out


def test_event_driven_on_a_processor_that_is_not_event_driven_is_an_error(tmp_path, capsys):
    out = _validate(tmp_path, capsys, "1.28.1", {
        "identifier": "43c7acbe-5c5a-431e-980e-caf91e2ac6cf", "name": "P",
        "schedulingStrategy": "EVENT_DRIVEN", "runDurationMillis": 25})
    assert "ERROR  flowContents.processors[P]: schedulingStrategy 'EVENT_DRIVEN' is not " \
           "supported. Available: TIMER_DRIVEN, CRON_DRIVEN." in out


@pytest.mark.parametrize("type_name, warned, listener_advice", [
    pytest.param("org.apache.nifi.processors.standard.ListenHTTP", False, False,
                 id="a listed listener"),
    pytest.param("org.apache.nifi.processors.kafka.pubsub.ConsumeKafkaRecord_2_6", False, False,
                 id="a listed pubsub Kafka consumer"),
    pytest.param("org.apache.nifi.processors.standard.HandleHttpRequest", True, True,
                 id="HandleHttpRequest"),
    pytest.param("org.apache.nifi.processors.standard.ListenTCP", True, True,
                 id="a listener missing from the list"),
    pytest.param("org.example.ConsumeThing", True, False,
                 id="a consumer missing from the list"),
    pytest.param("org.example.GetThing", True, False, id="a polling source"),
    pytest.param("org.example.ListThing", True, False, id="a lister is still a polling source"),
])
def test_a_zero_period_source_is_warned_about_unless_it_is_a_listed_type(
        tmp_path, capsys, type_name, warned, listener_advice):
    out = _validate(tmp_path, capsys, "1.28.1", {
        "identifier": "43c7acbe-5c5a-431e-980e-caf91e2ac6cf", "name": "P",
        "schedulingPeriod": "0 sec", "runDurationMillis": 25},
        type_name=type_name, input_requirement="INPUT_FORBIDDEN")
    assert ("source processor scheduled every '0 sec'" in out) is warned, out
    assert ("Set the run schedule to '50 millis'" in out) is listener_advice, out


def _parent_and_child_flow(child_context):
    """A flow whose root is bound to `ctx` and whose child group references #{db.url}."""
    child = {"identifier": "5d8f8a51-1f7e-4c2a-9a55-3c0f6f1b2d10", "name": "Child",
             "parameterContextName": child_context,
             "processors": [{"identifier": "43c7acbe-5c5a-431e-980e-caf91e2ac6cf", "name": "P",
                             "type": "org.example.P", "runDurationMillis": 25,
                             "properties": {"p": "#{db.url}"},
                             "bundle": {"group": "org.apache.nifi",
                                        "artifact": "nifi-standard-nar", "version": "1.28.1"}}]}
    return {"flowContents": {"identifier": "b051f24c-f9ff-4fde-b160-9b0b84af3e96",
                             "parameterContextName": "ctx", "processors": [],
                             "processGroups": [child]},
            "parameterContexts": {"ctx": {"name": "ctx", "parameters": [
                {"name": "db.url", "sensitive": False, "value": "x"}]}}}


def test_a_parameter_context_on_the_parent_group_does_not_bind_a_child_group(tmp_path, capsys):
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=_parent_and_child_flow(None))
    assert "ERROR  flowContents.Child: properties reference parameter(s) db.url but no " \
           "parameterContextName is set on this group" in out


def test_a_child_group_bound_to_its_own_parameter_context_is_valid(tmp_path, capsys):
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=_parent_and_child_flow("ctx"))
    assert "flowContents.Child: properties reference parameter(s)" not in out


@pytest.mark.parametrize("period, strategy, invalid, source_warned", [
    pytest.param("0", "TIMER_DRIVEN", True, False, id="a bare 0 has no unit"),
    pytest.param("0 sec", "TIMER_DRIVEN", False, True, id="zero with a unit"),
    pytest.param("0ms", "TIMER_DRIVEN", False, True, id="zero with an unspaced unit"),
    pytest.param("100 millis", "TIMER_DRIVEN", False, False, id="a real period"),
    pytest.param("10 micros", "TIMER_DRIVEN", False, False, id="a less common unit"),
    pytest.param("1 Milli", "TIMER_DRIVEN", False, False, id="a unit in mixed case"),
    pytest.param("0.5 sec", "TIMER_DRIVEN", False, False, id="a decimal period"),
    pytest.param("5 fortnights", "TIMER_DRIVEN", True, False, id="an unknown unit"),
    pytest.param("* * * * * ?", "CRON_DRIVEN", False, False, id="a cron expression"),
])
def test_a_timer_driven_period_is_checked_for_a_time_unit(
        tmp_path, capsys, period, strategy, invalid, source_warned):
    out = _validate(tmp_path, capsys, "1.28.1", {
        "identifier": "43c7acbe-5c5a-431e-980e-caf91e2ac6cf", "name": "P",
        "schedulingStrategy": strategy, "schedulingPeriod": period, "runDurationMillis": 25},
        type_name="org.example.GetThing", input_requirement="INPUT_FORBIDDEN")
    assert ("is not a valid time duration" in out) is invalid, out
    assert ("source processor scheduled every" in out) is source_warned, out


ROOT = {"identifier": "g", "processors": []}


@pytest.mark.parametrize("doc", [
    pytest.param({"flowContents": ROOT}, id="NiFi download"),
    pytest.param({"flowContents": ROOT, "snapshotMetadata": {"version": 3}}, id="Registry export"),
])
def test_root_group_accepts_a_top_level_flow_contents(doc):
    assert kb.root_group(doc) == (ROOT, doc)


@pytest.mark.parametrize("doc", [
    pytest.param({"versionedFlowSnapshot": {"flowContents": ROOT}},
                 id="VersionedFlowSnapshotEntity"),
    pytest.param({"snapshot": {"flowContents": ROOT}}, id="snapshot wrapper"),
    pytest.param(ROOT, id="bare process group"),
    pytest.param({"flowContents": None}, id="null flowContents"),
])
def test_root_group_refuses_anything_without_a_top_level_flow_contents(doc):
    with pytest.raises(kb.KbError, match="top-level 'flowContents'"):
        kb.root_group(doc)


# ---------------------------------------------------------------------------
# Child process groups: size, ports, connections across a boundary, service scope
# ---------------------------------------------------------------------------

ROOT_ID = "b051f24c-f9ff-4fde-b160-9b0b84af3e96"
CHILD_ID = "5d8f8a51-1f7e-4c2a-9a55-3c0f6f1b2d10"
SOURCE_ID = "38cc498e-6284-481e-a2a0-ef7dd961740f"
SINK_ID = "f75c0f2a-f002-42df-9003-b2dbcd0523bf"
INNER_ID = "cb9336e7-fced-4124-a99c-3768b66abed8"
IN_PORT_ID = "df03ecd1-b095-404b-bb4a-b08ab0e3be41"
OUT_PORT_ID = "4543f24f-fbd2-4461-96a7-b98ef5d83375"
CONNECTION_IDS = ["92b38ac3-399a-451d-90eb-2912ece98136", "e39f266b-0944-48c2-8488-8a2e554f8c71",
                  "5c2dc5b0-d93e-43ba-bc4e-8b391747663b", "4b6237f8-0cdf-41a8-aad4-18e85baf5194"]


def _processor(identifier, name, x=0.0, y=0.0):
    return {"identifier": identifier, "name": name, "type": "org.example.P", "runDurationMillis": 25,
            "position": {"x": x, "y": y},
            "bundle": {"group": "org.apache.nifi", "artifact": "nifi-standard-nar",
                       "version": "1.28.1"}}


def _port(identifier, name, x=0.0, y=0.0):
    return {"identifier": identifier, "name": name, "position": {"x": x, "y": y}}


def _connection(identifier, source, destination):
    """`source` and `destination` are (component id, group id) pairs."""
    return {"identifier": identifier, "selectedRelationships": [],
            "source": {"id": source[0], "groupId": source[1]},
            "destination": {"id": destination[0], "groupId": destination[1]}}


def _nested_flow(root_connections=None, input_ports=None):
    """Root: Source -> child `in_items` -> Inner -> child `out_items` -> Sink."""
    if root_connections is None:
        root_connections = [
            _connection(CONNECTION_IDS[0], (SOURCE_ID, ROOT_ID), (IN_PORT_ID, CHILD_ID)),
            _connection(CONNECTION_IDS[1], (OUT_PORT_ID, CHILD_ID), (SINK_ID, ROOT_ID)),
        ]
    child = {"identifier": CHILD_ID, "name": "Child", "position": {"x": 0.0, "y": 400.0},
             "processors": [_processor(INNER_ID, "Inner", 0.0, 300.0)],
             "inputPorts": input_ports or [_port(IN_PORT_ID, "in_items")],
             "outputPorts": [_port(OUT_PORT_ID, "out_items", 0.0, 700.0)],
             "connections": [
                 _connection(CONNECTION_IDS[2], (IN_PORT_ID, CHILD_ID), (INNER_ID, CHILD_ID)),
                 _connection(CONNECTION_IDS[3], (INNER_ID, CHILD_ID), (OUT_PORT_ID, CHILD_ID)),
             ]}
    return {"flowContents": {"identifier": ROOT_ID, "name": "Root",
                             "processors": [_processor(SOURCE_ID, "Source"),
                                            _processor(SINK_ID, "Sink", 0.0, 900.0)],
                             "processGroups": [child], "connections": root_connections}}


def test_a_child_group_wired_through_its_ports_is_valid(tmp_path, capsys):
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=_nested_flow())
    assert "0 error(s), 0 warning(s)" in out, out


def test_a_connection_from_the_parent_into_a_processor_of_a_child_is_an_error(tmp_path, capsys):
    flow = _nested_flow(root_connections=[
        _connection(CONNECTION_IDS[0], (SOURCE_ID, ROOT_ID), (INNER_ID, CHILD_ID)),
        _connection(CONNECTION_IDS[1], (OUT_PORT_ID, CHILD_ID), (SINK_ID, ROOT_ID)),
    ])
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=flow)
    assert "ERROR  flowContents.connections[unnamed]: the destination %s is in " \
           "flowContents.Child, but this connection belongs to flowContents." % INNER_ID in out, out


def test_an_endpoint_group_id_naming_the_wrong_group_is_an_error(tmp_path, capsys):
    flow = _nested_flow(root_connections=[
        _connection(CONNECTION_IDS[0], (SOURCE_ID, ROOT_ID), (IN_PORT_ID, ROOT_ID)),
        _connection(CONNECTION_IDS[1], (OUT_PORT_ID, CHILD_ID), (SINK_ID, ROOT_ID)),
    ])
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=flow)
    assert "destination.groupId is %s, but component %s is in flowContents.Child" \
           % (ROOT_ID, IN_PORT_ID) in out, out


def test_leaving_a_child_through_its_input_port_is_an_error(tmp_path, capsys):
    flow = _nested_flow(root_connections=[
        _connection(CONNECTION_IDS[0], (SOURCE_ID, ROOT_ID), (IN_PORT_ID, CHILD_ID)),
        _connection(CONNECTION_IDS[1], (IN_PORT_ID, CHILD_ID), (SINK_ID, ROOT_ID)),
    ])
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=flow)
    assert "the source is INPUT_PORT of child group flowContents.Child" in out, out


def test_two_input_ports_with_one_name_in_a_group_are_an_error(tmp_path, capsys):
    flow = _nested_flow(input_ports=[
        {"identifier": IN_PORT_ID, "name": "in_items"},
        {"identifier": "1a259b26-b48a-4b31-af44-898bacd1a528", "name": "in_items"},
    ])
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=flow)
    assert "ERROR  flowContents.Child: two input ports are named 'in_items'" in out, out


@pytest.mark.parametrize("collection, name, warned", [
    pytest.param("inputPorts", "items", True, id="input port without in_"),
    pytest.param("inputPorts", "in_items", False, id="input port with in_"),
    pytest.param("inputPorts", "out_items", True, id="input port with out_"),
    pytest.param("outputPorts", "items", True, id="output port without out_"),
    pytest.param("outputPorts", "out_items", False, id="output port with out_"),
])
def test_a_port_name_without_its_direction_prefix_is_warned_about(collection, name, warned):
    report = kb.Report()
    kb._check_port_names(report, "flowContents.Child",
                         {collection: [{"identifier": IN_PORT_ID, "name": name}]})
    assert any("does not follow the naming convention" in message
               for _, message in report.warnings) is warned, report.warnings


def test_a_child_port_with_no_connection_in_the_parent_is_an_error(tmp_path, capsys):
    flow = _nested_flow(root_connections=[
        _connection(CONNECTION_IDS[1], (OUT_PORT_ID, CHILD_ID), (SINK_ID, ROOT_ID)),
    ])
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=flow)
    assert "ERROR  flowContents.Child: input port 'in_items' has no connection in the " \
           "parent group" in out, out


def test_a_root_group_port_needs_no_connection_in_a_parent(tmp_path, capsys):
    flow = {"flowContents": {
        "identifier": ROOT_ID, "name": "Root",
        "processors": [_processor(SOURCE_ID, "Source", 0.0, 200.0)],
        "inputPorts": [_port(IN_PORT_ID, "in_items")],
        "connections": [_connection(CONNECTION_IDS[0], (IN_PORT_ID, ROOT_ID), (SOURCE_ID, ROOT_ID))],
    }}
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=flow)
    assert "0 error(s), 0 warning(s)" in out, out


@pytest.mark.parametrize("count, warned", [
    pytest.param(12, False, id="12 processors"),
    pytest.param(13, True, id="13 processors"),
])
def test_a_group_with_more_than_12_processors_is_warned_about(count, warned):
    report = kb.Report()
    kb._check_group_size(report, "flowContents",
                         {"processors": [{"name": "P%d" % n} for n in range(count)]})
    assert any("processors in one process group" in message
               for _, message in report.warnings) is warned, report.warnings


class _PoolKb(_OneDefinitionKb):
    """A processor with one property that takes an org.example.Pool service."""

    def __init__(self):
        super().__init__({"propertyDescriptors": {"Pool": {
            "name": "Pool", "typeProvidedByValue": {"type": "org.example.Pool"}}}})

    def lookup(self, kind, type_name):
        return {"controllerServiceApis": ["org.example.Pool"]}


@pytest.mark.parametrize("service_group, errors", [
    pytest.param(ROOT_ID, 0, id="service in the parent group"),
    pytest.param(CHILD_ID, 0, id="service in the same group"),
    pytest.param("c36ae982-4ddd-493f-8ad8-a2d2ae278585", 1, id="service in a sibling group"),
])
def test_a_service_is_visible_from_its_own_group_and_its_descendants(service_group, errors):
    report = kb.Report()
    service_id = "38fbf344-04e2-4ec4-a8d1-6b97576ce68c"
    kb._check_service_refs(
        _PoolKb(), report, "flowContents.Child.processors[P]",
        {"properties": {"Pool": service_id}}, {"type": "org.example.P"},
        {service_id: {"name": "Pool A", "type": "org.example.PoolImpl"}}, {},
        ({CHILD_ID, ROOT_ID}, {service_id: (service_group, "flowContents.Other")}))
    assert len(report.errors) == errors, report.errors


# ---------------------------------------------------------------------------
# Canvas layout: bends added by normalize, overlaps reported by validate
# ---------------------------------------------------------------------------

# Processor boxes are 352 x 128, so a processor at y=0 is centered on (176, 64).
TOP, BOTTOM, MIDDLE = "a-top", "c-bottom", "b-middle"


def _layout(connections, processors=((TOP, 0.0), (BOTTOM, 800.0)), groups=()):
    return {"identifier": ROOT_ID, "name": "Root", "connections": connections,
            "processors": [_processor(identifier, identifier, 0.0, y) for identifier, y in processors],
            "processGroups": list(groups)}


def _between(source, destination, *identifiers):
    return [_connection(identifier, (source, ROOT_ID), (destination, ROOT_ID))
            for identifier in identifiers]


def _bend_x(connection):
    return [bend["x"] for bend in connection.get("bends", [])]


def test_a_single_unobstructed_connection_stays_straight():
    group = _layout(_between(TOP, BOTTOM, "c1"))
    assert kb.route_group(group) == 0
    assert group["connections"][0].get("bends", []) == []


def test_two_connections_between_the_same_processors_are_pushed_to_opposite_sides():
    group = _layout(_between(TOP, BOTTOM, "c1", "c2"))
    assert kb.route_group(group) == 2
    first, second = group["connections"]
    # The line runs vertically through x=176, so the offsets are horizontal: half of a
    # label's width (224) plus the 20 px gap between labels on each side.
    assert sorted([_bend_x(first), _bend_x(second)]) == [[54], [298]]


def test_the_middle_of_three_parallel_connections_stays_straight():
    group = _layout(_between(TOP, BOTTOM, "c1", "c2", "c3"))
    kb.route_group(group)
    first, middle, last = group["connections"]
    assert _bend_x(middle) == []
    assert sorted([_bend_x(first), _bend_x(last)]) == [[-68], [420]]


def test_connections_between_ports_of_the_same_two_child_groups_are_parallel():
    upper = {"identifier": "g-upper", "name": "Upper", "position": {"x": 0.0, "y": 0.0},
             "outputPorts": [_port("out_a", "out_a"), _port("out_b", "out_b")]}
    lower = {"identifier": "g-lower", "name": "Lower", "position": {"x": 0.0, "y": 800.0},
             "inputPorts": [_port("in_a", "in_a"), _port("in_b", "in_b")]}
    group = _layout([_connection("c1", ("out_a", "g-upper"), ("in_a", "g-lower")),
                     _connection("c2", ("out_b", "g-upper"), ("in_b", "g-lower"))],
                    processors=(), groups=(upper, lower))
    kb.route_group(group)
    # Group boxes are 384 wide, so both lines would run through x=192.
    assert sorted(_bend_x(c) for c in group["connections"]) == [[70], [314]]


def test_a_connection_through_another_processor_is_routed_around_it():
    group = _layout(_between(TOP, BOTTOM, "c1"),
                    processors=((TOP, 0.0), (MIDDLE, 400.0), (BOTTOM, 800.0)))
    kb.route_group(group)
    connection = group["connections"][0]
    assert connection["bends"], "TOP -> BOTTOM runs straight through MIDDLE"
    path = kb._connection_path(connection, kb._canvas_boxes(group))
    middle_box = (0.0, 400.0, 352.0, 128.0)
    assert [kb._segment_hits_box(a, b, middle_box) for a, b in zip(path, path[1:])] == [False, False]


def test_a_self_loop_gets_two_bends_right_of_its_processor():
    group = _layout(_between(TOP, TOP, "c1"), processors=((TOP, 0.0),))
    kb.route_group(group)
    # 352 (box) + 112 (half a label) + 40 (margin): the label on the first bend clears the box.
    assert group["connections"][0]["bends"] == [{"x": 504, "y": 24}, {"x": 504, "y": 104}]


def test_bends_already_on_a_connection_are_kept():
    connections = _between(TOP, BOTTOM, "c1", "c2")
    connections[0]["bends"] = [{"x": -300, "y": 400}]
    group = _layout(connections)
    kb.route_group(group)
    assert group["connections"][0]["bends"] == [{"x": -300, "y": 400}]


def test_routing_a_routed_group_changes_nothing():
    group = _layout(_between(TOP, BOTTOM, "c1", "c2", "c3"),
                    processors=((TOP, 0.0), (MIDDLE, 400.0), (BOTTOM, 800.0)))
    kb.route_group(group)
    routed = json.loads(json.dumps(group))
    assert kb.route_group(group) == 0
    assert group == routed


def _layout_warnings(group):
    report = kb.Report()
    kb._check_layout(report, "flowContents", group)
    return [message for _, message in report.warnings]


@pytest.mark.parametrize("processors, connections, expected", [
    pytest.param(((TOP, 0.0), (BOTTOM, 800.0)), _between(TOP, BOTTOM, "c1", "c2"),
                 "are drawn on top of each other", id="parallel connections"),
    pytest.param(((TOP, 0.0), (MIDDLE, 400.0), (BOTTOM, 800.0)), _between(TOP, BOTTOM, "c1"),
                 "passes through another component", id="line through a processor"),
    pytest.param(((TOP, 0.0),), _between(TOP, TOP, "c1"),
                 "loops back to its source with no bends", id="self-loop"),
    pytest.param(((TOP, 0.0), (BOTTOM, 100.0)), [],
                 "overlap on the canvas", id="overlapping processors"),
])
def test_validate_warns_about_what_hides_part_of_the_canvas(processors, connections, expected):
    warnings = _layout_warnings(_layout(connections, processors=processors))
    assert any(expected in message for message in warnings), warnings


def test_validate_finds_nothing_to_warn_about_once_a_group_is_routed():
    group = _layout(_between(TOP, BOTTOM, "c1", "c2") + _between(MIDDLE, MIDDLE, "c3"),
                    processors=((TOP, 0.0), (MIDDLE, 400.0), (BOTTOM, 800.0)))
    kb.route_group(group)
    assert _layout_warnings(group) == []


def test_a_bend_is_moved_until_its_label_clears_a_nearby_processor():
    # SIDE sits right of the straight TOP -> BOTTOM line, clear of the line itself, but a
    # label centered on the line's midpoint (176, 464) would reach x=288 and cover it.
    group = _layout(_between(TOP, BOTTOM, "c1"),
                    processors=((TOP, 0.0), (BOTTOM, 800.0)))
    group["processors"].append(_processor("side", "side", 260.0, 420.0))
    kb.route_group(group)
    path = kb._connection_path(group["connections"][0], kb._canvas_boxes(group))
    label = kb._label_box(path, 0)
    assert not kb._boxes_overlap(label, (260.0, 420.0, 352.0, 128.0)), label


@pytest.mark.parametrize("gap, warned", [
    pytest.param(50.0, True, id="50 px between the boxes"),
    pytest.param(300.0, False, id="300 px between the boxes"),
])
def test_validate_warns_when_a_label_does_not_fit_between_connected_processors(gap, warned):
    group = _layout(_between(TOP, BOTTOM, "c1"), processors=((TOP, 0.0), (BOTTOM, 128.0 + gap)))
    warnings = _layout_warnings(group)
    assert any("covers part of" in message for message in warnings) is warned, warnings


def test_validate_warns_when_two_labels_cover_each_other():
    connections = _between(TOP, BOTTOM, "c1", "c2")
    connections[0]["bends"] = [{"x": 150, "y": 464}]
    connections[1]["bends"] = [{"x": 200, "y": 464}]
    warnings = _layout_warnings(_layout(connections))
    assert any("cover each other" in message for message in warnings), warnings


# ---------------------------------------------------------------------------
# Scripted components: Script Body, classpath properties, the platform line
# ---------------------------------------------------------------------------

def _native_2x(type_name, descriptors):
    return {"definitionFormat": "native-nifi-2x", "definition": {
        "type": type_name, "inputRequirement": "INPUT_ALLOWED", "supportedRelationships": [],
        "supportedSchedulingStrategies": ["TIMER_DRIVEN"], "propertyDescriptors": descriptors}}


@pytest.mark.parametrize("artifact, key, reported", [
    pytest.param("nifi-groovyx-nar", "Script Body", False, id="groovyx script body"),
    pytest.param("nifi-scripting-nar", "Script Body", False, id="scripting script body"),
    pytest.param("nifi-groovyx-nar", "Other", True, id="another NONE property of a scripted one"),
    pytest.param("nifi-standard-nar", "Script Body", True, id="script body outside a scripting NAR"),
])
def test_el_in_script_body_of_a_scripted_component_is_not_reported(
        tmp_path, capsys, artifact, key, reported):
    type_name = "org.example.Script"
    definition = _native_2x(type_name, {
        name: {"name": name, "expressionLanguageScope": "NONE"} for name in ("Script Body", "Other")})
    knowledge_base = kb.Kb(_write_kb(tmp_path / "kb", "2.10.0", [
        ("PROCESSOR", type_name, definition, {"artifact": artifact})]))
    flow = _flow([{"type": type_name, "properties": {key: 'def s = "${name}"'},
                   "bundle": {"group": "org.apache.nifi", "artifact": artifact,
                              "version": "2.10.0"}}], version="2.10.0")
    kb.normalize_group(knowledge_base, flow["flowContents"])
    path = tmp_path / "flow.json"
    path.write_text(json.dumps(flow), encoding="utf-8")
    capsys.readouterr()
    kb.cmd_validate(knowledge_base, argparse.Namespace(flow=str(path)))
    out = capsys.readouterr().out
    assert ("contains Expression Language" in out) == reported, out


@pytest.mark.parametrize("resource, line", [
    pytest.param({"cardinality": "MULTIPLE", "resourceTypes": ["URL", "DIRECTORY", "FILE"]},
                 "    takes: URL, directory, or file (comma-separated list)", id="several kinds"),
    pytest.param({"cardinality": "SINGLE", "resourceTypes": ["FILE"]},
                 "    takes: file", id="one file"),
])
def test_props_names_the_resources_a_property_takes(tmp_path, capsys, resource, line):
    type_name = "org.example.Script"
    knowledge_base = kb.Kb(_write_kb(tmp_path, "2.10.0", [("PROCESSOR", type_name, _native_2x(
        type_name, {"Modules": {"name": "Modules", "resourceDefinition": resource}}))]))
    kb.cmd_props(knowledge_base, argparse.Namespace(name=type_name, kind=None))
    assert line in capsys.readouterr().out.splitlines()


def test_props_prints_no_resource_line_for_a_plain_property(tmp_path, capsys):
    type_name = "org.example.Script"
    knowledge_base = kb.Kb(_write_kb(tmp_path, "2.10.0", [("PROCESSOR", type_name, _native_2x(
        type_name, {"Plain": {"name": "Plain"}}))]))
    kb.cmd_props(knowledge_base, argparse.Namespace(name=type_name, kind=None))
    out = capsys.readouterr().out
    assert "takes:" not in out, out


@pytest.mark.parametrize("group, platform", [
    pytest.param("org.qubership.nifi", "qubership-nifi", id="qubership bundle"),
    pytest.param("org.apache.nifi", "Apache NiFi", id="apache bundles only"),
])
def test_locate_names_the_platform_from_the_bundle_groups(tmp_path, capsys, group, platform):
    type_name = "org.example.P"
    knowledge_base = kb.Kb(_write_kb(tmp_path, "2.10.0", [
        ("PROCESSOR", type_name, _native_2x(type_name, {}), {"group": group})]))
    kb.cmd_locate(knowledge_base, argparse.Namespace())
    out = capsys.readouterr().out
    platform_line = next(line for line in out.splitlines() if line.startswith("Platform"))
    assert platform in platform_line, out

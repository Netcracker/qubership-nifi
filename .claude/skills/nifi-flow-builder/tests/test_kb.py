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


def test_a_property_key_given_as_the_display_name_is_an_error():
    definition = {"propertyDescriptors": {
        "bootstrap.servers": {"name": "bootstrap.servers", "displayName": "Bootstrap Servers"}}}
    report = kb.Report()
    kb._check_properties(_OneDefinitionKb(definition), report, "where",
                         {"properties": {"Bootstrap Servers": "k:9092"}}, {"type": "org.example.P"})
    assert report.errors == [("where", "property key 'Bootstrap Servers' is the display name. "
                                       "NiFi matches on the descriptor name - use "
                                       "'bootstrap.servers'.")]


@pytest.mark.parametrize("dynamic, errors", [
    pytest.param(False, 1, id="no dynamic properties"),
    pytest.param(True, 0, id="dynamic properties"),
])
def test_an_unknown_property_key_is_an_error_only_without_dynamic_properties(dynamic, errors):
    definition = {"supportsDynamicProperties": dynamic,
                  "propertyDescriptors": {"Batch Size": {"name": "Batch Size"}}}
    report = kb.Report()
    kb._check_properties(_OneDefinitionKb(definition), report, "where",
                         {"properties": {"Batch Sizing": "10"}}, {"type": "org.example.P"})
    assert len(report.errors) == errors, report.errors
    if errors:
        assert "'Batch Sizing' is not a property of P and the component takes no dynamic " \
               "properties. Did you mean: Batch Size?" in report.errors[0][1]


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
              input_requirement="INPUT_ALLOWED", flow=None, normalize=True):
    definition = {"definitionFormat": "normalized-nifi-1x",
                  "definition": _processor_1x(type=type_name, supportedRelationships=[],
                                              inputRequirement=input_requirement)}
    knowledge_base = kb.Kb(
        _write_kb(tmp_path / "kb", kb_version, [("PROCESSOR", type_name, definition)]))
    if flow is None:
        flow = _flow([dict(processor, type=type_name)], version=kb_version)
    if normalize:
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


@pytest.mark.parametrize("role, connection", [
    pytest.param("destination", 0, id="into a child input port"),
    pytest.param("source", 1, id="out of a child output port"),
])
def test_a_child_port_endpoint_without_a_group_id_is_an_error(tmp_path, capsys, role, connection):
    flow = _nested_flow()
    del flow["flowContents"]["connections"][connection][role]["groupId"]
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=flow, normalize=False)
    assert "%s.groupId is missing. The %s is a port of child group flowContents.Child" \
           % (role, role) in out, out


def test_a_same_group_endpoint_without_a_group_id_is_valid(tmp_path, capsys):
    flow = _nested_flow()
    del flow["flowContents"]["connections"][0]["source"]["groupId"]
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=flow, normalize=False)
    assert "groupId is missing" not in out, out


REMOTE_ID = "0b6c1e57-3f0a-4d2e-9c1b-7a5e8f2d4c31"
REMOTE_IN_ID = "a1e9d3c7-5b2f-4e8a-b6d0-3c7f9e1a2b45"
REMOTE_OUT_ID = "6e2b8f4d-1c9a-4a7e-8d3b-5f0c2e9a7b16"


def _remote_flow(to_remote=(REMOTE_IN_ID, REMOTE_ID), from_remote=(REMOTE_OUT_ID, REMOTE_ID)):
    """Root: Source -> remote input port; remote output port -> Sink."""
    remote = {"identifier": REMOTE_ID, "name": "Remote", "position": {"x": 0.0, "y": 400.0},
              "targetUris": "https://remote:8443/nifi",
              "inputPorts": [{"identifier": REMOTE_IN_ID, "name": "in_items",
                              "remoteGroupId": REMOTE_ID, "componentType": "REMOTE_INPUT_PORT"}],
              "outputPorts": [{"identifier": REMOTE_OUT_ID, "name": "out_items",
                               "remoteGroupId": REMOTE_ID,
                               "componentType": "REMOTE_OUTPUT_PORT"}]}
    return {"flowContents": {"identifier": ROOT_ID, "name": "Root",
                             "processors": [_processor(SOURCE_ID, "Source"),
                                            _processor(SINK_ID, "Sink", 0.0, 900.0)],
                             "remoteProcessGroups": [remote],
                             "connections": [
                                 _connection(CONNECTION_IDS[0], (SOURCE_ID, ROOT_ID), to_remote),
                                 _connection(CONNECTION_IDS[1], from_remote, (SINK_ID, ROOT_ID)),
                             ]}}


def test_a_flow_through_the_ports_of_a_remote_process_group_is_valid(tmp_path, capsys):
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=_remote_flow())
    assert "ERROR" not in out, out


def test_sending_to_a_remote_output_port_is_an_error(tmp_path, capsys):
    flow = _remote_flow(to_remote=(REMOTE_OUT_ID, REMOTE_ID))
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=flow)
    assert "the destination is REMOTE_OUTPUT_PORT of remote process group %s" % REMOTE_ID \
        in out, out


def test_receiving_from_a_remote_input_port_is_an_error(tmp_path, capsys):
    flow = _remote_flow(from_remote=(REMOTE_IN_ID, REMOTE_ID))
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=flow)
    assert "the source is REMOTE_INPUT_PORT of remote process group %s" % REMOTE_ID in out, out


def test_a_remote_port_endpoint_without_a_group_id_is_a_warning(tmp_path, capsys):
    flow = _remote_flow()
    del flow["flowContents"]["connections"][0]["destination"]["groupId"]
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=flow, normalize=False)
    assert "WARN   flowContents.connections[unnamed]: destination.groupId is missing. The " \
           "destination is a port of remote process group %s" % REMOTE_ID in out, out


def test_a_remote_port_endpoint_whose_group_id_is_not_its_remote_group_is_an_error(
        tmp_path, capsys):
    flow = _remote_flow(to_remote=(REMOTE_IN_ID, ROOT_ID))
    out = _validate(tmp_path, capsys, "1.28.1", None, flow=flow)
    assert "destination.groupId is %s, but the destination is a port of remote process group %s" \
        % (ROOT_ID, REMOTE_ID) in out, out


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


class _OtherApiKb(_PoolKb):
    """Like _PoolKb, but every service implements org.example.Other instead of Pool."""

    def lookup(self, kind, type_name):
        return {"controllerServiceApis": ["org.example.Other"]}


def test_a_service_that_does_not_implement_the_required_api_is_an_error():
    report = kb.Report()
    service_id = "38fbf344-04e2-4ec4-a8d1-6b97576ce68c"
    kb._check_service_refs(
        _OtherApiKb(), report, "where", {"properties": {"Pool": service_id}},
        {"type": "org.example.P"},
        {service_id: {"name": "Cache", "type": "org.example.CacheImpl"}}, {})
    assert report.errors == [("where", "property 'Pool' requires Pool, but 'Cache' is a "
                                       "CacheImpl, which does not implement it.")]


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


def test_a_matching_knowledge_base_at_the_workspace_root_beats_a_mismatched_one_by_the_flow(
        workspace):
    flows = workspace / "a" / "b" / "flows"
    _write_kb(flows / "kb", "1.28.1", [])
    _write_kb(workspace / "kb", "2.10.0", [])
    assert kb.discover(search_from=flows, flow_version="2.10.0") == workspace / "kb"


def test_the_knowledge_base_nearest_the_flow_wins_without_a_version_match(workspace):
    flows = workspace / "a" / "b" / "flows"
    _write_kb(flows / "kb", "1.28.1", [])
    _write_kb(workspace / "kb", "2.10.0", [])
    assert kb.discover(search_from=flows, flow_version="1.27.0") == flows / "kb"


# ---------------------------------------------------------------------------
# Relationships, parameters, and Script Body, on a native 2.x definition
# ---------------------------------------------------------------------------

PROCESSOR_ID = "43c7acbe-5c5a-431e-980e-caf91e2ac6cf"
ROUTE_SINK_ID = "7f3a9d0e-2b1c-4e5f-8a6b-0c9d8e7f6a5b"


def _validate_2x(tmp_path, capsys, definition, processor, connections=(), parameters=None,
                 artifact="nifi-standard-nar"):
    """Validate a flow of one processor, plus a sink when `connections` need a destination.

    `parameters` is a list of parameters for a context `ctx` bound to the root group.
    """
    type_name = definition["definition"]["type"]
    sink = _native_2x("org.example.Sink", {})
    knowledge_base = kb.Kb(_write_kb(tmp_path / "kb", "2.10.0", [
        ("PROCESSOR", type_name, definition, {"artifact": artifact}),
        ("PROCESSOR", "org.example.Sink", sink)]))
    processors = [dict({"identifier": PROCESSOR_ID, "name": "P", "type": type_name,
                        "runDurationMillis": 25,
                        "bundle": {"group": "org.apache.nifi", "artifact": artifact,
                                   "version": "2.10.0"}}, **processor)]
    if connections:
        processors.append({"identifier": ROUTE_SINK_ID, "name": "Sink", "type": "org.example.Sink",
                           "runDurationMillis": 25, "position": {"x": 0.0, "y": 600.0}})
    flow = _flow(processors, version="2.10.0")
    flow["flowContents"]["connections"] = [
        {"identifier": "c%d000000-0000-4000-8000-000000000000" % number,
         "source": {"id": PROCESSOR_ID, "type": "PROCESSOR"},
         "destination": {"id": ROUTE_SINK_ID, "type": "PROCESSOR"},
         "selectedRelationships": list(relationships)}
        for number, relationships in enumerate(connections)]
    if parameters is not None:
        flow["flowContents"]["parameterContextName"] = "ctx"
        flow["parameterContexts"] = {"ctx": {"name": "ctx", "parameters": parameters}}
    kb.normalize_group(knowledge_base, flow["flowContents"])
    path = tmp_path / "flow.json"
    path.write_text(json.dumps(flow), encoding="utf-8")
    capsys.readouterr()
    kb.cmd_validate(knowledge_base, argparse.Namespace(flow=str(path)))
    return capsys.readouterr().out


def test_el_in_the_pre_2_7_groovy_script_body_key_is_not_reported(tmp_path, capsys):
    # Before NiFi 2.7, ExecuteGroovyScript names the property groovyx-script-body.
    definition = _native_2x("org.apache.nifi.processors.groovyx.ExecuteGroovyScript", {
        "groovyx-script-body": {"name": "groovyx-script-body", "displayName": "Script Body",
                                "expressionLanguageScope": "NONE"}})
    out = _validate_2x(tmp_path, capsys, definition,
                       {"properties": {"groovyx-script-body": 'def s = "${name}"'}},
                       artifact="nifi-groovyx-nar")
    assert "contains Expression Language" not in out, out


def _router(simple, relationships, controlling=None, default=None):
    """A processor with dynamic relationships, and optionally the property that shapes them."""
    descriptors = {}
    if controlling:
        descriptors[controlling] = {"name": controlling, "displayName": controlling,
                                    "expressionLanguageScope": "NONE", "defaultValue": default}
    definition = _native_2x("org.apache.nifi.processors.standard." + simple, descriptors)
    definition["definition"].update(
        supportedRelationships=[{"name": name} for name in relationships],
        supportsDynamicProperties=True, supportsDynamicRelationships=True)
    return definition


def _route_on_attribute():
    return _router("RouteOnAttribute", ["unmatched"], "Routing Strategy", "Route to Property name")


def _route_text():
    return _router("RouteText", ["original", "unmatched"], "Routing Strategy",
                   "Route to each matching Property Name")


BY_PROPERTY_NAME = [
    pytest.param(_route_on_attribute, {}, ["unmatched"], id="RouteOnAttribute by default"),
    pytest.param(_route_on_attribute, {"Routing Strategy": "Route to Property name"},
                 ["unmatched"], id="RouteOnAttribute to property name"),
    pytest.param(_route_text, {"Routing Strategy": "Route to each matching Property Name"},
                 ["original", "unmatched"], id="RouteText to each matching property name"),
    pytest.param(lambda: _router("QueryRecord", ["original", "failure"]), {},
                 ["original", "failure"], id="QueryRecord"),
]

TO_MATCHED = [
    pytest.param(_route_on_attribute, "Route to 'matched' if all match", ["unmatched"],
                 id="RouteOnAttribute if all match"),
    pytest.param(_route_on_attribute, "Route to 'matched' if any matches", ["unmatched"],
                 id="RouteOnAttribute if any matches"),
    pytest.param(_route_text, "Route to 'matched' if line matches all conditions",
                 ["original", "unmatched"], id="RouteText if all conditions match"),
]


@pytest.mark.parametrize("router, strategy, fixed", BY_PROPERTY_NAME)
def test_a_relationship_named_after_a_dynamic_property_must_be_handled(
        tmp_path, capsys, router, strategy, fixed):
    out = _validate_2x(tmp_path, capsys, router(),
                       {"properties": dict(strategy, big="${fileSize:gt(10)}"),
                        "autoTerminatedRelationships": fixed})
    assert "relationship 'big' is neither connected nor auto-terminated" in out, out


@pytest.mark.parametrize("router, strategy, fixed", BY_PROPERTY_NAME)
@pytest.mark.parametrize("auto_terminated, connections", [
    pytest.param(["big"], (), id="auto-terminated"),
    pytest.param([], (["big"],), id="connected"),
])
def test_a_handled_relationship_named_after_a_dynamic_property_is_clean(
        tmp_path, capsys, router, strategy, fixed, auto_terminated, connections):
    out = _validate_2x(tmp_path, capsys, router(),
                       {"properties": dict(strategy, big="${fileSize:gt(10)}"),
                        "autoTerminatedRelationships": fixed + auto_terminated}, connections)
    assert "0 error(s), 0 warning(s)" in out, out


@pytest.mark.parametrize("router, strategy, fixed", TO_MATCHED)
def test_routing_to_matched_requires_matched_and_not_the_dynamic_property(
        tmp_path, capsys, router, strategy, fixed):
    out = _validate_2x(tmp_path, capsys, router(),
                       {"properties": {"Routing Strategy": strategy, "big": "${fileSize:gt(10)}"},
                        "autoTerminatedRelationships": fixed})
    assert "relationship 'matched' is neither connected nor auto-terminated" in out, out
    assert "'big'" not in out, out


@pytest.mark.parametrize("router, strategy, fixed", TO_MATCHED)
def test_routing_to_matched_with_matched_handled_is_clean(
        tmp_path, capsys, router, strategy, fixed):
    out = _validate_2x(tmp_path, capsys, router(),
                       {"properties": {"Routing Strategy": strategy, "big": "${fileSize:gt(10)}"},
                        "autoTerminatedRelationships": fixed + ["matched"]})
    assert "0 error(s), 0 warning(s)" in out, out


def test_routing_to_matched_has_no_relationship_named_after_a_dynamic_property(tmp_path, capsys):
    out = _validate_2x(tmp_path, capsys, _route_on_attribute(),
                       {"properties": {"Routing Strategy": "Route to 'matched' if all match",
                                       "big": "${fileSize:gt(10)}"},
                        "autoTerminatedRelationships": ["unmatched", "matched", "big"]})
    assert "relationship 'big' does not exist on RouteOnAttribute" in out, out


def _distribute_load():
    return _router("DistributeLoad", ["1"], "Number of Relationships", "1")


@pytest.mark.parametrize("weights", [
    pytest.param({}, id="no weights"),
    pytest.param({"3": "2"}, id="a weight for one relationship"),
])
def test_distribute_load_has_one_relationship_per_number(tmp_path, capsys, weights):
    out = _validate_2x(tmp_path, capsys, _distribute_load(),
                       {"properties": dict(weights, **{"Number of Relationships": "5"}),
                        "autoTerminatedRelationships": ["1"]})
    for number in ("2", "3", "4", "5"):
        assert ("relationship '%s' is neither connected nor auto-terminated" % number) in out, out


def test_a_distribute_load_weight_creates_no_relationship(tmp_path, capsys):
    out = _validate_2x(tmp_path, capsys, _distribute_load(),
                       {"properties": {"Number of Relationships": "2", "7": "3"},
                        "autoTerminatedRelationships": ["1", "2"]})
    assert "0 error(s), 0 warning(s)" in out, out


def test_distribute_load_with_every_relationship_handled_is_clean(tmp_path, capsys):
    out = _validate_2x(tmp_path, capsys, _distribute_load(),
                       {"properties": {"Number of Relationships": "5", "3": "2"},
                        "autoTerminatedRelationships": ["1", "2", "3", "4", "5"]})
    assert "0 error(s), 0 warning(s)" in out, out


@pytest.mark.parametrize("router, processor, names", [
    pytest.param(lambda: _router("RouteSomehow", ["unmatched"]),
                 {"properties": {"big": "${fileSize:gt(10)}"},
                  "autoTerminatedRelationships": ["unmatched", "matched"]},
                 "big, matched", id="a processor the check does not know"),
    pytest.param(_route_on_attribute,
                 {"properties": {"Routing Strategy": "#{choice}", "big": "${fileSize:gt(10)}"},
                  "autoTerminatedRelationships": ["unmatched", "matched"]},
                 "big, matched", id="a routing strategy from a parameter"),
    pytest.param(_distribute_load,
                 {"properties": {"Number of Relationships": "#{choice}"},
                  "autoTerminatedRelationships": ["1", "2"]},
                 "2", id="a relationship count from a parameter"),
])
def test_an_unprovable_relationship_set_is_one_warning(tmp_path, capsys, router, processor,
                                                       names):
    out = _validate_2x(tmp_path, capsys, router(), processor,
                       parameters=[{"name": "choice", "value": "2"}])
    assert "0 error(s), 1 warning(s)" in out, out
    assert "cannot work out which ones from its configuration. These names may not match " \
           "them: %s." % names in out, out


def _static_router(type_description=""):
    definition = _native_2x("org.example.P", {})
    definition["definition"].update(supportedRelationships=[{"name": "success"}],
                                    typeDescription=type_description)
    return definition


def test_a_relationship_the_processor_does_not_have_is_an_error(tmp_path, capsys):
    out = _validate_2x(tmp_path, capsys, _static_router(),
                       {"autoTerminatedRelationships": ["success", "bogus"]})
    assert "relationship 'bogus' does not exist on P. Available: success." in out, out


def test_a_relationship_named_only_in_the_documentation_is_a_warning(tmp_path, capsys):
    definition = _static_router("Sends a record it cannot parse to the 'parse failure' "
                                "relationship.")
    out = _validate_2x(tmp_path, capsys, definition,
                       {"autoTerminatedRelationships": ["success", "parse failure"]})
    assert "0 error(s), 1 warning(s)" in out, out
    assert "relationship 'parse failure' is not in the catalog for P, but the documentation " \
           "names it." in out, out


def test_a_relationship_auto_terminated_and_connected_is_a_warning(tmp_path, capsys):
    definition = _native_2x("org.example.P", {})
    definition["definition"]["supportedRelationships"] = [{"name": "success"}]
    out = _validate_2x(tmp_path, capsys, definition,
                       {"autoTerminatedRelationships": ["success"]}, (["success"],))
    assert ("WARN   flowContents.processors[P]: relationship 'success' is auto-terminated and "
            "also connected. NiFi imports the connection and drops the auto-termination") in out
    assert "0 error(s)" in out


@pytest.mark.parametrize("reference, reported", [
    pytest.param("#{'db url'}", False, id="quoted name"),
    pytest.param("#{db url}", False, id="bare name with a space"),
    pytest.param("#{'other'}", True, id="quoted name the context does not declare"),
])
def test_a_quoted_parameter_reference_is_matched_without_its_quotes(
        tmp_path, capsys, reference, reported):
    definition = _native_2x("org.example.P", {"p": {"name": "p"}})
    out = _validate_2x(tmp_path, capsys, definition, {"properties": {"p": reference}},
                       parameters=[{"name": "db url", "sensitive": False, "value": "x"}])
    assert ("which context 'ctx' does not declare" in out) is reported, out


@pytest.mark.parametrize("property_sensitive, parameter_sensitive, reported", [
    pytest.param(True, False, True, id="sensitive property, non-sensitive parameter"),
    pytest.param(False, True, True, id="non-sensitive property, sensitive parameter"),
    pytest.param(True, True, False, id="both sensitive"),
    pytest.param(False, False, False, id="neither sensitive"),
])
def test_a_parameter_must_match_the_sensitivity_of_the_property_referencing_it(
        tmp_path, capsys, property_sensitive, parameter_sensitive, reported):
    definition = _native_2x("org.example.P", {
        "p": {"name": "p", "sensitive": property_sensitive}})
    out = _validate_2x(tmp_path, capsys, definition, {"properties": {"p": "#{secret}"}},
                       parameters=[{"name": "secret", "sensitive": parameter_sensitive,
                                    "value": "x"}])
    assert ("NiFi requires the property and the parameter to be both sensitive or both not"
            in out) is reported, out


# ---------------------------------------------------------------------------
# normalize: descriptors, endpoint groups, default positions, file handling
# ---------------------------------------------------------------------------


def _one_processor_kb(tmp_path):
    type_name = "org.example.P"
    return kb.Kb(_write_kb(tmp_path / "kb", "2.10.0", [("PROCESSOR", type_name, _native_2x(
        type_name, {"a": {"name": "a", "displayName": "A"},
                    "b": {"name": "b", "displayName": "B", "sensitive": True}}))]))


def test_normalize_adds_a_descriptor_for_a_new_property_and_keeps_the_existing_ones(tmp_path):
    group = {"identifier": "g", "processors": [{
        "identifier": "p", "type": "org.example.P", "properties": {"a": "1", "b": "2"},
        "propertyDescriptors": {"a": {"name": "a", "displayName": "Edited by hand"}}}]}
    kb.normalize_group(_one_processor_kb(tmp_path), group)
    assert group["processors"][0]["propertyDescriptors"] == {
        "a": {"name": "a", "displayName": "Edited by hand"},
        "b": {"name": "b", "displayName": "B", "identifiesControllerService": False,
              "sensitive": True, "dynamic": False}}


def test_normalize_names_the_group_of_each_connection_endpoint(tmp_path):
    group = {"identifier": "root",
             "processors": [{"identifier": "p", "type": "org.example.P"}],
             "processGroups": [{"identifier": "child", "inputPorts": [{"identifier": "in"}]}],
             "connections": [{"identifier": "c", "source": {"id": "p", "type": "PROCESSOR"},
                              "destination": {"id": "in", "type": "INPUT_PORT"}}]}
    kb.normalize_group(_one_processor_kb(tmp_path), group)
    connection = group["connections"][0]
    assert (connection["source"]["groupId"], connection["destination"]["groupId"]) == (
        "root", "child")


def test_normalize_names_the_remote_process_group_of_a_remote_port_endpoint(tmp_path):
    group = {"identifier": "root",
             "processors": [{"identifier": "p", "type": "org.example.P"}],
             "remoteProcessGroups": [{"identifier": "remote",
                                      "inputPorts": [{"identifier": "remote-in"}]}],
             "connections": [{"identifier": "c", "source": {"id": "p", "type": "PROCESSOR"},
                              "destination": {"id": "remote-in",
                                              "type": "REMOTE_INPUT_PORT"}}]}
    kb.normalize_group(_one_processor_kb(tmp_path), group)
    assert group["connections"][0]["destination"]["groupId"] == "remote"


def test_normalize_spaces_new_components_on_the_skill_grid(tmp_path):
    group = {"identifier": "g",
             "processors": [{"identifier": "p1", "type": "org.example.P"},
                            {"identifier": "p2", "type": "org.example.P"}],
             "inputPorts": [{"identifier": "i1"}, {"identifier": "i2"}],
             "outputPorts": [{"identifier": "o1"}]}
    kb.normalize_group(_one_processor_kb(tmp_path), group)
    positions = [(c["identifier"], c["position"]) for collection in
                 ("processors", "inputPorts", "outputPorts") for c in group[collection]]
    assert positions == [
        ("p1", {"x": 0.0, "y": 0.0}), ("p2", {"x": 0.0, "y": 300.0}),
        ("i1", {"x": 0.0, "y": -300.0}), ("i2", {"x": 640.0, "y": -300.0}),
        ("o1", {"x": 0.0, "y": 600.0})]


def test_normalize_places_a_new_processor_below_an_exported_layout(tmp_path):
    group = {"identifier": "g",
             "processors": [{"identifier": "old", "type": "org.example.P",
                             "position": {"x": 400.0, "y": 800.0}},
                            {"identifier": "new", "type": "org.example.P"}],
             "funnels": [{"identifier": "f", "position": {"x": 0.0, "y": 1000.0}}],
             "outputPorts": [{"identifier": "o"}]}
    kb.normalize_group(_one_processor_kb(tmp_path), group)
    assert group["processors"][1]["position"] == {"x": 0.0, "y": 1300.0}
    assert group["outputPorts"][0]["position"] == {"x": 0.0, "y": 1600.0}
    assert _layout_warnings(group) == []


def test_normalize_writes_non_ascii_text_unescaped(tmp_path):
    name = "Zahlungseing\u00e4nge"
    path = tmp_path / "flow.json"
    path.write_text(json.dumps({"flowContents": {"identifier": "g", "name": name}}),
                    encoding="utf-8")
    kb.cmd_normalize(_one_processor_kb(tmp_path), argparse.Namespace(flow=str(path), output=None))
    assert '"name": "%s"' % name in path.read_text(encoding="utf-8")


def test_normalize_reports_a_file_that_is_not_json(tmp_path):
    path = tmp_path / "flow.json"
    path.write_text("{not json", encoding="utf-8")
    with pytest.raises(kb.KbError, match="is not valid JSON"):
        kb.cmd_normalize(_one_processor_kb(tmp_path),
                         argparse.Namespace(flow=str(path), output=None))


# ---------------------------------------------------------------------------
# Flow-wide checks: bundle versions and identifiers
# ---------------------------------------------------------------------------

def _validate_processors(tmp_path, capsys, processors, bundle_version="2.10.0"):
    knowledge_base = kb.Kb(_write_kb(tmp_path / "kb", "2.10.0", [
        ("PROCESSOR", "org.example.P", _native_2x("org.example.P", {}))]))
    flow = _flow([dict({"type": "org.example.P", "runDurationMillis": 25}, **processor)
                  for processor in processors], version=bundle_version)
    kb.normalize_group(knowledge_base, flow["flowContents"])
    path = tmp_path / "flow.json"
    path.write_text(json.dumps(flow), encoding="utf-8")
    capsys.readouterr()
    kb.cmd_validate(knowledge_base, argparse.Namespace(flow=str(path)))
    return capsys.readouterr().out


def _numbered(count):
    return [{"identifier": "c%d000000-0000-4000-8000-000000000000" % n, "name": "P%d" % n}
            for n in range(count)]


def test_three_bundle_version_mismatches_are_reported_one_by_one(tmp_path, capsys):
    out = _validate_processors(tmp_path, capsys, _numbered(3), bundle_version="2.9.0")
    assert out.count("bundle version is 2.9.0 but this Knowledge Base has 2.10.0.") == 3, out
    assert "components declare bundle version(s)" not in out, out


def test_four_bundle_version_mismatches_collapse_into_one_error(tmp_path, capsys):
    out = _validate_processors(tmp_path, capsys, _numbered(4), bundle_version="2.9.0")
    assert "4 components declare bundle version(s) 2.9.0, but this Knowledge Base describes " \
           "NiFi 2.10.0." in out, out
    assert "bundle version is 2.9.0" not in out, out


def test_a_placeholder_identifier_is_a_warning(tmp_path, capsys):
    out = _validate_processors(tmp_path, capsys, [
        {"identifier": "00000000-0000-0000-0000-000000000001", "name": "P"}])
    assert "identifier of 'P' is a constructed placeholder" in out, out


def test_an_identifier_used_twice_is_an_error(tmp_path, capsys):
    identifier = "c0000000-0000-4000-8000-000000000000"
    out = _validate_processors(tmp_path, capsys, [
        {"identifier": identifier, "name": "First", "position": {"x": 0.0, "y": 0.0}},
        {"identifier": identifier, "name": "Second", "position": {"x": 0.0, "y": 300.0}}])
    assert "identifier %s is used by both 'First' and 'Second'." % identifier in out, out

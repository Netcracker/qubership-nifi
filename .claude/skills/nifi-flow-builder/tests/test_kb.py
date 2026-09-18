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
    """Write a Knowledge Base holding `components`, each a (kind, type, component.json) triple."""
    (root / "components").mkdir(parents=True)
    index = []
    for number, (kind, type_name, component) in enumerate(components):
        path = "processors/C%d" % number
        (root / "components" / path).mkdir(parents=True)
        (root / "components" / path / "component.json").write_text(
            json.dumps(component), encoding="utf-8")
        index.append({"kind": kind, "group": "org.apache.nifi", "artifact": "nifi-standard-nar",
                      "version": version, "type": type_name, "tags": [], "deprecated": False,
                      "controllerServiceApis": [], "path": path,
                      "additionalDetailsAvailable": False})
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


@pytest.mark.parametrize("type_name, warned", [
    pytest.param("org.example.GetThing", True, id="a polling source"),
    pytest.param("org.example.ListThing", True, id="a lister is still a polling source"),
    pytest.param("org.example.ListenThing", False, id="a listener"),
    pytest.param("org.example.ConsumeThing", False, id="a consumer"),
])
def test_a_zero_period_source_is_warned_about_unless_it_listens_or_consumes(
        tmp_path, capsys, type_name, warned):
    out = _validate(tmp_path, capsys, "1.28.1", {
        "identifier": "43c7acbe-5c5a-431e-980e-caf91e2ac6cf", "name": "P",
        "schedulingPeriod": "0 sec", "runDurationMillis": 25},
        type_name=type_name, input_requirement="INPUT_FORBIDDEN")
    assert ("source processor scheduled every '0 sec'" in out) is warned


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

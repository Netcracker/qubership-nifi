import argparse
import json

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


def test_the_knowledge_base_matching_the_flow_version_is_chosen(tmp_path, monkeypatch):
    monkeypatch.delenv("NIFI_KB_PATH", raising=False)
    _write_kb(tmp_path / "kb-1", "1.28.1", [])
    _write_kb(tmp_path / "kb-2", "2.10.0", [])
    chosen = kb.discover(search_from=tmp_path, flow_version="1.28.1")
    assert chosen == (tmp_path / "kb-1").resolve()


def test_several_knowledge_bases_and_no_version_match_is_an_error(tmp_path, monkeypatch):
    monkeypatch.delenv("NIFI_KB_PATH", raising=False)
    _write_kb(tmp_path / "kb-1", "1.28.1", [])
    _write_kb(tmp_path / "kb-2", "2.10.0", [])
    with pytest.raises(kb.KbError, match="Several Knowledge Bases"):
        kb.discover(search_from=tmp_path, flow_version="1.27.0")


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


def _validate(tmp_path, capsys, kb_version, processor):
    definition = {"definitionFormat": "normalized-nifi-1x",
                  "definition": _processor_1x(type="org.example.P", supportedRelationships=[],
                                              inputRequirement="INPUT_ALLOWED")}
    knowledge_base = kb.Kb(
        _write_kb(tmp_path / "kb", kb_version, [("PROCESSOR", "org.example.P", definition)]))
    flow = _flow([dict(processor, type="org.example.P")], version=kb_version)
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

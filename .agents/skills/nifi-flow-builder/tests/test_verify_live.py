import json
import subprocess
import sys

import pytest

import verify_live

CONTENTS = {"identifier": "b051f24c-f9ff-4fde-b160-9b0b84af3e96", "processors": []}


def _snapshot(doc):
    return verify_live.registry_snapshot(json.dumps(doc).encode(), "bucket", "flow", "note")


@pytest.mark.parametrize("doc", [
    pytest.param({"versionedFlowSnapshot": {"flowContents": CONTENTS}},
                 id="VersionedFlowSnapshotEntity"),
    pytest.param({"snapshot": {"flowContents": CONTENTS}}, id="snapshot wrapper"),
    pytest.param(CONTENTS, id="bare process group"),
    pytest.param([CONTENTS], id="not an object"),
    pytest.param({"flowContents": None}, id="null flowContents"),
])
def test_registry_snapshot_refuses_a_file_without_a_top_level_flow_contents(doc):
    with pytest.raises(verify_live.LiveError, match="top-level 'flowContents'"):
        _snapshot(doc)


def test_a_file_without_flow_contents_is_refused_before_either_target_is_contacted(
        tmp_path, monkeypatch, capsys):
    flow = tmp_path / "flow.json"
    flow.write_text(json.dumps(CONTENTS), encoding="utf-8")
    monkeypatch.setenv("NIFI_ACCESS_TOKEN", "t")
    monkeypatch.setenv("NIFI_REGISTRY_ACCESS_TOKEN", "r")

    def no_client(*args, **kwargs):
        raise AssertionError("a Client was created for a file that should have been refused")

    monkeypatch.setattr(verify_live, "Client", no_client)
    code = verify_live.main([str(flow), "--nifi-url", "https://nifi.invalid",
                             "--registry-url", "https://registry.invalid", "--auth", "token"])
    assert code == 2
    assert "top-level 'flowContents'" in capsys.readouterr().err


HANDLED = {"identifier": "g", "processors": [
    {"identifier": "p1", "name": "Gen", "autoTerminatedRelationships": ["failure"],
     "properties": {"Batch Size": "5"}}],
    "connections": [{"source": {"id": "p1"}, "selectedRelationships": ["success"]}]}


@pytest.mark.parametrize("doc, relationships, properties", [
    pytest.param({"flowContents": HANDLED}, {"p1": {"failure", "success"}},
                 {"p1": {"batchsize": "5"}}, id="flowContents"),
    pytest.param(HANDLED, {}, {}, id="bare process group"),
])
def test_the_flow_helpers_read_components_only_from_flow_contents(doc, relationships, properties):
    flow_bytes = json.dumps(doc).encode()
    assert verify_live.flow_relationships(flow_bytes) == relationships
    assert verify_live.flow_properties(flow_bytes) == properties


def test_registry_snapshot_sends_every_field_of_the_file_unchanged():
    doc = {"flowContents": CONTENTS, "latest": False,
           "parameterContexts": {"ctx": {"name": "ctx"}}, "custom": [1, 2]}
    assert _snapshot(doc) == dict(doc, snapshotMetadata={
        "bucketIdentifier": "bucket", "flowIdentifier": "flow", "version": 1,
        "comments": "note"})


def test_registry_snapshot_replaces_the_snapshot_metadata_of_a_registry_export():
    doc = {"flowContents": CONTENTS, "snapshotMetadata": {
        "bucketIdentifier": "other", "flowIdentifier": "other", "version": 7, "author": "x"}}
    assert _snapshot(doc)["snapshotMetadata"] == {
        "bucketIdentifier": "bucket", "flowIdentifier": "flow", "version": 1,
        "comments": "note"}


class _FlowClient:
    """Serves /flow/process-groups/{id} from a map of group id to (ports, child ids)."""

    def __init__(self, groups):
        self._groups = groups

    def get(self, path, params=None):
        ports, children = self._groups[path.rsplit("/", 1)[1]]
        return {"processGroupFlow": {"flow": {
            "inputPorts": [p for p in ports if p["component"]["type"] == "INPUT_PORT"],
            "outputPorts": [p for p in ports if p["component"]["type"] == "OUTPUT_PORT"],
            "processGroups": [{"id": child} for child in children]}}}


def _port(name, kind, errors=()):
    return {"component": {"name": name, "type": kind, "validationErrors": list(errors)}}


def test_ports_are_collected_from_every_nested_group():
    client = _FlowClient({
        "root": ([_port("in_root", "INPUT_PORT")], ["child"]),
        "child": ([_port("out_child", "OUTPUT_PORT")], ["grandchild"]),
        "grandchild": ([_port("in_grandchild", "INPUT_PORT")], []),
    })
    names = [p["component"]["name"] for p in verify_live.collect_ports(client, "root")]
    assert sorted(names) == ["in_grandchild", "in_root", "out_child"]


def test_a_port_validation_error_is_reported_as_a_real_error(capsys):
    port = _port("in_items", "INPUT_PORT", ["Port 'in_items' has no outgoing connections"])
    code = verify_live.report([], [], "g", json.dumps({"flowContents": CONTENTS}).encode(), [port])
    assert code == 1
    assert "ERROR  input port 'in_items': Port 'in_items' has no outgoing connections" \
        in capsys.readouterr().out


UNCONNECTED_SUCCESS = ("Relationship 'success' is not connected to any component and is not "
                       "auto-terminated")


def _processor(live_id, name, file_id, errors=(), status="INVALID"):
    return {"component": {"id": live_id, "name": name, "versionedComponentId": file_id,
                          "validationStatus": status, "validationErrors": list(errors)}}


def test_same_named_processors_are_matched_to_the_file_by_identifier(capsys):
    # Two processors called Log: the root one auto-terminates success, the child one
    # leaves it unhandled. Only the child's error is real.
    flow = {"flowContents": {
        "identifier": "root", "processors": [
            {"identifier": "log-root", "name": "Log", "autoTerminatedRelationships": ["success"]}],
        "processGroups": [{"identifier": "child", "processors": [
            {"identifier": "log-child", "name": "Log", "autoTerminatedRelationships": []}]}]}}
    processors = [_processor("live-1", "Log", "log-root", [UNCONNECTED_SUCCESS]),
                  _processor("live-2", "Log", "log-child", [UNCONNECTED_SUCCESS])]

    code = verify_live.report(processors, [], "g", json.dumps(flow).encode())

    out = capsys.readouterr().out
    assert code == 1
    assert out.count("ERROR  processor 'Log': " + UNCONNECTED_SUCCESS) == 1
    assert "1 real validation error(s)." in out


def test_a_component_without_versioned_component_id_falls_back_to_a_unique_name(capsys):
    flow = {"flowContents": {"identifier": "root", "processors": [
        {"identifier": "p", "name": "Gen", "autoTerminatedRelationships": ["success"]}]}}
    processor = _processor("live", "Gen", None, [UNCONNECTED_SUCCESS])

    code = verify_live.report([processor], [], "g", json.dumps(flow).encode())

    assert code == 0
    assert "processor 'Gen' relationship 'success'" in capsys.readouterr().out


def test_a_component_still_validating_at_the_timeout_fails_the_run(capsys):
    processor = _processor("live", "Gen", "p", status="VALIDATING")

    code = verify_live.report([processor], [], "g",
                              json.dumps({"flowContents": CONTENTS}).encode(), timeout=60)

    out = capsys.readouterr().out
    assert code == 1
    assert "had not finished validating 1 component(s) when the 60-second timeout" in out
    assert "every component validated" not in out


class _ContextClient:
    """Lists the given parameter contexts and records every DELETE."""

    def __init__(self, contexts):
        self._contexts = contexts
        self.deleted = []

    def get(self, path, params=None):
        return {"parameterContexts": [
            {"id": "id-" + name, "revision": {"version": 0},
             "component": {"name": name, "boundProcessGroups": []}}
            for name in self._contexts]}

    def delete(self, path, params=None):
        self.deleted.append(path)
        return 200


def test_cleanup_deletes_only_new_contexts_the_flow_declares():
    client = _ContextClient(["existing", "from-flow", "other-user"])

    verify_live.drop_new_parameter_contexts(client, {"existing"}, {"from-flow", "existing"})

    assert client.deleted == ["/parameter-contexts/id-from-flow"]


def test_the_flow_declares_the_parameter_contexts_it_names():
    doc = {"flowContents": CONTENTS, "parameterContexts": {
        "a": {"name": "a"}, "b": {"name": "b", "parameters": []}}}
    assert verify_live.flow_parameter_context_names(json.dumps(doc).encode()) == {"a", "b"}


class _AboutClient:
    """Answers /flow/about with the given version and fails on any other call."""

    version = None

    def __init__(self, *args, **kwargs):
        pass

    def get(self, path, params=None):
        assert path == "/flow/about", "unexpected call %s" % path
        return {"about": {"version": _AboutClient.version}}


def test_cookie_mode_is_refused_for_nifi_1x_before_the_upload(tmp_path, monkeypatch, capsys):
    flow = tmp_path / "flow.json"
    flow.write_text(json.dumps({"flowContents": CONTENTS}), encoding="utf-8")
    monkeypatch.setenv("NIFI_AUTHORIZATION_BEARER_COOKIE", "c")
    monkeypatch.setattr(_AboutClient, "version", "1.28.1")
    monkeypatch.setattr(verify_live, "Client", _AboutClient)

    code = verify_live.main([str(flow), "--nifi-url", "https://nifi.invalid", "--auth", "cookie"])

    assert code == 2
    assert "Cookie mode supports NiFi 2.x only" in capsys.readouterr().err


def test_token_mode_sends_the_registry_its_own_token(monkeypatch):
    monkeypatch.setenv("NIFI_ACCESS_TOKEN", "nifi-token")
    monkeypatch.setenv("NIFI_REGISTRY_ACCESS_TOKEN", "registry-token")
    args = verify_live.argparse.Namespace(auth="token")
    assert verify_live.registry_auth_headers(args) == {"Authorization": "Bearer registry-token"}


def test_cookie_mode_is_refused_for_the_registry():
    args = verify_live.argparse.Namespace(auth="cookie")
    with pytest.raises(verify_live.LiveError, match="Cookie mode supports NiFi only"):
        verify_live.registry_auth_headers(args)


def test_the_error_signature_tells_same_named_components_apart():
    one = _processor("live-1", "Log", "a", [UNCONNECTED_SUCCESS])
    two = _processor("live-2", "Log", "b", [UNCONNECTED_SUCCESS])
    assert verify_live.error_signature([one, two]) == frozenset(
        {("live-1", UNCONNECTED_SUCCESS), ("live-2", UNCONNECTED_SUCCESS)})


def test_the_openssl_fallback_retries_with_the_legacy_provider(tmp_path, monkeypatch):
    # Without `cryptography`, a PKCS#12 file from keytool before JDK 12 needs -legacy.
    monkeypatch.setitem(sys.modules, "cryptography.hazmat.primitives.serialization", None)
    monkeypatch.setattr(verify_live.shutil, "which", lambda name: "/usr/bin/openssl")
    calls = []

    def run(command, **kwargs):
        calls.append(command)
        legacy = "-legacy" in command
        return subprocess.CompletedProcess(command, 0 if legacy else 1,
                                           b"PEM" if legacy else b"", b"unsupported")

    monkeypatch.setattr(verify_live.subprocess, "run", run)
    cert, key = verify_live.unpack_pkcs12(tmp_path / "client.p12", "secret", tmp_path)
    assert ["-legacy" in command for command in calls] == [False, True, False, True]
    assert (cert.read_bytes(), key.read_bytes()) == (b"PEM", b"PEM")


class _BrokenCleanupClient:
    """Imports an empty group, then fails the GET that cleanup starts with."""

    def get(self, path, params=None):
        if path == "/process-groups/g":
            raise OSError("connection reset")
        return {"/flow/parameter-contexts": {"parameterContexts": []},
                "/process-groups/g/processors": {"processors": []},
                "/flow/process-groups/g/controller-services": {"controllerServices": []},
                "/flow/process-groups/g": {"processGroupFlow": {"flow": {}}}}[path]

    def upload(self, path, filename, content, fields):
        return 201, b'{"id": "g"}'


def test_a_cleanup_failure_is_a_warning_and_keeps_the_result(capsys):
    args = verify_live.argparse.Namespace(parent_group="parent", timeout=5, keep=False)
    flow_bytes = json.dumps({"flowContents": CONTENTS}).encode()

    code = verify_live.verify(_BrokenCleanupClient(), args, flow_bytes, "flow.json")

    captured = capsys.readouterr()
    assert code == 0
    assert "every component validated" in captured.out
    assert "WARNING: cleanup of kb-verify-" in captured.err
    assert "connection reset" in captured.err


class _BrokenRegistryClient:
    def get(self, path, params=None):
        raise verify_live.LiveError("GET %s -> HTTP 503" % path)


def test_a_registry_item_that_cannot_be_read_back_is_reported(capsys):
    verify_live.drop_registry_item(_BrokenRegistryClient(), "/buckets/b", "temporary bucket x")
    assert "WARNING: could not delete temporary bucket x: GET /buckets/b -> HTTP 503" \
        in capsys.readouterr().err

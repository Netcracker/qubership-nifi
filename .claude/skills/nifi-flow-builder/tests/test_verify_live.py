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
    """Serves /flow/process-groups/{id} from a map of group id to (components, child ids)."""

    def __init__(self, groups):
        self._groups = groups

    def get(self, path, params=None):
        components, children = self._groups[path.rsplit("/", 1)[1]]
        return {"processGroupFlow": {"flow": {
            "inputPorts": [c for c in components if c["component"]["type"] == "INPUT_PORT"],
            "outputPorts": [c for c in components if c["component"]["type"] == "OUTPUT_PORT"],
            "remoteProcessGroups": [c for c in components
                                    if c["component"]["type"] == "REMOTE_PROCESS_GROUP"],
            "processGroups": [{"id": child} for child in children]}}}


def _port(name, kind, errors=()):
    return {"component": {"name": name, "type": kind, "validationErrors": list(errors)}}


def test_ports_are_collected_from_every_nested_group():
    client = _FlowClient({
        "root": ([_port("in_root", "INPUT_PORT")], ["child"]),
        "child": ([_port("out_child", "OUTPUT_PORT")], ["grandchild"]),
        "grandchild": ([_port("in_grandchild", "INPUT_PORT")], []),
    })
    ports, _ = verify_live.collect_ports(client, "root")
    assert sorted(p["component"]["name"] for p in ports) == [
        "in_grandchild", "in_root", "out_child"]


def test_remote_process_groups_are_collected_from_every_nested_group():
    client = _FlowClient({
        "root": ([_port("remote_root", "REMOTE_PROCESS_GROUP")], ["child"]),
        "child": ([_port("remote_child", "REMOTE_PROCESS_GROUP")], []),
    })
    _, remote_groups = verify_live.collect_ports(client, "root")
    assert sorted(r["component"]["name"] for r in remote_groups) == [
        "remote_child", "remote_root"]


def test_a_remote_process_group_validation_error_fails_the_run(capsys):
    remote = _port("Remote", "REMOTE_PROCESS_GROUP", ["'URLs' is invalid because it is empty"])
    code = verify_live.report([], [], "g", json.dumps({"flowContents": CONTENTS}).encode(),
                              remote_groups=[remote])
    assert code == 1
    assert "ERROR  remote process group 'Remote': 'URLs' is invalid because it is empty" \
        in capsys.readouterr().out


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


def test_a_required_property_waiting_on_a_parameter_value_is_not_a_real_error(capsys):
    flow = {"flowContents": {"identifier": "root", "processors": [
        {"identifier": "p", "name": "Put", "properties": {"Password": "#{db.password}"}}]}}
    processor = _processor("live", "Put", "p",
                           ["'Password' is invalid because Password is required"])

    code = verify_live.report([processor], [], "g", json.dumps(flow).encode())

    out = capsys.readouterr().out
    assert code == 0
    assert "Waiting on parameter values in the target NiFi" in out
    assert "processor 'Put' property 'Password'" in out
    assert "ERROR" not in out


class _ContextClient:
    """Lists parameter contexts in the given order and records every DELETE.

    `contexts` maps a name to the names of the contexts it inherits from.
    """

    def __init__(self, contexts, bound=()):
        self._contexts = contexts
        self._bound = set(bound)
        self.deleted = []

    def get(self, path, params=None):
        return {"parameterContexts": [
            {"id": "id-" + name, "revision": {"version": 0},
             "component": {"name": name,
                           "boundProcessGroups": [{"id": "g"}] if name in self._bound else [],
                           "inheritedParameterContexts": [
                               {"id": "id-" + parent, "component": {"name": parent}}
                               for parent in parents]}}
            for name, parents in self._contexts.items()]}

    def delete(self, path, params=None):
        self.deleted.append(path)
        return 200


def test_cleanup_deletes_only_the_contexts_this_run_created():
    # Another user created "ctx" during the run; this run's own context has the suffix.
    client = _ContextClient({"existing": [], "ctx": [], "ctx (kb-verify-1)": []})

    verify_live.drop_new_parameter_contexts(client, {"ctx (kb-verify-1)"})

    assert client.deleted == ["/parameter-contexts/id-ctx (kb-verify-1)"]


def test_cleanup_deletes_an_inheriting_context_before_the_one_it_inherits():
    client = _ContextClient({"base (r)": [], "child (r)": ["base (r)"]})

    verify_live.drop_new_parameter_contexts(client, {"base (r)", "child (r)"})

    assert client.deleted == ["/parameter-contexts/id-child (r)",
                              "/parameter-contexts/id-base (r)"]


def test_cleanup_leaves_a_bound_context_and_says_so(capsys):
    client = _ContextClient({"ctx (r)": []}, bound={"ctx (r)"})

    verify_live.drop_new_parameter_contexts(client, {"ctx (r)"})

    assert client.deleted == []
    assert "parameter context 'ctx (r)' is still bound" in capsys.readouterr().err


def test_new_parameter_contexts_are_renamed_for_the_run_and_existing_ones_kept():
    doc = {"flowContents": {"identifier": "root", "parameterContextName": "child",
                            "processGroups": [{"identifier": "inner",
                                               "parameterContextName": "shared"}]},
           "parameterContexts": {
               "child": {"name": "child", "inheritedParameterContexts": ["base", "shared"]},
               "base": {"name": "base"},
               "shared": {"name": "shared"}}}

    uploaded, temporary = verify_live.temporary_parameter_contexts(
        json.dumps(doc).encode(), {"shared"}, "run")

    result = json.loads(uploaded)
    assert temporary == {"child (run)", "base (run)"}
    assert result["parameterContexts"] == {
        "child (run)": {"name": "child (run)",
                        "inheritedParameterContexts": ["base (run)", "shared"]},
        "base (run)": {"name": "base (run)"},
        "shared": {"name": "shared"}}
    assert result["flowContents"]["parameterContextName"] == "child (run)"
    assert result["flowContents"]["processGroups"][0]["parameterContextName"] == "shared"


def test_a_flow_without_new_parameter_contexts_is_uploaded_unchanged():
    flow_bytes = json.dumps({"flowContents": CONTENTS,
                             "parameterContexts": {"shared": {"name": "shared"}}}).encode()
    assert verify_live.temporary_parameter_contexts(flow_bytes, {"shared"}, "run") == (
        flow_bytes, set())


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


OPENSSL_OUTPUT = {"-clcerts": b"LEAF\n", "-cacerts": b"CA\n", "-nocerts": b"KEY\n"}


def _without_cryptography(monkeypatch):
    monkeypatch.setitem(sys.modules, "cryptography.hazmat.primitives.serialization", None)
    monkeypatch.setattr(verify_live.shutil, "which", lambda name: "/usr/bin/openssl")


def _fake_openssl(monkeypatch, succeeds):
    """Answer each openssl call from OPENSSL_OUTPUT when `succeeds(command)`, else fail it."""
    calls = []

    def run(command, **kwargs):
        calls.append(command)
        if not succeeds(command):
            stderr = b"unknown option -legacy" if "-legacy" in command else b"mac verify failure"
            return subprocess.CompletedProcess(command, 1, b"", stderr)
        return subprocess.CompletedProcess(command, 0, OPENSSL_OUTPUT[command[6]], b"")

    monkeypatch.setattr(verify_live.subprocess, "run", run)
    return calls


def test_the_openssl_fallback_writes_the_ca_chain_after_the_client_certificate(
        tmp_path, monkeypatch):
    _without_cryptography(monkeypatch)
    _fake_openssl(monkeypatch, lambda command: True)

    cert, key = verify_live.unpack_pkcs12(tmp_path / "client.p12", "secret", tmp_path)

    assert (cert.read_bytes(), key.read_bytes()) == (b"LEAF\nCA\n", b"KEY\n")


def test_the_openssl_fallback_retries_with_the_legacy_provider(tmp_path, monkeypatch):
    # Without `cryptography`, a PKCS#12 file from keytool before JDK 12 needs -legacy.
    _without_cryptography(monkeypatch)
    calls = _fake_openssl(monkeypatch, lambda command: "-legacy" in command)

    cert, key = verify_live.unpack_pkcs12(tmp_path / "client.p12", "secret", tmp_path)

    assert ["-legacy" in command for command in calls] == [False, True] * 3
    assert (cert.read_bytes(), key.read_bytes()) == (b"LEAF\nCA\n", b"KEY\n")


def test_an_openssl_failure_reports_the_first_error_as_well_as_the_retry(tmp_path, monkeypatch):
    _without_cryptography(monkeypatch)
    _fake_openssl(monkeypatch, lambda command: False)

    with pytest.raises(verify_live.LiveError) as caught:
        verify_live.unpack_pkcs12(tmp_path / "client.p12", "secret", tmp_path)

    assert "mac verify failure" in str(caught.value)
    assert "unknown option -legacy" in str(caught.value)


@pytest.mark.parametrize("content", [
    pytest.param(b"not a PKCS#12 file", id="damaged file"),
    pytest.param(None, id="missing file"),
])
def test_an_unreadable_pkcs12_file_is_a_live_error(tmp_path, content):
    pytest.importorskip("cryptography")
    path = tmp_path / "client.p12"
    if content is not None:
        path.write_bytes(content)

    with pytest.raises(verify_live.LiveError, match="Check the path and the password"):
        verify_live.unpack_pkcs12(path, "secret", tmp_path)


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


class _RejectingClient:
    """Rejects the upload after NiFi has created the group, as a 500 from the importer does."""

    def __init__(self):
        self.group_name = None
        self.deleted = []

    def get(self, path, params=None):
        if path == "/flow/parameter-contexts":
            # After the upload, the context the import created under its temporary name.
            created = [] if self.group_name is None else [
                {"id": "ctx-id", "revision": {"version": 0},
                 "component": {"name": "ctx (%s)" % self.group_name,
                               "boundProcessGroups": []}}]
            return {"parameterContexts": created}
        if path == "/flow/process-groups/parent":
            return {"processGroupFlow": {"flow": {"processGroups": [
                {"id": "other", "component": {"name": "kb-verify-of-another-run"}},
                {"id": "created", "component": {"name": self.group_name}}]}}}
        return {"revision": {"version": 3}}

    def upload(self, path, filename, content, fields):
        self.group_name = fields["groupName"]
        return 500, b"An unexpected error has occurred."

    def delete(self, path, params=None):
        self.deleted.append(path)
        return 200


def test_a_rejected_import_deletes_what_nifi_created_before_failing(capsys):
    client = _RejectingClient()
    args = verify_live.argparse.Namespace(parent_group="parent", timeout=5, keep=False)
    flow = {"flowContents": CONTENTS, "parameterContexts": {"ctx": {"name": "ctx"}}}

    code = verify_live.verify(client, args, json.dumps(flow).encode(), "flow.json")

    assert code == 1
    assert client.deleted == ["/process-groups/created", "/parameter-contexts/ctx-id"]


class _UnreachableAfterRejectionClient(_RejectingClient):
    def get(self, path, params=None):
        if path == "/flow/process-groups/parent":
            raise OSError("connection reset")
        return super().get(path, params)


def test_a_failed_lookup_after_a_rejected_import_keeps_the_result(capsys):
    args = verify_live.argparse.Namespace(parent_group="parent", timeout=5, keep=False)

    code = verify_live.verify(_UnreachableAfterRejectionClient(), args,
                              json.dumps({"flowContents": CONTENTS}).encode(), "flow.json")

    assert code == 1
    assert "could not look for temporary group kb-verify-" in capsys.readouterr().err


class _BrokenRegistryClient:
    def get(self, path, params=None):
        raise verify_live.LiveError("GET %s -> HTTP 503" % path)


def test_a_registry_item_that_cannot_be_read_back_is_reported(capsys):
    verify_live.drop_registry_item(_BrokenRegistryClient(), "/buckets/b", "temporary bucket x")
    assert "WARNING: could not delete temporary bucket x: GET /buckets/b -> HTTP 503" \
        in capsys.readouterr().err

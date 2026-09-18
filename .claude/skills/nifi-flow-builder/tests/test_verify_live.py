import json

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
    pytest.param({"flowContents": HANDLED}, {"Gen": {"failure", "success"}},
                 {"Gen": {"batchsize": "5"}}, id="flowContents"),
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

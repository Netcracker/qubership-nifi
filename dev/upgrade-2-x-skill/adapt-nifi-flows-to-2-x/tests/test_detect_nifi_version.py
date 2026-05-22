import json

from detect_nifi_version import detect_versions


def _write_flow(tmp_path, name: str, data: dict) -> None:
    (tmp_path / name).write_text(json.dumps(data), encoding="utf-8")


def _flow_with_bundle(version: str) -> dict:
    return {
        "flowContents": {
            "processors": [
                {
                    "bundle": {
                        "group": "org.apache.nifi",
                        "artifact": "nifi-standard-nar",
                        "version": version,
                    }
                }
            ]
        }
    }


def test_detect_versions_single_file(tmp_path):
    _write_flow(tmp_path, "flow.json", _flow_with_bundle("1.28.1"))
    assert detect_versions(str(tmp_path)) == {"1.28.1"}


def test_detect_versions_multiple_versions(tmp_path):
    _write_flow(tmp_path, "flow1.json", _flow_with_bundle("1.28.1"))
    _write_flow(tmp_path, "flow2.json", _flow_with_bundle("1.27.0"))
    result = detect_versions(str(tmp_path))
    assert result == {"1.28.1", "1.27.0"}


def test_detect_versions_no_bundles(tmp_path):
    _write_flow(tmp_path, "flow.json", {"flowContents": {"processors": []}})
    assert detect_versions(str(tmp_path)) == set()


def test_detect_versions_wrong_group(tmp_path):
    data = {
        "flowContents": {
            "processors": [
                {"bundle": {"group": "com.example", "artifact": "my-nar", "version": "2.0.0"}}
            ]
        }
    }
    _write_flow(tmp_path, "flow.json", data)
    assert detect_versions(str(tmp_path)) == set()


def test_detect_versions_nested_bundle(tmp_path):
    # Bundle buried inside a list inside a dict
    data = {
        "wrapperList": [
            {
                "inner": {
                    "bundle": {
                        "group": "org.apache.nifi",
                        "artifact": "nifi-nar",
                        "version": "1.26.0",
                    }
                }
            }
        ]
    }
    _write_flow(tmp_path, "flow.json", data)
    assert "1.26.0" in detect_versions(str(tmp_path))


def test_detect_versions_skips_invalid_json(tmp_path):
    (tmp_path / "bad.json").write_text("not valid json{{", encoding="utf-8")
    _write_flow(tmp_path, "good.json", _flow_with_bundle("1.28.1"))
    assert detect_versions(str(tmp_path)) == {"1.28.1"}


def test_detect_versions_empty_dir(tmp_path):
    assert detect_versions(str(tmp_path)) == set()

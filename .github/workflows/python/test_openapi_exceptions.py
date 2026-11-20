import pytest
import yaml

from openapi_exceptions import (
    APPROVED_EXCEPTIONS_PATH, REASON_PLACEHOLDER, apply_exceptions, load_exception_groups, load_exceptions,
)


UPGRADE = {"previousNifiVersion": "2.9.0", "currentNifiVersion": "2.10.0"}
OPERATION = {"method": "post", "path": "/nifi-api/controller/controller-services"}
REMOVAL = {
    "action": "remove",
    "scope": "root",
    "previousDeclarationJsonPaths": [["paths", "/controller/controller-services", "post", "security", 2]],
    "previousValueHash": "45826b3a09040d9a0c62b9cf06891550",
}
REASON = "Expected security declaration change in Qubership NiFi 2.8.0."


def write_approvals(path, *changes):
    path.write_text(yaml.safe_dump({"exceptions": [{**UPGRADE, "changes": list(changes)}]}), encoding="utf-8")
    return load_exceptions(path)


@pytest.mark.parametrize(
    ("previous", "current", "expected", "reason"),
    [
        pytest.param("2.9.0", "2.10.0", (0, 1), REASON, id="exact-pair"),
        pytest.param("2.8.0", "2.10.0", (1, 0), None, id="different-baseline"),
        pytest.param("2.9.0", "2.12.0", (1, 0), None, id="different-candidate"),
    ],
)
def test_exception_matches_only_its_exact_nifi_version_pair(tmp_path, previous, current, expected, reason):
    config = write_approvals(tmp_path / "approved.yml", {**OPERATION, **REMOVAL, "reason": REASON})
    versions = {"previousNifiVersion": previous, "currentNifiVersion": current}
    issue = {"severity": "breaking", **REMOVAL}
    operations = [{"currentOperation": OPERATION, "issues": [issue]}]

    assert apply_exceptions(config, versions, operations, tmp_path / "proposed.yml") == expected
    assert issue.get("exceptionReason") == reason


@pytest.mark.parametrize("field", ["previousDeclarationJsonPaths", "previousValueHash"])
def test_a_location_or_hash_omitted_from_approval_cannot_match_a_detailed_finding(tmp_path, field):
    approval = {key: value for key, value in {**OPERATION, **REMOVAL, "reason": REASON}.items() if key != field}
    config = write_approvals(tmp_path / "approved.yml", approval)
    operations = [{"currentOperation": OPERATION, "issues": [{"severity": "breaking", **REMOVAL}]}]

    assert apply_exceptions(config, UPGRADE, operations, tmp_path / "proposed.yml") == (1, 0)


def test_proposal_adds_only_unmatched_breaks_and_applies_once_its_reason_is_reviewed(tmp_path):
    config_path = tmp_path / "approved.yml"
    proposal = tmp_path / "proposed.yml"
    approval = {**OPERATION, **REMOVAL, "reason": REASON}
    config = write_approvals(config_path, approval)
    original = config_path.read_text(encoding="utf-8")
    unapproved = {**REMOVAL, "previousValueHash": "different-hash"}
    issues = [
        {"severity": "breaking", **REMOVAL},
        {"severity": "breaking", **unapproved},
        {"severity": "non-breaking", "action": "add", "scope": "root"},
    ]
    operations = [{"currentOperation": OPERATION, "issues": issues}]

    assert apply_exceptions(config, UPGRADE, operations, proposal) == (1, 1)
    assert config_path.read_text(encoding="utf-8") == original
    with pytest.raises(ValueError, match="group 1, change 2 has the proposed placeholder reason"):
        load_exceptions(proposal)
    reviewed = "The new security declaration hash was reviewed."
    proposal.write_text(proposal.read_text(encoding="utf-8").replace(REASON_PLACEHOLDER, reviewed), encoding="utf-8")
    proposed_config = load_exceptions(proposal)
    approved, suggested = proposed_config["exceptions"][0]["changes"]
    assert approved == approval
    assert {field: value for field, value in suggested.items() if field != "reason"} == {**OPERATION, **unapproved}

    proposal.unlink()
    assert apply_exceptions(proposed_config, UPGRADE, operations, proposal) == (0, 2)
    assert not proposal.exists()
    assert [issue["exceptionReason"] for issue in issues if "exceptionReason" in issue] == [REASON, reviewed]


def test_renames_match_the_previous_and_current_keys(tmp_path):
    rename = {**REMOVAL, "action": "rename", "previousKey": "oldName", "currentKey": "newName"}
    del rename["previousValueHash"]
    config = write_approvals(tmp_path / "approved.yml", {**OPERATION, **rename, "reason": REASON})
    proposal = tmp_path / "proposed.yml"
    issues = [
        {"severity": "breaking", **rename},
        {"severity": "breaking", **rename, "previousKey": "anotherOldName"},
        {"severity": "breaking", **rename, "currentKey": "anotherNewName"},
    ]
    operations = [{"currentOperation": OPERATION, "issues": issues}]

    assert apply_exceptions(config, UPGRADE, operations, proposal) == (2, 1)
    assert [issue.get("exceptionReason") for issue in issues] == [REASON, None, None]
    assert apply_exceptions({"exceptions": load_exception_groups(proposal)}, UPGRADE, operations, proposal) == (0, 3)


def test_removed_operation_uses_its_previous_method_and_path(tmp_path):
    operation = {"method": "get", "path": "/nifi-api/flow/about"}
    removal = {"action": "remove", "scope": "root"}
    operations = [{"previousOperation": operation, "issues": [{"severity": "breaking", **removal}]}]
    proposal = tmp_path / "proposed.yml"

    assert apply_exceptions({"exceptions": []}, UPGRADE, operations, proposal) == (1, 0)
    proposed, = load_exception_groups(proposal)[0]["changes"]
    assert {field: value for field, value in proposed.items() if field != "reason"} == {**operation, **removal}


def test_duplicate_config_keys_are_rejected(tmp_path):
    config_path = tmp_path / "approved.yml"
    config_path.write_text("exceptions: []\nexceptions: []\n", encoding="utf-8")

    with pytest.raises(ValueError, match="Duplicate key in OpenAPI exceptions"):
        load_exceptions(config_path)


def test_repository_exceptions_file_is_valid():
    assert isinstance(load_exceptions(APPROVED_EXCEPTIONS_PATH)["exceptions"], list)

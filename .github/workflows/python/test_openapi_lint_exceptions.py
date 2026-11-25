from collections import Counter

import pytest
import yaml

from openapi_exceptions import load_exception_groups
from openapi_lint_exceptions import (
    APPROVED_LINT_EXCEPTIONS_PATH, REASON_PLACEHOLDER, apply_lint_exceptions, load_lint_exceptions,
    unused_lint_exceptions,
)


SCOPE = {"nifiVersion": "2.12.0", "linter": "spectral", "ruleset": "default-openapi-3-0"}
FINDING = {
    "severity": "warning",
    "code": "operation-success-response",
    "path": ["paths", "/authentication/configuration", "get", "responses"],
    "message": 'Operation must have at least one "2xx" or "3xx" response.',
}
REASON = "Existing finding accepted for this NiFi version."


def test_exact_match_preserves_the_approval_reason(tmp_path):
    result = {"linter": "spectral", "ruleset": {"name": SCOPE["ruleset"]}, "issues": [dict(FINDING)]}
    approvals = [{**SCOPE, "issues": [{**FINDING, "reason": REASON}]}]
    proposal = tmp_path / "proposal.yml"

    assert apply_lint_exceptions(approvals, SCOPE["nifiVersion"], result, proposal) == (Counter(), Counter(warning=1))
    assert result["issues"] == [{**FINDING, "exceptionReason": REASON}]
    assert not proposal.exists()


def test_an_explicit_empty_path_matches_a_document_level_finding(tmp_path):
    finding = {field: value for field, value in FINDING.items() if field != "path"}
    result = {"linter": "spectral", "ruleset": {"name": SCOPE["ruleset"]}, "issues": [finding]}
    approvals = [{**SCOPE, "issues": [{**finding, "path": [], "reason": REASON}]}]
    proposal = tmp_path / "proposal.yml"

    assert apply_lint_exceptions(approvals, SCOPE["nifiVersion"], result, proposal) == (Counter(), Counter(warning=1))
    assert result["issues"] == [{**FINDING, "path": [], "exceptionReason": REASON}]
    assert not proposal.exists()


@pytest.mark.parametrize(
    ("scope_change", "finding_change"),
    [
        pytest.param({"linter": "other-linter"}, {}, id="linter"),
        pytest.param({"ruleset": "custom-openapi"}, {}, id="ruleset"),
        pytest.param({}, {"severity": "error"}, id="severity"),
        pytest.param({}, {"code": "another-rule"}, id="code"),
        pytest.param({}, {"path": ["paths", "/flow", "get", "responses"]}, id="path"),
        pytest.param({}, {"message": "Different reason from the same rule."}, id="message"),
    ],
)
def test_each_linter_ruleset_and_finding_field_must_match(tmp_path, scope_change, finding_change):
    scope = {**SCOPE, **scope_change}
    finding = {**FINDING, **finding_change}
    result = {"linter": scope["linter"], "ruleset": {"name": scope["ruleset"]}, "issues": [finding]}
    approvals = [{**SCOPE, "issues": [{**FINDING, "reason": REASON}]}]
    proposal = tmp_path / "proposal.yml"

    assert apply_lint_exceptions(approvals, scope["nifiVersion"], result, proposal) == (
        Counter({finding["severity"]: 1}), Counter(),
    )
    assert "exceptionReason" not in finding
    assert load_exception_groups(proposal) == [
        *(approvals if scope_change else []),
        {**scope, "issues": [{**finding, "reason": REASON_PLACEHOLDER}]},
    ]


def test_unused_approvals_are_listed_only_for_the_linter_and_ruleset_of_the_result():
    result = {"linter": "spectral", "ruleset": {"name": SCOPE["ruleset"]}, "issues": [dict(FINDING)]}
    unused = {**FINDING, "code": "another-rule", "reason": REASON}
    approvals = [
        {**SCOPE, "issues": [{**FINDING, "reason": REASON}, unused]},
        {**SCOPE, "ruleset": "custom-openapi", "issues": [unused]},
        {**SCOPE, "linter": "other-linter", "issues": [unused]},
    ]

    assert unused_lint_exceptions(approvals, result) == [{**SCOPE, **unused}]


def test_upgrade_proposal_keeps_matching_approvals_and_removes_stale_ones(tmp_path):
    new_finding = {**FINDING, "code": "another-rule"}
    result = {
        "linter": "spectral", "ruleset": {"name": SCOPE["ruleset"]}, "issues": [dict(FINDING), dict(new_finding)],
    }
    matching = {**FINDING, "reason": REASON}
    stale = {**matching, "path": ["obsolete"]}
    other_ruleset = {**SCOPE, "ruleset": "custom-openapi", "issues": [stale]}
    approvals = [
        {**SCOPE, "nifiVersion": "2.11.0", "issues": [{**matching, "code": "obsolete-rule"}]},
        {**SCOPE, "issues": [matching, stale]},
        other_ruleset,
    ]
    proposal = tmp_path / "proposal.yml"

    assert apply_lint_exceptions(approvals, "2.13.0", result, proposal) == (Counter(warning=1), Counter(warning=1))
    assert [issue.get("exceptionReason") for issue in result["issues"]] == [REASON, None]
    assert load_exception_groups(proposal) == [
        {**SCOPE, "issues": [matching]},
        other_ruleset,
        {**SCOPE, "nifiVersion": "2.13.0", "issues": [{**new_finding, "reason": REASON_PLACEHOLDER}]},
    ]


@pytest.mark.parametrize(
    "overrides",
    [
        pytest.param({"path": ["paths", "parameters", 0]}, id="numeric-path-segment"),
        pytest.param({"severity": "info"}, id="nonblocking-severity"),
        pytest.param({"reason": ""}, id="empty-reason"),
        pytest.param({"reason": REASON_PLACEHOLDER}, id="placeholder-reason"),
        pytest.param({"severty": "warning"}, id="unknown-field"),
    ],
)
def test_invalid_lint_approvals_are_rejected(tmp_path, overrides):
    path = tmp_path / "approved.yml"
    finding = {**FINDING, "reason": REASON, **overrides}
    path.write_text(yaml.safe_dump({"exceptions": [{**SCOPE, "issues": [finding]}]}), encoding="utf-8")

    with pytest.raises(ValueError, match="group 1, issue 1"):
        load_lint_exceptions(path)


def write_approvals_for_two_nifi_versions(path, later_ruleset):
    approval = {**FINDING, "reason": REASON}
    groups = [
        {**SCOPE, "issues": [approval]},
        {**SCOPE, "nifiVersion": "2.13.0", "ruleset": later_ruleset, "issues": [approval]},
    ]
    path.write_text(yaml.safe_dump({"exceptions": groups}), encoding="utf-8")
    return groups


def test_a_finding_approved_for_an_earlier_nifi_version_is_rejected_when_approved_again(tmp_path):
    path = tmp_path / "approved.yml"
    write_approvals_for_two_nifi_versions(path, SCOPE["ruleset"])

    with pytest.raises(ValueError, match="group 2, issue 1 repeats a lint exception for the same linter and ruleset"):
        load_lint_exceptions(path)


def test_the_same_finding_can_be_approved_again_for_another_ruleset(tmp_path):
    path = tmp_path / "approved.yml"
    groups = write_approvals_for_two_nifi_versions(path, "custom-openapi")

    assert load_lint_exceptions(path) == groups


def test_repository_lint_exceptions_file_is_valid():
    assert isinstance(load_lint_exceptions(APPROVED_LINT_EXCEPTIONS_PATH), list)

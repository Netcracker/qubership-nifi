import base64
from collections import deque
from dataclasses import dataclass, field
from email import policy
from email.parser import BytesParser
import io
import json
import sys
import time
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit
import zipfile

import pytest
import yaml

import check_openapi


PACKAGE = "/api/v2/packages/CI.NIFI"
PUBLISH = f"{PACKAGE}/publish"
CANDIDATE = f"{PACKAGE}/versions/0.0.2"
LINT = f"{CANDIDATE}/validation"
COMPARISON = f"{CANDIDATE}/changes/summary"
OPERATIONS = "/api/v4/packages/CI.NIFI/versions/0.0.2/rest/changes"
VERSIONS = {"previousNifiVersion": "2.9.0", "currentNifiVersion": "2.10.0"}
OPERATION = {"operationId": "get-flow", "method": "get", "path": "/nifi-api/flow"}
BREAKING = {
    "severity": "breaking",
    "action": "remove",
    "scope": "response",
    "previousDeclarationJsonPaths": [["paths", "/flow", "get", "responses", "200", "headers", "ETag"]],
    "previousValueHash": "previous-hash",
}
PROPOSAL = "proposed-openapi-compatibility-exceptions.yml"
LINT_PROPOSAL = "proposed-openapi-lint-exceptions.yml"
LINT_SCOPE = {
    "nifiVersion": VERSIONS["currentNifiVersion"],
    "linter": "spectral",
    "ruleset": "default-openapi-3-0",
}


@dataclass
class Response:
    status: int
    payload: dict | None = None
    headers: dict = field(default_factory=dict)

    def read(self):
        return json.dumps(self.payload).encode() if self.payload is not None else b""

    def getheaders(self):
        return list(self.headers.items())


@dataclass
class Request:
    method: str
    target: str
    headers: dict
    body: bytes | str | None


class MockApiHub:
    def __init__(self):
        self.routes = {}
        self.requests = []
        self.unexpected_requests = []

    def respond(self, method, path, *responses):
        self.routes[method, path] = deque(responses)

    def request(self, method, target, body, headers):
        self.requests.append(Request(method, target, headers, body))
        route = method, urlsplit(target).path
        responses = self.routes.get(route)
        if responses:
            return responses.popleft()
        self.unexpected_requests.append(route)
        return Response(500, {"message": "Unexpected call to mock APIHUB"})


class MockConnection:
    def __init__(self, service):
        self.service = service

    def request(self, method, target, body, headers):
        self.response = self.service.request(method, target, body, headers)

    def getresponse(self):
        return self.response

    def close(self):
        pass


def lint_issue(severity):
    return {
        "severity": severity, "code": f"sample-{severity}", "path": ["paths", "/flow", "get"],
        "message": f"Sample {severity}",
    }


def mock_lint(linter, *severities):
    linter.respond("GET", f"{LINT}/summary", Response(200, {
        "status": "success",
        "documents": [{"slug": "openapi", "issuesSummary": {
            severity: severities.count(severity) for severity in ("error", "warning")
        }}],
    }))
    linter.respond("GET", f"{LINT}/documents/openapi/details", Response(200, {
        "results": [{
            "linter": "spectral", "ruleset": {"name": LINT_SCOPE["ruleset"]}, "status": "success",
            "issues": [lint_issue(severity) for severity in severities],
        }],
    }))


def mock_comparison(backend, breaking=0, affected=0):
    backend.respond("GET", COMPARISON, Response(200, {
        "hasErrors": False,
        "operationTypes": [{
            "apiType": "rest",
            "changesSummary": {"breaking": breaking},
            "numberOfImpactedOperations": {"breaking": affected},
        }],
    }))


def mock_breaking_change(backend, issue=BREAKING):
    mock_comparison(backend, breaking=1, affected=1)
    backend.respond("GET", OPERATIONS, Response(200, {"operations": [{
        "currentOperation": OPERATION, "changeSummary": {"breaking": 1},
    }]}))
    backend.respond("GET", f"{CANDIDATE}/rest/operations/get-flow/changes", Response(200, {"changes": [issue]}))


def mock_publication(backend):
    backend.respond("POST", "/api/v3/auth/local", Response(
        200, headers={"Set-Cookie": "apihub-access-token=mock-session; Path=/; HttpOnly"}
    ))
    backend.respond("POST", "/api/v2/packages", Response(201, {"packageId": "CI"}),
                    Response(201, {"packageId": "CI.NIFI"}))
    backend.respond("POST", PUBLISH, Response(202, {"publishId": "baseline"}), Response(202, {"publishId": "candidate"}))
    backend.respond("GET", f"{PUBLISH}/baseline/status", Response(200, {"status": "complete"}))
    backend.respond("GET", f"{PUBLISH}/candidate/status", Response(200, {"status": "complete"}))


@pytest.fixture
def apihub(monkeypatch):
    backend = MockApiHub()
    linter = MockApiHub()
    mock_publication(backend)
    mock_lint(linter)
    mock_comparison(backend)
    monkeypatch.setattr(check_openapi, "time", SimpleNamespace(monotonic=time.monotonic, sleep=lambda seconds: None))
    monkeypatch.setenv("APIHUB_ADMIN_EMAIL", "ci@example.test")
    monkeypatch.setenv("APIHUB_ADMIN_PASSWORD", "mock-password")

    services = {18090: backend, 18091: linter}

    def connect(host, port, *, timeout):
        assert host == "127.0.0.1"
        return MockConnection(services[port])

    monkeypatch.setattr(check_openapi.http.client, "HTTPConnection", connect)
    monkeypatch.setenv("APIHUB_PORT", "18090")
    monkeypatch.setenv("APIHUB_LINTER_PORT", "18091")
    yield backend, linter

    assert backend.unexpected_requests == []
    assert linter.unexpected_requests == []


@pytest.fixture
def work_dir(monkeypatch, tmp_path):
    for name, version_key in (("previous", "previousNifiVersion"), ("current", "currentNifiVersion")):
        spec = {"openapi": "3.0.3", "info": {"title": "NiFi REST API", "version": VERSIONS[version_key]}, "paths": {}}
        (tmp_path / f"{name}.json").write_text(json.dumps(spec), encoding="utf-8")
    exceptions = tmp_path / "approved.yml"
    exceptions.write_text("exceptions: []\n", encoding="utf-8")
    lint_exceptions = tmp_path / "approved-lint.yml"
    lint_exceptions.write_text("exceptions: []\n", encoding="utf-8")
    monkeypatch.setattr(check_openapi, "APPROVED_LINT_EXCEPTIONS_PATH", lint_exceptions)
    monkeypatch.setenv("GITHUB_OUTPUT", str(tmp_path / "outputs"))
    monkeypatch.setenv("GITHUB_STEP_SUMMARY", str(tmp_path / "summary"))
    monkeypatch.setattr(sys, "argv", ["check_openapi.py", str(tmp_path), "--exceptions", str(exceptions)])
    return tmp_path


def read_report(work_dir, name):
    return json.loads((work_dir / "reports" / f"{name}.json").read_text(encoding="utf-8"))


def outputs(work_dir):
    return dict(line.split("=", 1) for line in (work_dir / "outputs").read_text(encoding="utf-8").splitlines())


def unpack_publish(request):
    header = f"Content-Type: {request.headers['Content-Type']}\r\n\r\n".encode()
    multipart = BytesParser(policy=policy.default).parsebytes(header + request.body)
    parts = {part.get_param("name", header="Content-Disposition"): part.get_payload(decode=True)
             for part in multipart.iter_parts()}
    with zipfile.ZipFile(io.BytesIO(parts["sources"])) as archive:
        return json.loads(parts["config"]), json.loads(archive.read("openapi.json"))


def test_publishes_both_specs_as_distinct_drafts_and_waits_for_lint(apihub, work_dir, monkeypatch):
    backend, linter = apihub
    backend.respond("GET", f"{PUBLISH}/baseline/status", Response(200, {"status": "running"}),
                    Response(200, {"status": "complete"}))
    linter.respond("GET", f"{LINT}/summary", Response(404), Response(200, {"status": "inProgress"}),
                   Response(200, {"status": "success", "documents": [{"slug": "openapi"}]}))
    previous = json.loads((work_dir / "previous.json").read_bytes())
    current = {**previous, "paths": {"/flow": {"get": {}}}}
    (work_dir / "current.json").write_text(json.dumps(current), encoding="utf-8")
    monkeypatch.setattr(sys, "argv", ["check_openapi.py", str(work_dir)])

    assert check_openapi.main() == 0

    login, *requests = backend.requests
    credential = base64.b64encode(b"ci@example.test:mock-password").decode()
    assert (login.target, login.headers["Authorization"]) == ("/api/v3/auth/local", f"Basic {credential}")
    assert {request.headers["Cookie"] for request in [*requests, *linter.requests]} == {
        "apihub-access-token=mock-session"
    }
    workspace, package = [json.loads(request.body) for request in requests if request.target == "/api/v2/packages"]
    assert (workspace["kind"], workspace["parentId"]) == ("workspace", "")
    assert package == {"alias": "NIFI", "kind": "package", "name": "NIFI", "parentId": "CI"}
    baseline_publish, candidate_publish = [unpack_publish(request) for request in requests if request.target == PUBLISH]
    common = {"packageId": "CI.NIFI", "status": "draft", "files": [{"fileId": "openapi.json", "publish": True}]}
    assert baseline_publish == ({**common, "version": "0.0.1"}, previous)
    assert candidate_publish == ({**common, "version": "0.0.2", "previousVersion": "0.0.1"}, current)
    assert [request.target for request in linter.requests] == [
        f"{LINT}/summary", f"{LINT}/summary", f"{LINT}/summary", f"{LINT}/documents/openapi/details",
    ]
    assert outputs(work_dir) == {"lint_findings": "false", "breaking": "false"}


def test_unapproved_lint_warning_fails_and_proposes_an_exact_exception(apihub, work_dir):
    _, linter = apihub
    mock_lint(linter, "warning")

    assert check_openapi.main() == 1
    assert outputs(work_dir) == {"lint_findings": "true", "breaking": "false"}
    assert read_report(work_dir, "lint-issues")[0]["results"][0]["issues"] == [lint_issue("warning")]
    assert read_report(work_dir, "check-failures") == {
        "lint": "Unapproved lint findings (errors 0, warnings 1)",
    }
    assert yaml.safe_load((work_dir / "reports" / LINT_PROPOSAL).read_text(encoding="utf-8")) == {
        "exceptions": [{**LINT_SCOPE, "issues": [{
            **lint_issue("warning"), "reason": "Add the reason for accepting this lint finding.",
        }]}],
    }


@pytest.mark.parametrize("approved_severities", [("error", "warning"), ("error",)], ids=["all-approved", "partial"])
def test_lint_exceptions_preserve_findings_and_gate_only_unapproved_ones(apihub, work_dir, approved_severities):
    _, linter = apihub
    mock_lint(linter, "error", "warning", "info")
    reason = "The existing finding was accepted for this NiFi version."
    approvals = [{**lint_issue(severity), "reason": reason} for severity in approved_severities]
    config = work_dir / "approved-lint.yml"
    config.write_text(yaml.safe_dump({"exceptions": [{**LINT_SCOPE, "issues": approvals}]}), encoding="utf-8")
    approved_config = config.read_bytes()
    warning_is_approved = "warning" in approved_severities

    assert check_openapi.main() == (0 if warning_is_approved else 1)
    assert outputs(work_dir) == {"lint_findings": "true", "breaking": "false"}
    assert config.read_bytes() == approved_config
    assert read_report(work_dir, "lint-issues")[0]["results"][0]["issues"] == [
        {**lint_issue("error"), "exceptionReason": reason},
        {**lint_issue("warning"), "exceptionReason": reason} if warning_is_approved else lint_issue("warning"),
        lint_issue("info"),
    ]
    proposal = work_dir / "reports" / LINT_PROPOSAL
    if warning_is_approved:
        assert not proposal.exists()
    else:
        proposed, = yaml.safe_load(proposal.read_text(encoding="utf-8"))["exceptions"]
        assert proposed["issues"] == [
            *approvals, {**lint_issue("warning"), "reason": "Add the reason for accepting this lint finding."},
        ]
        assert read_report(work_dir, "check-failures") == {
            "lint": "Unapproved lint findings (errors 0, warnings 1)",
        }


def test_lint_fails_when_api_details_omit_a_reported_warning(apihub, work_dir):
    _, linter = apihub
    linter.respond("GET", f"{LINT}/summary", Response(200, {
        "status": "success", "documents": [{"slug": "openapi", "issuesSummary": {"warning": 1}}],
    }))

    assert check_openapi.main() == 1
    assert "returned 0 of 1 warning findings" in read_report(work_dir, "check-failures")["lint"]
    assert not (work_dir / "reports" / LINT_PROPOSAL).exists()


def test_document_level_lint_finding_without_a_wire_path_can_be_proposed(apihub, work_dir):
    _, linter = apihub
    mock_lint(linter, "warning")
    issue = {"severity": "warning", "code": "info-description", "message": "The document needs a description."}
    linter.respond("GET", f"{LINT}/documents/openapi/details", Response(200, {
        "results": [{
            "linter": "spectral", "ruleset": {"name": LINT_SCOPE["ruleset"]}, "status": "success", "issues": [issue],
        }],
    }))

    assert check_openapi.main() == 1
    assert read_report(work_dir, "lint-issues")[0]["results"][0]["issues"] == [{**issue, "path": []}]
    proposed, = yaml.safe_load((work_dir / "reports" / LINT_PROPOSAL).read_text(encoding="utf-8"))["exceptions"]
    assert proposed == {
        **LINT_SCOPE,
        "issues": [{**issue, "path": [], "reason": "Add the reason for accepting this lint finding."}],
    }


def test_spectral_execution_failure_fails_without_a_proposed_exception(apihub, work_dir):
    _, linter = apihub
    linter.respond("GET", f"{LINT}/documents/openapi/details", Response(200, {
        "results": [{
            "linter": "spectral", "ruleset": {"name": LINT_SCOPE["ruleset"]}, "status": "error", "issues": [],
            "details": "Spectral process failed.",
        }],
    }))

    assert check_openapi.main() == 1
    assert "did not return a successful Spectral result" in read_report(work_dir, "check-failures")["lint"]
    assert read_report(work_dir, "lint-issues")[0]["results"][0]["details"] == "Spectral process failed."
    assert not (work_dir / "reports" / LINT_PROPOSAL).exists()


def test_unapproved_breaks_and_lint_errors_keep_both_reports_and_a_proposal(apihub, work_dir):
    backend, linter = apihub
    mock_lint(linter, "error")
    mock_breaking_change(backend)

    assert check_openapi.main() == 1

    assert outputs(work_dir) == {"lint_findings": "true", "breaking": "true"}
    assert read_report(work_dir, "check-failures") == {
        "lint": "Unapproved lint findings (errors 1, warnings 0)", "compatibility": "Unapproved breaking finding count = 1",
    }
    assert read_report(work_dir, "compatibility-issues")[0]["issues"] == [BREAKING]
    assert read_report(work_dir, "lint-issues")[0]["results"][0]["issues"][0]["severity"] == "error"
    assert (work_dir / "reports" / LINT_PROPOSAL).is_file()
    proposed, = yaml.safe_load((work_dir / "reports" / PROPOSAL).read_text(encoding="utf-8"))["exceptions"]
    assert {field: proposed[field] for field in VERSIONS} == VERSIONS
    match, = proposed["changes"]
    assert match == {
        **{field: OPERATION[field] for field in ("method", "path")},
        **{field: value for field, value in BREAKING.items() if field != "severity"},
        "reason": "Add the reason for accepting this change.",
    }
    comparison_query = next(urlsplit(request.target).query for request in backend.requests
                            if urlsplit(request.target).path == OPERATIONS)
    assert parse_qs(comparison_query) == {
        "severity": ["breaking"], "previousVersion": ["0.0.1"], "previousVersionPackageId": ["CI.NIFI"],
        "limit": ["100"], "page": ["0"],
    }


def test_successful_rerun_clears_obsolete_proposals_and_failure_report(apihub, work_dir):
    backend, linter = apihub
    mock_lint(linter, "warning")
    mock_breaking_change(backend)
    reports = work_dir / "reports"

    assert check_openapi.main() == 1
    lint_proposal = reports / LINT_PROPOSAL
    compatibility_proposal = reports / PROPOSAL
    failures = reports / "check-failures.json"
    assert {path.name for path in reports.iterdir()} >= {LINT_PROPOSAL, PROPOSAL, failures.name}
    (work_dir / "approved-lint.yml").write_bytes(lint_proposal.read_bytes())
    (work_dir / "approved.yml").write_bytes(compatibility_proposal.read_bytes())
    notes = reports / "notes.txt"
    notes.write_text("Review notes", encoding="utf-8")
    mock_publication(backend)
    mock_lint(linter, "warning")
    mock_breaking_change(backend)

    assert check_openapi.main() == 0
    assert {path.name for path in reports.iterdir()} == {
        "notes.txt", "lint-issues.json", "lint-summary.json", "spec-versions.json",
        "compatibility-issues.json", "compatibility-summary.json",
    }
    assert notes.read_text(encoding="utf-8") == "Review notes"


def test_rerun_can_use_a_proposal_as_input_and_write_a_followup(apihub, work_dir, monkeypatch):
    backend, linter = apihub
    mock_breaking_change(backend)
    reports = work_dir / "reports"

    assert check_openapi.main() == 1
    proposal = reports / PROPOSAL
    original = proposal.read_bytes()
    monkeypatch.setattr(sys, "argv", ["check_openapi.py", str(work_dir), "--exceptions", str(proposal)])
    mock_publication(backend)
    mock_lint(linter)
    mock_breaking_change(backend, {**BREAKING, "previousValueHash": "new-hash"})

    assert check_openapi.main() == 1
    assert proposal.read_bytes() == original
    followup = reports / "next-openapi-compatibility-exceptions.yml"
    groups = yaml.safe_load(followup.read_text(encoding="utf-8"))["exceptions"]
    assert [issue["previousValueHash"] for group in groups for issue in group["changes"]] == [
        "previous-hash", "new-hash",
    ]


def test_rerun_rejects_an_approved_file_in_a_reserved_report_path(work_dir, monkeypatch, capsys):
    reports = work_dir / "reports"
    reports.mkdir()
    approved = reports / "check-failures.json"
    original = b'{"exceptions": []}\n'
    approved.write_bytes(original)
    monkeypatch.setattr(sys, "argv", ["check_openapi.py", str(work_dir), "--exceptions", str(approved)])

    assert check_openapi.main() == 1
    assert approved.read_bytes() == original
    assert "Choose an exceptions file outside the reserved report path" in capsys.readouterr().err


@pytest.mark.parametrize(("lint_issues", "status"), [((), 0), (("error",), 1)], ids=["clean-lint", "lint-error"])
def test_approved_breaks_still_keep_the_report_and_apply_the_lint_gate(apihub, work_dir, lint_issues, status):
    backend, linter = apihub
    mock_lint(linter, *lint_issues)
    mock_breaking_change(backend)
    reason = "The obsolete response header was reviewed and removed."
    approval = {field: value for field, value in BREAKING.items() if field != "severity"}
    approval.update(method="get", path="/nifi-api/flow", reason=reason)
    (work_dir / "approved.yml").write_text(
        yaml.safe_dump({"exceptions": [{**VERSIONS, "changes": [approval]}]}), encoding="utf-8",
    )

    assert check_openapi.main() == status
    assert outputs(work_dir) == {"lint_findings": str(bool(lint_issues)).lower(), "breaking": "true"}
    assert read_report(work_dir, "compatibility-issues")[0]["issues"] == [{**BREAKING, "exceptionReason": reason}]
    assert not (work_dir / "reports" / PROPOSAL).exists()
    failures = work_dir / "reports" / "check-failures.json"
    assert (json.loads(failures.read_text(encoding="utf-8")) if failures.exists() else {}) == (
        {"lint": "Unapproved lint findings (errors 1, warnings 0)"} if lint_issues else {}
    )


def test_added_spec_runs_lint_and_skips_compatibility(apihub, work_dir):
    backend, linter = apihub
    (work_dir / "previous.json").unlink()
    backend.respond("POST", PUBLISH, Response(202, {"publishId": "candidate"}))
    mock_lint(linter, "error")

    assert check_openapi.main() == 1
    publish, = [unpack_publish(request)[0] for request in backend.requests if request.target == PUBLISH]
    assert publish["version"] == "0.0.2"
    assert "previousVersion" not in publish
    assert read_report(work_dir, "check-failures") == {"lint": "Unapproved lint findings (errors 1, warnings 0)"}
    assert not (work_dir / "reports" / "compatibility-summary.json").exists()


def test_failed_baseline_publication_still_runs_candidate_lint(apihub, work_dir):
    backend, linter = apihub
    backend.respond("GET", f"{PUBLISH}/baseline/status", Response(200, {"status": "error", "message": "Bad baseline"}))
    mock_lint(linter, "warning")

    assert check_openapi.main() == 1
    _, candidate = [unpack_publish(request)[0] for request in backend.requests if request.target == PUBLISH]
    assert "previousVersion" not in candidate
    assert outputs(work_dir) == {"lint_findings": "true", "breaking": "false"}
    assert "Bad baseline" in read_report(work_dir, "check-failures")["compatibility"]
    assert (work_dir / "reports" / "lint-issues.json").is_file()
    assert not (work_dir / "reports" / "compatibility-summary.json").exists()


def test_comparison_preserves_available_findings_when_an_operation_details_request_fails(apihub, work_dir):
    backend, _ = apihub
    mock_comparison(backend, breaking=2, affected=2)
    backend.respond("GET", OPERATIONS, Response(200, {"operations": [
        {"currentOperation": OPERATION, "changeSummary": {"breaking": 1}},
        {"previousOperation": {"method": "get", "path": "/nifi-api/about"}, "changeSummary": {"breaking": 1}},
    ]}))
    backend.respond("GET", f"{CANDIDATE}/rest/operations/get-flow/changes", Response(503))

    assert check_openapi.main() == 1
    operations = read_report(work_dir, "compatibility-issues")
    failure = next(operation for operation in operations if operation.get("currentOperation") == OPERATION)
    assert "HTTP 503" in failure["detailError"]
    removal = next(operation for operation in operations if "previousOperation" in operation)
    assert removal["issues"] == [{
        "severity": "breaking", "action": "remove", "scope": "root", "description": "The operation was removed.",
    }]
    proposed, = yaml.safe_load((work_dir / "reports" / PROPOSAL).read_text(encoding="utf-8"))["exceptions"]
    match, = proposed["changes"]
    assert match["path"] == "/nifi-api/about"
    assert "incomplete breaking issue details" in read_report(work_dir, "check-failures")["compatibility"]


def test_comparison_reads_all_pages_of_breaking_operations(apihub, work_dir):
    backend, _ = apihub
    removals = [
        {"previousOperation": {"method": "get", "path": f"/operation-{index}"}, "changeSummary": {"breaking": 1}}
        for index in range(101)
    ]
    mock_comparison(backend, breaking=len(removals), affected=len(removals))
    backend.respond("GET", OPERATIONS, Response(200, {"operations": removals[:100]}),
                    Response(200, {"operations": removals[100:]}))

    assert check_openapi.main() == 1
    pages = [parse_qs(urlsplit(request.target).query)["page"] for request in backend.requests
             if urlsplit(request.target).path == OPERATIONS]
    assert pages == [["0"], ["1"]]
    report = read_report(work_dir, "compatibility-issues")
    assert [item["previousOperation"]["path"] for item in report] == [f"/operation-{index}" for index in range(101)]

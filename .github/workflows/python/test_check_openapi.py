import base64
from collections import deque
from contextlib import suppress
from dataclasses import dataclass, field
from email import policy
from email.parser import BytesParser
import io
import json
from socketserver import StreamRequestHandler, TCPServer
import sys
from threading import Event, Thread
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit
import zipfile

import pytest
import yaml

import check_openapi
from openapi_exceptions import REASON_PLACEHOLDER
from openapi_lint_exceptions import REASON_PLACEHOLDER as LINT_REASON_PLACEHOLDER


PACKAGE = "/api/v2/packages/CI.NIFI"
PUBLISH = f"{PACKAGE}/publish"
CANDIDATE = f"{PACKAGE}/versions/0.0.2"
LINT = f"{CANDIDATE}/validation"
START_LINT = "/api/v1/packages/CI.NIFI/versions/0.0.2/validation"
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

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return None


class SlowResponse(Response):
    def read(self):
        check_openapi.time.sleep(20)
        return super().read()


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

    def connect(self):
        self.sock = SimpleNamespace(shutdown=lambda how: None)

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
    clock = SimpleNamespace(now=0.0)

    def sleep(seconds):
        clock.now += seconds

    monkeypatch.setattr(check_openapi, "time", SimpleNamespace(monotonic=lambda: clock.now, sleep=sleep))
    monkeypatch.setenv("APIHUB_ADMIN_EMAIL", "apihub-test-user@example.com")
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
    monkeypatch.setenv("GITHUB_STEP_SUMMARY", str(tmp_path / "summary"))
    monkeypatch.setattr(sys, "argv", ["check_openapi.py", str(tmp_path), "--exceptions", str(exceptions)])
    return tmp_path


def read_report(work_dir, name):
    return json.loads((work_dir / "reports" / f"{name}.json").read_text(encoding="utf-8"))


def unpack_publish(request):
    header = f"Content-Type: {request.headers['Content-Type']}\r\n\r\n".encode()
    multipart = BytesParser(policy=policy.default).parsebytes(header + request.body)
    parts = {part.get_param("name", header="Content-Disposition"): part.get_payload(decode=True)
             for part in multipart.iter_parts()}
    with zipfile.ZipFile(io.BytesIO(parts["sources"])) as archive:
        return json.loads(parts["config"]), json.loads(archive.read("openapi.json"))


def test_publishes_both_specs_as_distinct_drafts_and_waits_for_lint(apihub, work_dir, monkeypatch, capsys):
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
    credential = base64.b64encode(b"apihub-test-user@example.com:mock-password").decode()
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
    assert (work_dir / "reports" / "compatibility-summary.json").is_file()
    assert "Requesting APIHUB lint" not in capsys.readouterr().out


def test_unapproved_lint_warning_fails_and_proposes_an_exact_exception(apihub, work_dir):
    _, linter = apihub
    mock_lint(linter, "warning")

    assert check_openapi.main() == 1
    assert read_report(work_dir, "lint-issues")[0]["results"][0]["issues"] == [lint_issue("warning")]
    assert read_report(work_dir, "check-failures") == {
        "lint": "Unapproved lint findings (errors 0, warnings 1)",
    }
    assert yaml.safe_load((work_dir / "reports" / LINT_PROPOSAL).read_text(encoding="utf-8")) == {
        "exceptions": [{**LINT_SCOPE, "issues": [{
            **lint_issue("warning"), "reason": LINT_REASON_PLACEHOLDER,
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
            *approvals, {**lint_issue("warning"), "reason": LINT_REASON_PLACEHOLDER},
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
        "issues": [{**issue, "path": [], "reason": LINT_REASON_PLACEHOLDER}],
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
    compatibility_header = "# Approve only reviewed breaking changes.\n"
    lint_header = "# Approved Spectral warnings.\n# Keep the reason with each approval.\n\n"
    (work_dir / "approved.yml").write_text(compatibility_header + "exceptions: []\n", encoding="utf-8")
    (work_dir / "approved-lint.yml").write_text(lint_header + "exceptions: []\n", encoding="utf-8")

    assert check_openapi.main() == 1
    lint_proposal = reports / LINT_PROPOSAL
    compatibility_proposal = reports / PROPOSAL
    failures = reports / "check-failures.json"
    assert {path.name for path in reports.iterdir()} >= {LINT_PROPOSAL, PROPOSAL, failures.name}
    lint_text = lint_proposal.read_text(encoding="utf-8")
    compatibility_text = compatibility_proposal.read_text(encoding="utf-8")
    assert lint_text.startswith(lint_header + "exceptions:\n")
    assert compatibility_text.startswith(compatibility_header + "exceptions:\n")
    (work_dir / "approved-lint.yml").write_text(lint_text.replace(
        LINT_REASON_PLACEHOLDER, "The warning was reviewed."), encoding="utf-8")
    (work_dir / "approved.yml").write_text(compatibility_text.replace(
        REASON_PLACEHOLDER, "The ETag header was reviewed."), encoding="utf-8")
    notes = reports / "notes.txt"
    notes.write_text("Review notes", encoding="utf-8")
    (reports / "unused-compatibility-exceptions.json").write_text("[{}]\n", encoding="utf-8")
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
    reviewed = proposal.read_text(encoding="utf-8").replace(REASON_PLACEHOLDER, "The ETag header was reviewed.")
    proposal.write_text(reviewed, encoding="utf-8")
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
    assert read_report(work_dir, "compatibility-issues")[0]["issues"] == [{**BREAKING, "exceptionReason": reason}]
    assert not (work_dir / "reports" / PROPOSAL).exists()
    failures = work_dir / "reports" / "check-failures.json"
    assert (json.loads(failures.read_text(encoding="utf-8")) if failures.exists() else {}) == (
        {"lint": "Unapproved lint findings (errors 1, warnings 0)"} if lint_issues else {}
    )


@pytest.mark.parametrize("has_breaking_change", [False, True], ids=["no-breaks", "approved-break"])
def test_unused_compatibility_exceptions_are_reported_for_the_compared_pair(apihub, work_dir, has_breaking_change):
    backend, _ = apihub
    if has_breaking_change:
        mock_breaking_change(backend)
    approval = {
        "method": "get", "path": OPERATION["path"], "reason": "The response header removal was reviewed.",
        **{field: value for field, value in BREAKING.items() if field != "severity"},
    }
    obsolete = {**approval, "previousValueHash": "obsolete-hash"}
    approvals = {"exceptions": [
        {**VERSIONS, "changes": [approval, obsolete]},
        {**VERSIONS, "previousNifiVersion": "2.8.0", "changes": [approval]},
    ]}
    approved = work_dir / "approved.yml"
    approved.write_text(yaml.safe_dump(approvals), encoding="utf-8")

    assert check_openapi.main() == 0
    unused = [obsolete] if has_breaking_change else [approval, obsolete]
    assert read_report(work_dir, "unused-compatibility-exceptions") == [{**VERSIONS, **change} for change in unused]
    assert (
        f"- Unused compatibility exceptions ({len(unused)}). See unused-compatibility-exceptions.json.\n"
        in (work_dir / "summary").read_text(encoding="utf-8")
    )
    assert not (work_dir / "reports" / PROPOSAL).exists()
    assert yaml.safe_load(approved.read_text(encoding="utf-8")) == approvals


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


def test_login_without_a_session_cookie_adds_setup_failure_to_the_summary(apihub, work_dir):
    backend, _ = apihub
    backend.respond("POST", "/api/v3/auth/local", Response(200))

    assert check_openapi.main() == 1
    assert read_report(work_dir, "check-failures") == {"setup": "KeyError('apihub-access-token')"}
    assert (work_dir / "summary").read_text(encoding="utf-8") == (
        "## OpenAPI checks\n\n- OpenAPI check setup failed. See check-failures.json in the openapi-reports artifact.\n"
    )


def test_failed_baseline_publication_still_runs_candidate_lint(apihub, work_dir):
    backend, linter = apihub
    backend.respond("GET", f"{PUBLISH}/baseline/status", Response(200, {"status": "error", "message": "Bad baseline"}))
    mock_lint(linter, "warning")

    assert check_openapi.main() == 1
    _, candidate = [unpack_publish(request)[0] for request in backend.requests if request.target == PUBLISH]
    assert "previousVersion" not in candidate
    assert "Bad baseline" in read_report(work_dir, "check-failures")["compatibility"]
    assert (work_dir / "reports" / "lint-issues.json").is_file()
    assert not (work_dir / "reports" / "compatibility-summary.json").exists()


def test_failed_candidate_publication_keeps_the_summary_and_the_baseline_failure(apihub, work_dir):
    backend, linter = apihub
    backend.respond("GET", f"{PUBLISH}/baseline/status", Response(200, {"status": "error", "message": "Bad baseline"}))
    backend.respond("GET", f"{PUBLISH}/candidate/status", Response(200, {"status": "error", "message": "Bad candidate"}))

    assert check_openapi.main() == 1
    failures = read_report(work_dir, "check-failures")
    assert sorted(failures) == ["compatibility", "publication"]
    assert "Bad baseline" in failures["compatibility"]
    assert "Bad candidate" in failures["publication"]
    assert "APIHUB could not publish the candidate spec." in (work_dir / "summary").read_text(encoding="utf-8")
    assert linter.requests == []


def test_failed_candidate_publication_skips_lint_and_comparison(apihub, work_dir):
    backend, linter = apihub
    backend.respond("GET", f"{PUBLISH}/candidate/status", Response(200, {"status": "error", "message": "Bad candidate"}))

    assert check_openapi.main() == 1
    assert list(read_report(work_dir, "check-failures")) == ["publication"]
    assert linter.requests == []
    assert [request.target for request in backend.requests if urlsplit(request.target).path == COMPARISON] == []


@pytest.mark.parametrize("status", [200, 202], ids=["ok", "accepted"])
def test_lint_requests_the_validation_once_when_no_task_appears_within_the_grace_period(apihub, work_dir, capsys, status):
    _, linter = apihub
    linter.respond("GET", f"{LINT}/summary", *[Response(404)] * 15,
                   Response(200, {"status": "success", "documents": [{"slug": "openapi"}]}))
    linter.respond("POST", START_LINT, Response(status, {"taskId": "lint"}))

    assert check_openapi.main() == 0
    assert capsys.readouterr().out.count("Requesting APIHUB lint after the validation summary returned HTTP 404.") == 1
    assert [(request.method, request.target) for request in linter.requests] == [
        *[("GET", f"{LINT}/summary")] * 11, ("POST", START_LINT), *[("GET", f"{LINT}/summary")] * 5,
        ("GET", f"{LINT}/documents/openapi/details"),
    ]


def test_lint_fallback_reports_an_http_conflict_as_a_failure(apihub, work_dir):
    _, linter = apihub
    linter.respond("GET", f"{LINT}/summary", *[Response(404)] * 11)
    linter.respond("POST", START_LINT, Response(409))

    assert check_openapi.main() == 1, (
        "The linter's POST validation endpoint always queues a new task and returns 202, so any other status fails"
    )
    assert "HTTP 409" in read_report(work_dir, "check-failures")["lint"]
    assert [(request.method, request.target) for request in linter.requests if request.method == "POST"] == [
        ("POST", START_LINT),
    ]


def test_lint_times_out_after_one_validation_request_when_no_task_ever_appears(apihub, work_dir):
    _, linter = apihub
    linter.respond("GET", f"{LINT}/summary", *[Response(404)] * 60)
    linter.respond("POST", START_LINT, Response(202, {"taskId": "lint"}))

    assert check_openapi.main() == 1
    assert read_report(work_dir, "check-failures")["lint"] == "TimeoutError('APIHUB linting timed out at its 120 s limit')"
    assert [request.method for request in linter.requests].count("POST") == 1


def test_slow_publications_shorten_the_lint_wait_to_the_shared_budget(apihub, work_dir):
    backend, linter = apihub
    for build in ("baseline", "candidate"):
        backend.respond("GET", f"{PUBLISH}/{build}/status", *[Response(200, {"status": "running"})] * 55,
                        Response(200, {"status": "complete"}))
    linter.respond("GET", f"{LINT}/summary", *[Response(404)] * 10)

    assert check_openapi.main() == 1
    assert read_report(work_dir, "check-failures")["lint"] == (
        "TimeoutError('APIHUB linting timed out at the 240 s check budget')"
    )
    assert len(linter.requests) == 10, "lint starts at 220 s and polls every 2 s until the 240 s budget ends"


@pytest.mark.parametrize(("status", "payload"), [
    pytest.param(404, None, id="no-task"),
    pytest.param(200, {"status": "success", "documents": [{"slug": "openapi"}]}, id="task-finished"),
])
def test_lint_summary_arriving_after_cutoff_cannot_start_another_request(apihub, work_dir, status, payload):
    backend, linter = apihub
    for build in ("baseline", "candidate"):
        backend.respond("GET", f"{PUBLISH}/{build}/status", *[Response(200, {"status": "running"})] * 55,
                        Response(200, {"status": "complete"}))
    linter.respond("GET", f"{LINT}/summary", *[Response(404)] * 9, SlowResponse(status, payload))

    assert check_openapi.main() == 1
    assert [(request.method, request.target) for request in linter.requests] == [("GET", f"{LINT}/summary")] * 10
    assert read_report(work_dir, "check-failures") == {
        "lint": "TimeoutError('APIHUB linting timed out at the 240 s check budget')",
    }
    assert read_report(work_dir, "compatibility-summary")["hasErrors"] is False


def test_request_deadline_interrupts_a_trickling_response_body(monkeypatch):
    body_started = Event()
    body_completed = Event()
    stop = Event()

    class TricklingResponse(StreamRequestHandler):
        def handle(self):
            for line in self.rfile:
                if line == b"\r\n":
                    break
            self.wfile.write(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n{}")
            body_started.set()
            with suppress(ConnectionError):
                for _ in range(80):
                    if stop.wait(0.025):
                        return
                    self.wfile.write(b" ")
                body_completed.set()

    with TCPServer(("127.0.0.1", 0), TricklingResponse) as server:
        monkeypatch.setenv("APIHUB_PORT", str(server.server_address[1]))
        worker = Thread(target=server.handle_request, daemon=True)
        worker.start()
        deadline = check_openapi.Deadline(check_openapi.time.monotonic() + 0.5, "Response deadline expired")
        try:
            with pytest.raises(TimeoutError, match="Response deadline expired"):
                check_openapi.ApiHub().get("/slow-response", deadline=deadline)
            assert (body_started.is_set(), body_completed.is_set()) == (True, False)
        finally:
            stop.set()
            worker.join(timeout=2)


def test_the_check_budget_also_ends_a_publication_before_its_own_limit(apihub, work_dir, monkeypatch):
    backend, linter = apihub
    monkeypatch.setattr(check_openapi, "CHECK_BUDGET", 150)
    backend.respond("GET", f"{PUBLISH}/baseline/status", *[Response(200, {"status": "running"})] * 55,
                    Response(200, {"status": "complete"}))
    backend.respond("GET", f"{PUBLISH}/candidate/status", *[Response(200, {"status": "running"})] * 20)

    assert check_openapi.main() == 1
    assert read_report(work_dir, "check-failures") == {
        "publication": "TimeoutError('APIHUB publication 0.0.2 timed out at the 150 s check budget')",
    }
    candidate_polls = [request for request in backend.requests if request.target == f"{PUBLISH}/candidate/status"]
    assert len(candidate_polls) == 20, "the candidate starts at 110 s and polls every 2 s until the 150 s budget ends"
    assert linter.requests == []


def test_unused_lint_exceptions_are_reported_without_failing_the_check(apihub, work_dir):
    backend, linter = apihub
    approval = {**lint_issue("warning"), "reason": "The warning was reviewed."}
    approval_path = work_dir / "approved-lint.yml"
    config = yaml.safe_dump({"exceptions": [{**LINT_SCOPE, "issues": [approval]}]})
    approval_path.write_text(config, encoding="utf-8")

    assert check_openapi.main() == 0
    assert "- Unused lint exceptions (1). See unused-lint-exceptions.json.\n" in (work_dir / "summary").read_text(
        encoding="utf-8",
    )
    assert read_report(work_dir, "unused-lint-exceptions") == [{**LINT_SCOPE, **approval}]
    assert approval_path.read_text(encoding="utf-8") == config

    mock_publication(backend)
    mock_lint(linter, "warning")
    mock_comparison(backend)

    assert check_openapi.main() == 0
    assert not (work_dir / "reports" / "unused-lint-exceptions.json").exists()
    assert read_report(work_dir, "lint-issues")[0]["results"][0]["issues"] == [
        {**lint_issue("warning"), "exceptionReason": approval["reason"]},
    ]


def test_a_removed_operation_is_complete_whatever_breaking_count_apihub_reports(apihub, work_dir):
    backend, _ = apihub
    mock_comparison(backend, breaking=2, affected=1)
    backend.respond("GET", OPERATIONS, Response(200, {"operations": [
        {"previousOperation": {"method": "get", "path": "/nifi-api/about"}, "changeSummary": {"breaking": 2}},
    ]}))

    assert check_openapi.main() == 1
    assert read_report(work_dir, "check-failures") == {"compatibility": "Unapproved breaking finding count = 1"}


def test_a_current_operation_with_fewer_breaking_details_than_its_count_is_incomplete(apihub, work_dir):
    backend, _ = apihub
    mock_comparison(backend, breaking=2, affected=1)
    backend.respond("GET", OPERATIONS, Response(200, {"operations": [
        {"currentOperation": OPERATION, "changeSummary": {"breaking": 2}},
    ]}))
    backend.respond("GET", f"{CANDIDATE}/rest/operations/get-flow/changes", Response(200, {"changes": [BREAKING]}))

    assert check_openapi.main() == 1
    assert read_report(work_dir, "check-failures") == {
        "compatibility": "RuntimeError('APIHUB returned incomplete breaking issue details for 1 operations')",
    }
    operation, = read_report(work_dir, "compatibility-issues")
    assert operation["detailError"] == "RuntimeError('APIHUB returned 1 of 2 breaking issue details')"


@pytest.mark.parametrize("status", ["error", "failed"], ids=["error", "unknown-status"])
def test_lint_stops_polling_on_a_final_status_other_than_success(apihub, work_dir, status):
    _, linter = apihub
    linter.respond("GET", f"{LINT}/summary", Response(200, {"status": status, "documents": []}))

    assert check_openapi.main() == 1
    assert f"APIHUB linting ended with status '{status}'" in read_report(work_dir, "check-failures")["lint"]
    assert [request.target for request in linter.requests] == [f"{LINT}/summary"]


def test_comparison_preserves_available_findings_when_an_operation_details_request_fails(apihub, work_dir):
    backend, _ = apihub
    mock_comparison(backend, breaking=2, affected=2)
    backend.respond("GET", OPERATIONS, Response(200, {"operations": [
        {"currentOperation": OPERATION, "changeSummary": {"breaking": 1}},
        {"previousOperation": {"method": "get", "path": "/nifi-api/about"}, "changeSummary": {"breaking": 1}},
    ]}))
    backend.respond("GET", f"{CANDIDATE}/rest/operations/get-flow/changes", Response(503))
    approval = {
        "method": OPERATION["method"], "path": OPERATION["path"], "reason": "The ETag removal was reviewed.",
        **{field: value for field, value in BREAKING.items() if field != "severity"},
    }
    (work_dir / "approved.yml").write_text(
        yaml.safe_dump({"exceptions": [{**VERSIONS, "changes": [approval]}]}), encoding="utf-8",
    )

    assert check_openapi.main() == 1
    operations = read_report(work_dir, "compatibility-issues")
    failure = next(operation for operation in operations if operation.get("currentOperation") == OPERATION)
    assert "HTTP 503" in failure["detailError"]
    removal = next(operation for operation in operations if "previousOperation" in operation)
    assert removal["issues"] == [{
        "severity": "breaking", "action": "remove", "scope": "root", "description": "The operation was removed.",
    }]
    assert not (work_dir / "reports" / PROPOSAL).exists()
    assert not (work_dir / "reports" / "unused-compatibility-exceptions.json").exists()
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


@pytest.mark.parametrize(("failed_page", "detail"), [
    pytest.param(Response(503), "HTTP 503", id="http-error"),
    pytest.param(Response(200, {"operations": {"unexpected": 0}}), "Page 1 did not contain", id="malformed-page"),
    pytest.param(SlowResponse(200, {"operations": []}), "timed out at the 10 s comparison cutoff", id="timeout"),
])
def test_comparison_keeps_operations_from_pages_before_a_failed_page(apihub, work_dir, monkeypatch, failed_page,
                                                                     detail):
    backend, _ = apihub
    monkeypatch.setattr(check_openapi, "COMPARISON_CUTOFF", 10)
    first_page = [
        {"previousOperation": {"method": "get", "path": f"/operation-{index}"}, "changeSummary": {"breaking": 1}}
        for index in range(100)
    ]
    mock_comparison(backend, breaking=101, affected=101)
    backend.respond("GET", OPERATIONS, Response(200, {"operations": first_page}), failed_page)

    assert check_openapi.main() == 1
    assert read_report(work_dir, "compatibility-issues") == first_page
    failure = read_report(work_dir, "check-failures")["compatibility"]
    assert "returned 100 of 101 affected operations" in failure
    assert detail in failure
    assert not (work_dir / "reports" / PROPOSAL).exists()


def test_comparison_reports_breaking_changes_with_zero_affected_operations(apihub, work_dir):
    backend, _ = apihub
    mock_comparison(backend, breaking=3, affected=0)

    assert check_openapi.main() == 1
    assert read_report(work_dir, "check-failures") == {
        "compatibility": "RuntimeError('APIHUB reported 3 breaking changes affecting 0 operations')",
    }
    assert read_report(work_dir, "compatibility-summary")["operationTypes"][0]["changesSummary"] == {"breaking": 3}
    assert [request.target for request in backend.requests if urlsplit(request.target).path == OPERATIONS] == []


def test_comparison_deadline_preserves_completed_details(apihub, work_dir, monkeypatch):
    backend, _ = apihub
    monkeypatch.setattr(check_openapi, "COMPARISON_CUTOFF", 10)
    removed = {"previousOperation": {"method": "get", "path": "/removed"}, "changeSummary": {"breaking": 1}}
    slow = {"currentOperation": OPERATION, "changeSummary": {"breaking": 1}}
    pending = {"currentOperation": {**OPERATION, "operationId": "get-about", "path": "/about"},
               "changeSummary": {"breaking": 1}}
    mock_comparison(backend, breaking=3, affected=3)
    backend.respond("GET", OPERATIONS, Response(200, {"operations": [removed, slow, pending]}))
    backend.respond("GET", f"{CANDIDATE}/rest/operations/get-flow/changes", SlowResponse(200, {"changes": [BREAKING]}))

    assert check_openapi.main() == 1
    timeout = "TimeoutError('APIHUB comparison timed out at the 10 s comparison cutoff')"
    assert read_report(work_dir, "check-failures") == {"compatibility": timeout}
    report = read_report(work_dir, "compatibility-issues")
    assert report == [
        {**removed, "issues": [{"severity": "breaking", "action": "remove", "scope": "root",
                               "description": "The operation was removed."}]},
        {**slow, "detailError": timeout},
        pending,
    ]
    assert not (work_dir / "reports" / PROPOSAL).exists()


def test_comparison_summary_decoded_after_cutoff_fails_the_check(apihub, work_dir, monkeypatch):
    monkeypatch.setattr(check_openapi, "COMPARISON_CUTOFF", 10)

    def slow_comparison_decode(data):
        document = json.loads(data)
        if "operationTypes" in document:
            check_openapi.time.sleep(10)
        return document

    monkeypatch.setattr(check_openapi, "json", SimpleNamespace(loads=slow_comparison_decode, dumps=json.dumps))

    assert check_openapi.main() == 1
    assert read_report(work_dir, "check-failures") == {
        "compatibility": "TimeoutError('APIHUB comparison timed out at the 10 s comparison cutoff')",
    }
    assert not (work_dir / "reports" / "compatibility-summary.json").exists()

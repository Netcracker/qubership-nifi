import argparse
import base64
from collections import Counter
import http.client
from http.cookies import SimpleCookie
import io
import json
import os
from pathlib import Path
import sys
import time
from urllib.parse import quote, urlencode
import uuid
import zipfile

from openapi_exceptions import APPROVED_EXCEPTIONS_PATH, apply_exceptions, load_exceptions
from openapi_lint_exceptions import (
    APPROVED_LINT_EXCEPTIONS_PATH, LINT_SEVERITIES, apply_lint_exceptions, load_lint_exceptions,
)


# The specs can declare the same NiFi version and still need distinct APIHUB drafts.
BASELINE = "0.0.1"
CANDIDATE = "0.0.2"
PUBLISH_TIMEOUT = 120
LINT_TIMEOUT = 120
PAGE_SIZE = 100
LINT_PROPOSAL = "proposed-openapi-lint-exceptions.yml"
COMPATIBILITY_PROPOSAL = "proposed-openapi-compatibility-exceptions.yml"
FOLLOWUP_COMPATIBILITY_PROPOSAL = "next-openapi-compatibility-exceptions.yml"
GENERATED_REPORTS = (
    "lint-summary.json", "lint-issues.json", "compatibility-summary.json", "compatibility-issues.json",
    "spec-versions.json", "check-failures.json", LINT_PROPOSAL, COMPATIBILITY_PROPOSAL, FOLLOWUP_COMPATIBILITY_PROPOSAL,
)


class ApiHub:
    def __init__(self):
        self.backend_port = int(os.environ.get("APIHUB_PORT", "18090"))
        self.linter_port = int(os.environ.get("APIHUB_LINTER_PORT", "18091"))
        self.headers = {}

    def request(self, method, path, *, body=None, headers=None, expected=(200,), linter=False):
        port = self.linter_port if linter else self.backend_port
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=30)
        try:
            connection.request(method, path, body, self.headers | (headers or {}))
            response = connection.getresponse()
            data = response.read()
            if response.status not in expected:
                raise RuntimeError(f"{method} {path}: HTTP {response.status}: {data[:500]!r}")
            return response.status, response.getheaders(), json.loads(data) if data else None
        finally:
            connection.close()

    def get(self, path, **kwargs):
        return self.request("GET", path, **kwargs)[2]

    def login(self):
        email = os.environ.get("APIHUB_ADMIN_EMAIL", "ci@example.test")
        password = os.environ["APIHUB_ADMIN_PASSWORD"]
        credentials = base64.b64encode(f"{email}:{password}".encode()).decode()
        _, headers, _ = self.request(
            "POST", "/api/v3/auth/local", headers={"Authorization": f"Basic {credentials}"}
        )
        cookies = SimpleCookie()
        for name, value in headers:
            if name.lower() == "set-cookie":
                cookies.load(value)
        self.headers["Cookie"] = f"apihub-access-token={cookies['apihub-access-token'].value}"

    def create_package(self, alias, kind, parent=""):
        body = json.dumps({"alias": alias, "kind": kind, "name": alias, "parentId": parent})
        _, _, package = self.request(
            "POST", "/api/v2/packages", body=body,
            headers={"Content-Type": "application/json"}, expected=(200, 201),
        )
        return package["packageId"]

    def publish(self, package, version, spec, previous=None):
        config = {
            "packageId": package, "version": version, "status": "draft",
            "files": [{"fileId": "openapi.json", "publish": True}],
        }
        if previous:
            config["previousVersion"] = previous
        boundary = uuid.uuid4().hex
        sources = io.BytesIO()
        with zipfile.ZipFile(sources, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("openapi.json", spec)
        body = b"".join([
            f'--{boundary}\r\nContent-Disposition: form-data; name="config"\r\n\r\n'.encode(),
            json.dumps(config).encode(),
            f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="sources"; filename="sources.zip"\r\n'
            'Content-Type: application/zip\r\n\r\n'.encode(),
            sources.getvalue(),
            f"\r\n--{boundary}--\r\n".encode(),
        ])
        endpoint = f"/api/v2/packages/{package}/publish"
        _, _, build = self.request(
            "POST", endpoint, body=body,
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}, expected=(202,),
        )
        deadline = time.monotonic() + PUBLISH_TIMEOUT
        while time.monotonic() < deadline:
            status = self.get(f"{endpoint}/{build['publishId']}/status")
            if status["status"] == "complete":
                return
            if status["status"] == "error":
                raise RuntimeError(f"APIHUB publication {version} failed: {status!r}")
            time.sleep(2)
        raise TimeoutError(f"APIHUB publication {version} timed out")


def write_report(reports, name, data):
    (reports / f"{name}.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def clear_generated_reports(reports, exceptions_path):
    approved_path = exceptions_path.resolve()
    for name in GENERATED_REPORTS:
        report = reports / name
        if report.resolve() != approved_path:
            report.unlink(missing_ok=True)


def lint(client, package, reports, nifi_version):
    endpoint = f"/api/v2/packages/{package}/versions/{CANDIDATE}/validation"
    deadline = time.monotonic() + LINT_TIMEOUT
    while time.monotonic() < deadline:
        status, _, summary = client.request(
            "GET", f"{endpoint}/summary", expected=(200, 404), linter=True,
        )
        if status == 200:
            write_report(reports, "lint-summary", summary)
            if summary["status"] in ("success", "error"):
                break
        time.sleep(2)
    else:
        raise TimeoutError("APIHUB linting timed out")

    documents = summary.get("documents", [])
    details = []
    for document in documents:
        slug = quote(document["slug"], safe="")
        details.append(client.get(f"{endpoint}/documents/{slug}/details", linter=True))
        write_report(reports, "lint-issues", details)
    if summary["status"] != "success" or len(details) != 1:
        raise RuntimeError("APIHUB did not validate the candidate OpenAPI document")

    spectral = [result for result in details[0]["results"] if result["linter"] == "spectral"]
    if len(spectral) != 1 or spectral[0]["status"] != "success":
        raise RuntimeError("APIHUB did not return a successful Spectral result")
    result = spectral[0]
    counts = Counter(issue["severity"] for issue in result["issues"])
    reported = documents[0].get("issuesSummary", {})
    for severity in LINT_SEVERITIES:
        if severity in reported and counts[severity] != reported[severity]:
            raise RuntimeError(f"APIHUB returned {counts[severity]} of {reported[severity]} {severity} findings")
    findings = apply_lint_exceptions(
        load_lint_exceptions(APPROVED_LINT_EXCEPTIONS_PATH), nifi_version, result,
        reports / LINT_PROPOSAL,
    )
    write_report(reports, "lint-issues", details)
    return findings


def breaking_operations(client, package, expected_count):
    operations = []
    for page in range((expected_count + PAGE_SIZE - 1) // PAGE_SIZE):
        query = urlencode({
            "severity": "breaking", "previousVersion": BASELINE, "previousVersionPackageId": package,
            "limit": PAGE_SIZE, "page": page,
        })
        result = client.get(f"/api/v4/packages/{package}/versions/{CANDIDATE}/rest/changes?{query}")
        operations.extend(result["operations"])
    return operations


def compare(client, package, reports, versions, exceptions_path):
    endpoint = f"/api/v2/packages/{package}/versions/{CANDIDATE}"
    query = urlencode({"previousVersion": BASELINE, "previousVersionPackageId": package})
    summary = client.get(f"{endpoint}/changes/summary?{query}")
    write_report(reports, "compatibility-summary", summary)
    if summary.get("noContent") or summary["hasErrors"]:
        raise RuntimeError("APIHUB did not produce a complete comparison")

    breaking_count = sum(api["changesSummary"]["breaking"] for api in summary["operationTypes"])
    if not breaking_count:
        load_exceptions(exceptions_path)
        return 0, 0

    affected_count = sum(api["numberOfImpactedOperations"]["breaking"] for api in summary["operationTypes"])
    operations = breaking_operations(client, package, affected_count)
    if not operations or len(operations) != affected_count:
        write_report(reports, "compatibility-issues", operations)
        raise RuntimeError(f"APIHUB returned {len(operations)} of {affected_count} affected operations")
    query += "&severity=breaking"
    incomplete_operations = 0
    for operation in operations:
        try:
            current = operation.get("currentOperation")
            if current is None:
                # APIHUB lists removed operations only on the previous side of the comparison.
                operation["issues"] = [{
                    "severity": "breaking", "action": "remove", "scope": "root",
                    "description": "The operation was removed.",
                }]
            else:
                operation_id = quote(current["operationId"], safe="")
                details = client.get(f"{endpoint}/rest/operations/{operation_id}/changes?{query}")
                operation["issues"] = [issue for issue in details["changes"] if issue["severity"] == "breaking"]
            expected = operation["changeSummary"]["breaking"]
            if len(operation["issues"]) != expected:
                raise RuntimeError(f"APIHUB returned {len(operation['issues'])} of {expected} breaking issue details")
        except Exception as error:
            operation["detailError"] = repr(error)
            incomplete_operations += 1

    proposal = reports / COMPATIBILITY_PROPOSAL
    if proposal.resolve() == exceptions_path.resolve():
        # A local rerun can use the earlier proposal as its approved exceptions file.
        proposal = reports / FOLLOWUP_COMPATIBILITY_PROPOSAL
        if proposal.resolve() == exceptions_path.resolve():
            raise ValueError("Both compatibility proposal paths point to the approved exceptions file")
    try:
        counts = apply_exceptions(
            load_exceptions(exceptions_path), versions, operations, proposal,
        )
    finally:
        write_report(reports, "compatibility-issues", operations)
    if incomplete_operations:
        raise RuntimeError(f"APIHUB returned incomplete breaking issue details for {incomplete_operations} operations")
    return counts


def spec_version(spec):
    version = json.loads(spec)["info"]["version"]
    if not isinstance(version, str) or not version.strip():
        raise ValueError("OpenAPI info.version must be a nonempty string")
    return version


def run(work_dir, reports, exceptions_path):
    current = (work_dir / "current.json").read_bytes()
    previous_file = work_dir / "previous.json"
    versions = {"previousNifiVersion": None, "currentNifiVersion": spec_version(current)}

    client = ApiHub()
    client.login()
    workspace = client.create_package(f"CI{uuid.uuid4().hex[:8]}", "workspace")
    package = client.create_package("NIFI", "package", workspace)
    messages = []
    failures = {}
    baseline = None
    if previous_file.exists():
        try:
            previous = previous_file.read_bytes()
            versions["previousNifiVersion"] = spec_version(previous)
            client.publish(package, BASELINE, previous)
            baseline = BASELINE
        except Exception as error:
            messages.append("Compatibility check could not prepare the baseline. See the check diagnostics.")
            failures["compatibility"] = repr(error)
    write_report(reports, "spec-versions", versions)
    client.publish(package, CANDIDATE, current, baseline)

    lint_findings = False
    try:
        active_lint, excepted_lint = lint(client, package, reports, versions["currentNifiVersion"])
        lint_findings = bool(active_lint or excepted_lint)
        errors, warnings = (active_lint[severity] for severity in LINT_SEVERITIES)
        messages.append(
            f"Unapproved lint findings (errors {errors}, warnings {warnings}). "
            f"Approved lint exceptions (errors {excepted_lint['error']}, warnings {excepted_lint['warning']})."
        )
        if active_lint:
            messages.append("Review the proposed lint exceptions YAML in the openapi-reports artifact.")
            failures["lint"] = f"Unapproved lint findings (errors {errors}, warnings {warnings})"
    except Exception as error:
        messages.append("Lint did not complete. See the check diagnostics.")
        failures["lint"] = repr(error)

    matched = 0
    unmatched = 0
    if baseline is not None:
        try:
            unmatched, matched = compare(client, package, reports, versions, exceptions_path)
            messages.append(f"Breaking API findings without an exception ({unmatched}). Approved exceptions ({matched}).")
            if unmatched:
                messages.append("Review the proposed exceptions YAML in the openapi-reports artifact.")
                failures["compatibility"] = f"Unapproved breaking finding count = {unmatched}"
        except Exception as error:
            messages.append("Compatibility check did not complete. See the check diagnostics.")
            failures["compatibility"] = repr(error)
    elif "compatibility" not in failures:
        messages.append("Compatibility check skipped because this commit adds the spec.")

    for line in messages:
        print(line)
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a", encoding="utf-8") as output:
            output.write("## OpenAPI checks\n\n" + "".join(f"- {line}\n" for line in messages))
    if output_path := os.environ.get("GITHUB_OUTPUT"):
        with Path(output_path).open("a", encoding="utf-8") as output:
            output.write(f"lint_findings={str(lint_findings).lower()}\n")
            output.write(f"breaking={str(matched + unmatched > 0).lower()}\n")
    if failures:
        write_report(reports, "check-failures", failures)
        for check, failure in failures.items():
            print(f"{check}: {failure!r}", file=sys.stderr)
    return int(bool(failures))


def main():
    parser = argparse.ArgumentParser(description="Validate the candidate OpenAPI spec with APIHUB.")
    parser.add_argument("work_dir", type=Path, help="Directory containing the current and previous specs")
    parser.add_argument(
        "--exceptions", type=Path,
        default=APPROVED_EXCEPTIONS_PATH,
        help="YAML file containing approved breaking changes",
    )
    args = parser.parse_args()
    work_dir = args.work_dir
    reports = work_dir / "reports"
    reports.mkdir(exist_ok=True)
    approved = args.exceptions.resolve()
    reserved_reports = {
        (reports / name).resolve() for name in GENERATED_REPORTS
        if name not in (COMPATIBILITY_PROPOSAL, FOLLOWUP_COMPATIBILITY_PROPOSAL)
    }
    if approved in reserved_reports:
        print(f"Choose an exceptions file outside the reserved report path {args.exceptions}", file=sys.stderr)
        return 1
    try:
        clear_generated_reports(reports, args.exceptions)
        return run(work_dir, reports, args.exceptions)
    except Exception as error:
        write_report(reports, "check-failures", {"setup": repr(error)})
        print(f"OpenAPI check setup failed: {error!r}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

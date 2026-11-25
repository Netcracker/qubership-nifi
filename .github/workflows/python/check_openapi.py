import argparse
import base64
from collections import Counter
from contextlib import suppress
from dataclasses import dataclass
import http.client
from http.cookies import SimpleCookie
import io
import json
import os
from pathlib import Path
import socket
import sys
from threading import Timer
import time
from urllib.parse import quote, urlencode
import uuid
import zipfile

from openapi_exceptions import APPROVED_EXCEPTIONS_PATH, apply_exceptions, load_exceptions, read_exception_header
from openapi_lint_exceptions import (
    APPROVED_LINT_EXCEPTIONS_PATH, LINT_SEVERITIES, apply_lint_exceptions, load_lint_exceptions, unused_lint_exceptions,
)


# The specs can declare the same NiFi version and still need distinct APIHUB drafts.
BASELINE = "0.0.1"
CANDIDATE = "0.0.2"
PUBLISH_TIMEOUT = 120
LINT_TIMEOUT = 120
# Give the publication event time to start linting before sending the fallback request.
LINT_START_GRACE = 20
CHECK_BUDGET = 240
# The workflow stops the script at 270 s. Reserve the last 10 s for writing reports.
COMPARISON_CUTOFF = 260
LINT_IN_PROGRESS = "inProgress"
PAGE_SIZE = 100
LINT_PROPOSAL = "proposed-openapi-lint-exceptions.yml"
COMPATIBILITY_PROPOSAL = "proposed-openapi-compatibility-exceptions.yml"
FOLLOWUP_COMPATIBILITY_PROPOSAL = "next-openapi-compatibility-exceptions.yml"
UNUSED_COMPATIBILITY_EXCEPTIONS = "unused-compatibility-exceptions"
GENERATED_REPORTS = (
    "lint-summary.json", "lint-issues.json", "unused-lint-exceptions.json",
    "compatibility-summary.json", "compatibility-issues.json", f"{UNUSED_COMPATIBILITY_EXCEPTIONS}.json",
    "spec-versions.json", "check-failures.json", LINT_PROPOSAL, COMPATIBILITY_PROPOSAL, FOLLOWUP_COMPATIBILITY_PROPOSAL,
)


@dataclass(frozen=True)
class Deadline:
    expires_at: float
    message: str

    def remaining(self):
        seconds = self.expires_at - time.monotonic()
        if seconds <= 0:
            raise TimeoutError(self.message)
        return seconds

    def wait_to_poll(self):
        time.sleep(min(2, self.remaining()))


class ApiHub:
    def __init__(self):
        self.backend_port = int(os.environ.get("APIHUB_PORT", "18090"))
        self.linter_port = int(os.environ.get("APIHUB_LINTER_PORT", "18091"))
        self.headers = {}

    def request(self, method, path, *, deadline, body=None, headers=None, expected=(200,), linter=False):
        port = self.linter_port if linter else self.backend_port
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=min(30, deadline.remaining()))
        try:
            connection.connect()
            connected_socket = connection.sock

            def interrupt_request():
                with suppress(OSError):
                    connected_socket.shutdown(socket.SHUT_RDWR)

            # The socket timeout limits idle I/O. Shutting down the socket at the deadline also stops a trickling body.
            watchdog = Timer(deadline.remaining(), interrupt_request)
            watchdog.daemon = True
            watchdog.start()
            try:
                connection.request(method, path, body, self.headers | (headers or {}))
                with connection.getresponse() as response:
                    data = response.read()
                    deadline.remaining()
                    if response.status not in expected:
                        raise RuntimeError(f"{method} {path}: HTTP {response.status}: {data[:500]!r}")
                    result = json.loads(data) if data else None
                    deadline.remaining()
                    return response.status, response.getheaders(), result
            finally:
                watchdog.cancel()
        except (OSError, http.client.HTTPException):
            deadline.remaining()
            raise
        finally:
            connection.close()

    def get(self, path, **kwargs):
        return self.request("GET", path, **kwargs)[2]

    def login(self, deadline):
        email = os.environ.get("APIHUB_ADMIN_EMAIL", "apihub-test-user@example.com")
        password = os.environ["APIHUB_ADMIN_PASSWORD"]
        credentials = base64.b64encode(f"{email}:{password}".encode()).decode()
        _, headers, _ = self.request(
            "POST", "/api/v3/auth/local", deadline=deadline, headers={"Authorization": f"Basic {credentials}"},
        )
        cookies = SimpleCookie()
        for name, value in headers:
            if name.lower() == "set-cookie":
                cookies.load(value)
        self.headers["Cookie"] = f"apihub-access-token={cookies['apihub-access-token'].value}"

    def create_package(self, alias, kind, deadline, parent=""):
        body = json.dumps({"alias": alias, "kind": kind, "name": alias, "parentId": parent})
        _, _, package = self.request(
            "POST", "/api/v2/packages", deadline=deadline, body=body,
            headers={"Content-Type": "application/json"}, expected=(200, 201),
        )
        return package["packageId"]

    def publish(self, package, version, spec, budget, previous=None):
        deadline = phase_deadline(PUBLISH_TIMEOUT, budget, f"publication {version}")
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
            "POST", endpoint, deadline=deadline, body=body,
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}, expected=(202,),
        )
        while True:
            status = self.get(f"{endpoint}/{build['publishId']}/status", deadline=deadline)
            if status["status"] == "complete":
                return
            if status["status"] == "error":
                raise RuntimeError(f"APIHUB publication {version} failed: {status!r}")
            deadline.wait_to_poll()


def phase_deadline(timeout, budget, phase):
    expires_at = time.monotonic() + timeout
    limit = f"its {timeout} s limit" if expires_at <= budget else f"the {CHECK_BUDGET} s check budget"
    return Deadline(min(expires_at, budget), f"APIHUB {phase} timed out at {limit}")


def write_report(reports, name, data):
    (reports / f"{name}.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def clear_generated_reports(reports, exceptions_path):
    approved_path = exceptions_path.resolve()
    for name in GENERATED_REPORTS:
        report = reports / name
        if report.resolve() != approved_path:
            report.unlink(missing_ok=True)


def lint(client, package, reports, nifi_version, budget):
    endpoint = f"/api/v2/packages/{package}/versions/{CANDIDATE}/validation"
    started = time.monotonic()
    deadline = phase_deadline(LINT_TIMEOUT, budget, "linting")
    validation_requested = False
    while True:
        status, _, summary = client.request(
            "GET", f"{endpoint}/summary", deadline=deadline, expected=(200, 404), linter=True,
        )
        if status == 200:
            write_report(reports, "lint-summary", summary)
            if summary["status"] != LINT_IN_PROGRESS:
                break
        elif not validation_requested and time.monotonic() - started >= LINT_START_GRACE:
            deadline.remaining()
            print("Requesting APIHUB lint after the validation summary returned HTTP 404.", flush=True)
            client.request(
                "POST", f"/api/v1/packages/{package}/versions/{CANDIDATE}/validation",
                deadline=deadline, expected=(200, 202), linter=True,
            )
            validation_requested = True
        deadline.wait_to_poll()
    if summary["status"] != "success":
        raise RuntimeError(f"APIHUB linting ended with status {summary['status']!r}")

    documents = summary.get("documents", [])
    details = []
    for document in documents:
        slug = quote(document["slug"], safe="")
        details.append(client.get(f"{endpoint}/documents/{slug}/details", deadline=deadline, linter=True))
        write_report(reports, "lint-issues", details)
    if len(details) != 1:
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
    approvals = load_lint_exceptions(APPROVED_LINT_EXCEPTIONS_PATH)
    findings = apply_lint_exceptions(
        approvals, nifi_version, result, reports / LINT_PROPOSAL, header=read_exception_header(APPROVED_LINT_EXCEPTIONS_PATH),
    )
    write_report(reports, "lint-issues", details)
    unused = unused_lint_exceptions(approvals, result)
    if unused:
        write_report(reports, "unused-lint-exceptions", unused)
    return (*findings, len(unused))


def breaking_operations(client, package, reports, expected_count, deadline):
    operations = []
    try:
        for page in range((expected_count + PAGE_SIZE - 1) // PAGE_SIZE):
            query = urlencode({
                "severity": "breaking", "previousVersion": BASELINE, "previousVersionPackageId": package,
                "limit": PAGE_SIZE, "page": page,
            })
            result = client.get(
                f"/api/v4/packages/{package}/versions/{CANDIDATE}/rest/changes?{query}", deadline=deadline,
            )
            page_operations = result["operations"]
            if not isinstance(page_operations, list) or not all(isinstance(item, dict) for item in page_operations):
                raise ValueError(f"Page {page} did not contain a valid operations list.")
            operations.extend(page_operations)
    except Exception as error:
        write_report(reports, "compatibility-issues", operations)
        raise RuntimeError(f"APIHUB returned {len(operations)} of {expected_count} affected operations. {error}") from error
    if len(operations) != expected_count:
        write_report(reports, "compatibility-issues", operations)
        raise RuntimeError(f"APIHUB returned {len(operations)} of {expected_count} affected operations")
    return operations


def read_breaking_details(client, endpoint, query, operations, deadline):
    incomplete_operations = 0
    details_timeout = None
    for operation in operations:
        try:
            deadline.remaining()
            current = operation.get("currentOperation")
            if current is None:
                # APIHUB lists removed operations only on the previous side of the comparison.
                operation["issues"] = [{
                    "severity": "breaking", "action": "remove", "scope": "root",
                    "description": "The operation was removed.",
                }]
            else:
                operation_id = quote(current["operationId"], safe="")
                details = client.get(f"{endpoint}/rest/operations/{operation_id}/changes?{query}", deadline=deadline)
                operation["issues"] = [issue for issue in details["changes"] if issue["severity"] == "breaking"]
                expected = operation["changeSummary"]["breaking"]
                if len(operation["issues"]) != expected:
                    raise RuntimeError(
                        f"APIHUB returned {len(operation['issues'])} of {expected} breaking issue details"
                    )
        except Exception as error:
            operation["detailError"] = repr(error)
            incomplete_operations += 1
            if time.monotonic() >= deadline.expires_at:
                details_timeout = error
                break

    if details_timeout is not None:
        raise TimeoutError(deadline.message) from details_timeout
    if incomplete_operations:
        raise RuntimeError(f"APIHUB returned incomplete breaking issue details for {incomplete_operations} operations")


def compare(client, package, reports, versions, exceptions_path, deadline):
    endpoint = f"/api/v2/packages/{package}/versions/{CANDIDATE}"
    query = urlencode({"previousVersion": BASELINE, "previousVersionPackageId": package})
    summary = client.get(f"{endpoint}/changes/summary?{query}", deadline=deadline)
    write_report(reports, "compatibility-summary", summary)
    if summary.get("noContent") or summary["hasErrors"]:
        raise RuntimeError("APIHUB did not produce a complete comparison")

    operations = []
    breaking_count = sum(api["changesSummary"]["breaking"] for api in summary["operationTypes"])
    if breaking_count:
        affected_count = sum(api["numberOfImpactedOperations"]["breaking"] for api in summary["operationTypes"])
        if affected_count == 0:
            raise RuntimeError(f"APIHUB reported {breaking_count} breaking changes affecting 0 operations")
        operations = breaking_operations(client, package, reports, affected_count, deadline)

    try:
        proposal = reports / COMPATIBILITY_PROPOSAL
        if operations:
            read_breaking_details(client, endpoint, query + "&severity=breaking", operations, deadline)
            if proposal.resolve() == exceptions_path.resolve():
                # A local rerun can use the earlier proposal as its approved exceptions file.
                proposal = reports / FOLLOWUP_COMPATIBILITY_PROPOSAL
                if proposal.resolve() == exceptions_path.resolve():
                    raise ValueError("Both compatibility proposal paths point to the approved exceptions file")
        unmatched, matched, unused = apply_exceptions(
            load_exceptions(exceptions_path), versions, operations, proposal, header=read_exception_header(exceptions_path),
        )
    finally:
        if operations:
            write_report(reports, "compatibility-issues", operations)
    if unused:
        write_report(reports, UNUSED_COMPATIBILITY_EXCEPTIONS, unused)
    return unmatched, matched, len(unused)


def spec_version(spec):
    version = json.loads(spec)["info"]["version"]
    if not isinstance(version, str) or not version.strip():
        raise ValueError("OpenAPI info.version must be a nonempty string")
    return version


def check_candidate(client, package, reports, versions, exceptions_path, baseline, budget, comparison_deadline,
                    messages, failures):
    try:
        active_lint, excepted_lint, unused_lint = lint(
            client, package, reports, versions["currentNifiVersion"], budget,
        )
        errors, warnings = (active_lint[severity] for severity in LINT_SEVERITIES)
        messages.append(
            f"Unapproved lint findings (errors {errors}, warnings {warnings}). "
            f"Approved lint exceptions (errors {excepted_lint['error']}, warnings {excepted_lint['warning']})."
        )
        if unused_lint:
            messages.append(f"Unused lint exceptions ({unused_lint}). See unused-lint-exceptions.json.")
        if active_lint:
            messages.append("Review the proposed lint exceptions YAML in the openapi-reports artifact.")
            failures["lint"] = f"Unapproved lint findings (errors {errors}, warnings {warnings})"
    except Exception as error:
        messages.append("Lint did not complete. See the check diagnostics.")
        failures["lint"] = repr(error)

    if baseline is not None:
        try:
            unmatched, matched, unused = compare(client, package, reports, versions, exceptions_path, comparison_deadline)
            messages.append(f"Breaking API findings without an exception ({unmatched}). Approved exceptions ({matched}).")
            if unused:
                messages.append(f"Unused compatibility exceptions ({unused}). See {UNUSED_COMPATIBILITY_EXCEPTIONS}.json.")
            if unmatched:
                messages.append("Review the proposed exceptions YAML in the openapi-reports artifact.")
                failures["compatibility"] = f"Unapproved breaking finding count = {unmatched}"
        except Exception as error:
            messages.append("Compatibility check did not complete. See the check diagnostics.")
            failures["compatibility"] = repr(error)
    elif "compatibility" not in failures:
        messages.append("Compatibility check skipped because this commit adds the spec.")


def write_summary(messages):
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a", encoding="utf-8") as output:
            output.write("## OpenAPI checks\n\n" + "".join(f"- {line}\n" for line in messages))


def run(work_dir, reports, exceptions_path):
    started = time.monotonic()
    budget = started + CHECK_BUDGET
    setup_deadline = Deadline(budget, f"APIHUB setup timed out at the {CHECK_BUDGET} s check budget")
    comparison_deadline = Deadline(
        started + COMPARISON_CUTOFF, f"APIHUB comparison timed out at the {COMPARISON_CUTOFF} s comparison cutoff",
    )
    current = (work_dir / "current.json").read_bytes()
    previous_file = work_dir / "previous.json"
    versions = {"previousNifiVersion": None, "currentNifiVersion": spec_version(current)}

    client = ApiHub()
    client.login(setup_deadline)
    workspace = client.create_package(f"CI{uuid.uuid4().hex[:8]}", "workspace", setup_deadline)
    package = client.create_package("NIFI", "package", setup_deadline, parent=workspace)
    messages = []
    failures = {}
    baseline = None
    if previous_file.exists():
        try:
            previous = previous_file.read_bytes()
            versions["previousNifiVersion"] = spec_version(previous)
            client.publish(package, BASELINE, previous, budget)
            baseline = BASELINE
        except Exception as error:
            messages.append("Compatibility check could not prepare the baseline. See the check diagnostics.")
            failures["compatibility"] = repr(error)
    write_report(reports, "spec-versions", versions)
    try:
        client.publish(package, CANDIDATE, current, budget, baseline)
    except Exception as error:
        messages.append("APIHUB could not publish the candidate spec. See the check diagnostics.")
        failures["publication"] = repr(error)
    else:
        check_candidate(client, package, reports, versions, exceptions_path, baseline, budget, comparison_deadline,
                        messages, failures)

    for line in messages:
        print(line)
    write_summary(messages)
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
        with suppress(OSError):
            write_summary(["OpenAPI check setup failed. See check-failures.json in the openapi-reports artifact."])
        return 1


if __name__ == "__main__":
    sys.exit(main())

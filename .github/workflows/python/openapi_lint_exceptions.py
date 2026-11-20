from collections import Counter
import json
from pathlib import Path

from openapi_exceptions import is_nonempty_string, load_exception_groups, write_exception_groups


APPROVED_LINT_EXCEPTIONS_PATH = Path(__file__).resolve().parents[3] / "docs/openapi/openapi-lint-exceptions.yml"
LINT_SEVERITIES = ("error", "warning")
SCOPE_FIELDS = ("nifiVersion", "linter", "ruleset")
LINTER_RULESET_FIELDS = ("linter", "ruleset")
ISSUE_FIELDS = ("severity", "code", "path", "message")
REASON_PLACEHOLDER = "Add the reason for accepting this lint finding."


def issue_signature(issue):
    return json.dumps({field: issue[field] for field in ISSUE_FIELDS}, sort_keys=True)


def validate_issue(issue, location):
    if (not isinstance(issue, dict) or issue.get("severity") not in LINT_SEVERITIES
            or not all(is_nonempty_string(issue.get(field)) for field in ("code", "message"))
            or not isinstance(issue.get("path"), list)
            or not all(isinstance(segment, str) for segment in issue["path"])):
        raise ValueError(f"{location} must specify error or warning severity, code, path strings, and message")


def load_lint_exceptions(path):
    groups = load_exception_groups(path)
    scopes = set()
    signatures = {}
    for index, group in enumerate(groups, start=1):
        if (not isinstance(group, dict) or set(group) != {*SCOPE_FIELDS, "issues"}
                or not all(is_nonempty_string(group[field]) for field in SCOPE_FIELDS)
                or not isinstance(group["issues"], list)):
            raise ValueError(f"{path} exception group {index} must specify NiFi version, linter, ruleset, and issues")
        scope = tuple(group[field] for field in SCOPE_FIELDS)
        if scope in scopes:
            raise ValueError(f"{path} repeats the lint exception scope {scope!r}")
        scopes.add(scope)

        ruleset_signatures = signatures.setdefault(tuple(group[field] for field in LINTER_RULESET_FIELDS), set())
        for entry, issue in enumerate(group["issues"], start=1):
            location = f"{path}, group {index}, issue {entry}"
            validate_issue(issue, location)
            if set(issue) != {*ISSUE_FIELDS, "reason"} or not is_nonempty_string(issue["reason"]):
                raise ValueError(f"{location} must contain only severity, code, path, message, and reason")
            if issue["reason"].strip() == REASON_PLACEHOLDER:
                raise ValueError(f"{location} has the proposed placeholder reason; replace it with the reason for the finding")
            signature = issue_signature(issue)
            if signature in ruleset_signatures:
                raise ValueError(f"{location} repeats a lint exception for the same linter and ruleset")
            ruleset_signatures.add(signature)
    return groups


def apply_lint_exceptions(groups, nifi_version, result, proposal_path):
    scope = {"nifiVersion": nifi_version, "linter": result["linter"], "ruleset": result["ruleset"]["name"]}
    if not all(is_nonempty_string(value) for value in scope.values()):
        raise ValueError("A lint exception scope requires the NiFi version, linter, and ruleset name")
    active_group = next((group for group in groups if all(group[field] == value for field, value in scope.items())), None)
    # An approval applies to every NiFi version; nifiVersion records the version it was approved for.
    approved = {
        issue_signature(issue): issue["reason"]
        for group in groups if all(group[field] == scope[field] for field in LINTER_RULESET_FIELDS)
        for issue in group["issues"]
    }
    proposals = {}
    unmatched = Counter()
    matched = Counter()
    for issue in result["issues"]:
        severity = issue["severity"]
        if severity not in LINT_SEVERITIES:
            continue
        # APIHUB omits the path for findings at the root of the document.
        issue.setdefault("path", [])
        validate_issue(issue, "APIHUB lint finding")
        signature = issue_signature(issue)
        if signature in approved:
            issue["exceptionReason"] = approved[signature]
            matched[severity] += 1
        else:
            unmatched[severity] += 1
            proposals.setdefault(signature, {
                **{field: issue[field] for field in ISSUE_FIELDS},
                "reason": REASON_PLACEHOLDER,
            })

    if proposals:
        suggested = [proposals[key] for key in sorted(proposals)]
        if active_group is None:
            proposed_groups = [*groups, {**scope, "issues": suggested}]
        else:
            proposed_groups = [
                {**group, "issues": [*group["issues"], *suggested]} if group is active_group else group
                for group in groups
            ]
        write_exception_groups(
            proposal_path, proposed_groups, "Review the proposed lint findings before updating the repository exceptions.",
        )
    return unmatched, matched

import json
from pathlib import Path

import yaml


APPROVED_EXCEPTIONS_PATH = Path(__file__).resolve().parents[3] / "docs/openapi/openapi-compatibility-exceptions.yml"
VERSION_FIELDS = ("previousNifiVersion", "currentNifiVersion")
OPERATION_FIELDS = ("method", "path")
REQUIRED_ISSUE_FIELDS = ("action", "scope")
PATH_FIELDS = ("previousDeclarationJsonPaths", "currentDeclarationJsonPaths")
HASH_FIELDS = ("previousValueHash", "currentValueHash")
KEY_FIELDS = ("previousKey", "currentKey")
ISSUE_FIELDS = REQUIRED_ISSUE_FIELDS + PATH_FIELDS + HASH_FIELDS + KEY_FIELDS
SELECTOR_FIELDS = OPERATION_FIELDS + ISSUE_FIELDS
REASON_PLACEHOLDER = "Add the reason for accepting this change."


class ExceptionLoader(yaml.SafeLoader):
    def construct_mapping(self, node, deep=False):
        mapping = super().construct_mapping(node, deep=deep)
        if len(mapping) != len(node.value):
            raise ValueError(f"Duplicate key in OpenAPI exceptions at line {node.start_mark.line + 1}")
        return mapping


class ExceptionDumper(yaml.SafeDumper):
    def increase_indent(self, flow=False, indentless=False):
        return super().increase_indent(flow, indentless=False)


def signature(change):
    selector = {field: change[field] for field in SELECTOR_FIELDS if field in change}
    selector["method"] = selector["method"].lower()
    for field in PATH_FIELDS:
        if field in selector:
            selector[field] = sorted(selector[field], key=json.dumps)
    return json.dumps(selector, sort_keys=True)


def is_nonempty_string(value):
    return isinstance(value, str) and bool(value.strip())


def load_exception_groups(path):
    loader = ExceptionLoader(path.read_text(encoding="utf-8"))
    try:
        config = loader.get_single_data()
    finally:
        loader.dispose()
    if not isinstance(config, dict) or set(config) != {"exceptions"} or not isinstance(config["exceptions"], list):
        raise ValueError(f"{path} must contain an exceptions list")
    return config["exceptions"]


def write_exception_groups(path, groups, comment):
    path.write_text(
        f"# {comment}\n" + yaml.dump({"exceptions": groups}, Dumper=ExceptionDumper,
                                     sort_keys=False, default_flow_style=None, width=120),
        encoding="utf-8",
        newline="\n",
    )


def load_exceptions(path):
    groups = load_exception_groups(path)
    version_pairs = set()
    for index, group in enumerate(groups, start=1):
        if (not isinstance(group, dict) or set(group) != {*VERSION_FIELDS, "changes"}
                or not all(is_nonempty_string(group[field]) for field in VERSION_FIELDS)
                or not isinstance(group["changes"], list)):
            raise ValueError(f"{path} exception group {index} must specify both NiFi versions and a changes list")
        version_pair = tuple(group[field] for field in VERSION_FIELDS)
        if version_pair in version_pairs:
            raise ValueError(f"{path} repeats the NiFi version pair {version_pair!r}")
        version_pairs.add(version_pair)

        changes = set()
        for entry, change in enumerate(group["changes"], start=1):
            location = f"{path}, group {index}, change {entry}"
            required = OPERATION_FIELDS + REQUIRED_ISSUE_FIELDS + ("reason",)
            if (not isinstance(change, dict) or set(change) - {*SELECTOR_FIELDS, "reason"}
                    or not all(is_nonempty_string(change.get(field)) for field in required)):
                raise ValueError(f"{location} must specify method, path, action, scope, and reason")
            if change["reason"].strip() == REASON_PLACEHOLDER:
                raise ValueError(f"{location} has the proposed placeholder reason; replace it with the reason for the change")
            for field in HASH_FIELDS:
                if field in change and not is_nonempty_string(change[field]):
                    raise ValueError(f"{location} must specify {field} as a nonempty string")
            for field in KEY_FIELDS:
                if field in change and not isinstance(change[field], str):
                    raise ValueError(f"{location} must specify {field} as a string")
            for field in PATH_FIELDS:
                if field not in change:
                    continue
                paths = change[field]
                if not isinstance(paths, list) or not all(
                    isinstance(parts, list) and all(type(part) in (str, int) for part in parts)
                    for parts in paths
                ):
                    raise ValueError(f"{location} must specify {field} as a list of path arrays")
            key = signature(change)
            if key in changes:
                raise ValueError(f"{location} repeats an exception in the same NiFi version pair")
            changes.add(key)
    return {"exceptions": groups}


def apply_exceptions(config, versions, operations, proposal_path):
    groups = config["exceptions"]
    active_group = next((group for group in groups if all(
        group[field] == versions[field] for field in VERSION_FIELDS
    )), None)
    approved = {signature(change): change["reason"] for change in (active_group or {}).get("changes", [])}
    proposals = {}
    unmatched = 0
    matched = 0
    for operation in operations:
        for issue in operation.get("issues", []):
            if issue["severity"] != "breaking":
                continue
            identity = operation.get("currentOperation") or operation["previousOperation"]
            selector = {field: identity[field] for field in OPERATION_FIELDS}
            selector.update({field: issue[field] for field in ISSUE_FIELDS if field in issue})
            key = signature(selector)
            if key in approved:
                issue["exceptionReason"] = approved[key]
                matched += 1
            else:
                unmatched += 1
                proposals.setdefault(key, {
                    **selector,
                    "reason": REASON_PLACEHOLDER,
                })

    if proposals:
        suggested = [proposals[key] for key in sorted(proposals)]
        if active_group is None:
            proposed_groups = [*groups, {**versions, "changes": suggested}]
        else:
            proposed_groups = [
                {**group, "changes": [*group["changes"], *suggested]} if group is active_group else group
                for group in groups
            ]
        write_exception_groups(
            proposal_path, proposed_groups, "Review the proposed changes before replacing the repository exceptions file.",
        )
    return unmatched, matched

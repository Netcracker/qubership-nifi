"""
detect_nifi_version.py  —  Detect the source NiFi version from exported flow JSONs.

Walks every *.json under exports_dir, searches all component objects for entries with
bundle.group == "org.apache.nifi", and prints each unique bundle.version found (one per line).

Usage:
    python detect_nifi_version.py <exports_dir>

Exit codes:
    0  one or more versions found and printed
    1  no org.apache.nifi bundles found
"""

import json
import sys
from pathlib import Path


def _collect_versions(obj, versions: set) -> None:
    if isinstance(obj, dict):
        bundle = obj.get("bundle")
        if isinstance(bundle, dict) and bundle.get("group") == "org.apache.nifi":
            version = bundle.get("version")
            if version:
                versions.add(version)
        for value in obj.values():
            _collect_versions(value, versions)
    elif isinstance(obj, list):
        for item in obj:
            _collect_versions(item, versions)


def detect_versions(exports_dir: str) -> set[str]:
    versions: set[str] = set()
    for json_file in Path(exports_dir).rglob("*.json"):
        try:
            with open(json_file, encoding="utf-8") as f:
                data = json.load(f)
            _collect_versions(data, versions)
        except (json.JSONDecodeError, OSError):
            continue
    return versions


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: detect_nifi_version.py <exports_dir>", file=sys.stderr)
        sys.exit(1)

    found = detect_versions(sys.argv[1])
    if not found:
        sys.exit(1)

    for v in sorted(found):
        print(v)

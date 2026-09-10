#!/usr/bin/env python3
"""Query a NiFi Knowledge Base and validate flow definition JSON against it.

The Knowledge Base is produced by qubership-nifi-kb-builder-tool and describes one
exact NiFi version: component types, bundle coordinates, property descriptors,
relationships and controller service APIs. Everything here is a lookup against
those files - nothing contacts a NiFi instance.

Python 3.8+, standard library only.
"""

import argparse
import difflib
import json
import os
import re
import sys
import uuid
from pathlib import Path

BUILDER_PREFIX = "qubership-nifi-kb-builder"

KIND_ALIASES = {
    "processor": "PROCESSOR",
    "processors": "PROCESSOR",
    "p": "PROCESSOR",
    "controller-service": "CONTROLLER_SERVICE",
    "controller-services": "CONTROLLER_SERVICE",
    "cs": "CONTROLLER_SERVICE",
    "service": "CONTROLLER_SERVICE",
    "reporting-task": "REPORTING_TASK",
    "reporting-tasks": "REPORTING_TASK",
    "rt": "REPORTING_TASK",
}

EL_TOKEN = re.compile(r"(?<!\$)\$\{")
PARAM_TOKEN = re.compile(r"(?<!#)#\{")
# The all-zero prefix that hand-written flows reach for when they number components.
PLACEHOLDER_ID = re.compile(r"^0{8}-0{4}-0{4}-0{4}-", re.IGNORECASE)


class KbError(Exception):
    """A problem the caller can act on, reported without a traceback."""


# ---------------------------------------------------------------------------
# Knowledge Base discovery and loading
# ---------------------------------------------------------------------------


def _is_kb_root(path):
    """Recognize a Knowledge Base by its layout.

    Several builders emit this format (the Java and Python tools stamp different
    builder names), so structure is the reliable signal, not the builder name.
    """
    manifest = path / "manifest.json"
    if not manifest.is_file() or not (path / "components" / "index.json").is_file():
        return False
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        return False
    return "schemaVersion" in data and "version" in data.get("nifi", {})


def _search_roots(start):
    """Directories worth scanning for a Knowledge Base, nearest first."""
    roots = [start]
    for parent in start.parents:
        if (parent / ".git").exists():
            roots.append(parent)
            break
    return roots


def discover(explicit=None, search_from=None):
    """Resolve the Knowledge Base: --kb, then NIFI_KB_PATH, then the repository."""
    if explicit:
        path = Path(explicit).expanduser().resolve()
        if not _is_kb_root(path):
            raise KbError(
                "'%s' is not a NiFi Knowledge Base. Expected manifest.json with a "
                "nifi.version, plus components/index.json." % path
            )
        return path

    env = os.environ.get("NIFI_KB_PATH")
    if env:
        path = Path(env).expanduser().resolve()
        if not _is_kb_root(path):
            raise KbError(
                "NIFI_KB_PATH points at '%s', which is not a NiFi Knowledge Base." % path
            )
        return path

    start = Path(search_from or Path.cwd()).resolve()
    found = []
    for root in _search_roots(start):
        for manifest in sorted(root.glob("*/manifest.json")) + sorted(
            root.glob("*/*/manifest.json")
        ):
            candidate = manifest.parent
            if _is_kb_root(candidate) and candidate not in found:
                found.append(candidate)
        if found:
            break

    if not found:
        raise KbError(
            "No NiFi Knowledge Base found. Set NIFI_KB_PATH, pass --kb <path>, or build "
            "one with %s-tool." % BUILDER_PREFIX
        )
    if len(found) > 1:
        listing = "\n".join(
            "  %s  (NiFi %s)" % (p, _manifest(p).get("nifi", {}).get("version", "?"))
            for p in found
        )
        raise KbError(
            "Several Knowledge Bases are present. Choose one with --kb or NIFI_KB_PATH:\n"
            + listing
        )
    return found[0]


def _manifest(kb):
    return json.loads((kb / "manifest.json").read_text(encoding="utf-8"))


class Kb:
    def __init__(self, root):
        self.root = root
        self.manifest = _manifest(root)
        self.index = json.loads(
            (root / "components" / "index.json").read_text(encoding="utf-8")
        )
        self.by_type = {}
        for entry in self.index:
            self.by_type.setdefault((entry["kind"], entry["type"]), entry)
        self._definitions = {}

    @property
    def nifi_version(self):
        return self.manifest.get("nifi", {}).get("version", "unknown")

    def definition(self, entry):
        """Load and cache component.json for an index entry."""
        key = entry["path"]
        if key not in self._definitions:
            path = self.root / "components" / key / "component.json"
            self._definitions[key] = json.loads(path.read_text(encoding="utf-8"))
        return self._definitions[key]

    def lookup(self, kind, type_name):
        return self.by_type.get((kind, type_name))

    def resolve(self, query, kind=None):
        """Find one component by simple name, fully qualified type, or substring."""
        wanted = KIND_ALIASES.get(kind.lower(), kind.upper()) if kind else None
        pool = [e for e in self.index if wanted is None or e["kind"] == wanted]
        q = query.lower()

        exact = [e for e in pool if simple_name(e["type"]).lower() == q]
        if len(exact) == 1:
            return exact[0]
        if len(exact) > 1:
            raise KbError(_ambiguous(query, exact))

        fqcn = [e for e in pool if e["type"].lower() == q]
        if len(fqcn) == 1:
            return fqcn[0]

        partial = [e for e in pool if q in simple_name(e["type"]).lower()]
        if len(partial) == 1:
            return partial[0]
        if len(partial) > 1:
            raise KbError(_ambiguous(query, partial))

        names = sorted({simple_name(e["type"]) for e in pool})
        hint = difflib.get_close_matches(query, names, n=5, cutoff=0.5)
        message = "No component named '%s' in this Knowledge Base." % query
        if hint:
            message += " Closest matches: " + ", ".join(hint)
        raise KbError(message)


def _ambiguous(query, matches):
    listing = "\n".join(
        "  %-40s %s" % (simple_name(e["type"]), e["type"]) for e in matches[:20]
    )
    more = "" if len(matches) <= 20 else "\n  ... and %d more" % (len(matches) - 20)
    return "'%s' matches %d components. Use the full type or --kind:\n%s%s" % (
        query,
        len(matches),
        listing,
        more,
    )


def zero_period(period):
    """Whether a scheduling period means 'run again immediately'.

    NiFi accepts '0 sec', '0 ms', or a bare '0'; all of them mean the task is rescheduled
    the instant it returns.
    """
    match = re.match(r"\s*([0-9]+(?:\.[0-9]+)?)", str(period or ""))
    return bool(match) and float(match.group(1)) == 0


def simple_name(type_name):
    return type_name.rsplit(".", 1)[-1]


def bundle_of(entry):
    return "%s:%s:%s" % (entry["group"], entry["artifact"], entry["version"])


def nifi_major(kb):
    """Major version of the Knowledge Base's NiFi, or 0 when the manifest does not say."""
    head = str(kb.nifi_version).split(".", 1)[0]
    return int(head) if head.isdigit() else 0


def identifier_problem(identifier):
    """Say why an identifier is not a proper UUID, or None when it is fine.

    NiFi never writes anything but a UUID here, and it keeps the one you write: the
    identifier survives import and comes back unchanged on the next export, so it is the
    component's portable identity rather than a label local to this file. Two flows built
    from the same numbered placeholders end up indistinguishable to anything that keys on
    it - registry version diffs, external service references, flow comparison tooling.
    """
    if not isinstance(identifier, str) or not identifier:
        return "is missing"
    try:
        uuid.UUID(identifier)
    except ValueError:
        return "is not a UUID"
    if PLACEHOLDER_ID.match(identifier):
        return "is a constructed placeholder"
    return None


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------


def cmd_locate(kb, args):
    counts = kb.manifest.get("counts", {})
    guides = kb.manifest.get("guides", {})
    print("Knowledge Base : %s" % kb.root)
    print("NiFi version   : %s" % kb.nifi_version)
    print("Built at       : %s" % kb.manifest.get("generatedAt", "?"))
    print(
        "Components     : %s processors, %s controller services, %s reporting tasks"
        % (
            counts.get("processors", "?"),
            counts.get("controllerServices", "?"),
            counts.get("reportingTasks", "?"),
        )
    )
    collected = [
        name
        for name, value in guides.items()
        if isinstance(value, dict) and value.get("status") == "collected"
    ]
    print("Guides         : %s" % (", ".join(collected) if collected else "none"))
    return 0


def cmd_find(kb, args):
    wanted = KIND_ALIASES.get(args.kind.lower(), args.kind.upper()) if args.kind else None
    query = args.query.lower() if args.query else ""
    tag = args.tag.lower() if args.tag else None

    hits = []
    for entry in kb.index:
        if wanted and entry["kind"] != wanted:
            continue
        tags = [t.lower() for t in entry.get("tags", [])]
        if tag and not any(tag in t for t in tags):
            continue
        if query:
            haystack = " ".join([entry["type"].lower(), " ".join(tags)])
            if query not in haystack:
                continue
        hits.append(entry)

    if not hits:
        print("No match. Try a shorter query, or --tag to search by tag.", file=sys.stderr)
        return 1

    for entry in hits[: args.limit]:
        flags = []
        if entry.get("deprecated"):
            flags.append("DEPRECATED")
        if entry.get("additionalDetailsAvailable"):
            flags.append("+details")
        print(
            "%-38s %-18s %s%s"
            % (
                simple_name(entry["type"]),
                entry["kind"].lower().replace("_", "-"),
                bundle_of(entry),
                (" [" + " ".join(flags) + "]") if flags else "",
            )
        )
        print("    %s" % entry["type"])
        if entry.get("tags"):
            print("    tags: %s" % ", ".join(entry["tags"]))
    if len(hits) > args.limit:
        print("... %d more (raise --limit or narrow the query)" % (len(hits) - args.limit))
    return 0


def _wrap(text, indent="  ", width=96):
    import textwrap

    out = []
    for paragraph in (text or "").strip().splitlines():
        if not paragraph.strip():
            continue
        out.extend(textwrap.wrap(paragraph.strip(), width=width, initial_indent=indent,
                                 subsequent_indent=indent))
    return "\n".join(out)


def cmd_show(kb, args):
    """Render a component summary from component.json.

    component.md holds the same facts already rendered, but reading the lossless
    JSON keeps this output independent of that file's formatting.
    """
    entry = kb.resolve(args.name, args.kind)
    base = kb.root / "components" / entry["path"]

    if args.details:
        path = base / "additionalDetails.md"
        if not path.is_file():
            raise KbError(
                "%s has no additionalDetails.md." % simple_name(entry["type"])
            )
        sys.stdout.write(path.read_text(encoding="utf-8"))
        return 0

    definition = kb.definition(entry)["definition"]
    _print_header(kb, entry, definition)

    print()
    print("DESCRIPTION")
    print(_wrap(definition.get("typeDescription", "")) or "  (none)")

    if entry["kind"] == "PROCESSOR":
        print()
        print("RELATIONSHIPS")
        for relationship in definition.get("supportedRelationships") or []:
            print("  %-14s %s" % (relationship["name"], relationship.get("description", "")))
        if definition.get("supportsDynamicRelationships"):
            print("  (supports dynamic relationships)")

    print()
    _print_properties(definition)

    for label, key in (("READS FLOWFILE ATTRIBUTES", "readsAttributes"),
                       ("WRITES FLOWFILE ATTRIBUTES", "writesAttributes")):
        items = definition.get(key) or []
        if items:
            print()
            print(label)
            for item in items:
                print("  %-32s %s" % (item.get("name", "?"), item.get("description", "")))

    use_cases = definition.get("useCases") or []
    if use_cases:
        print()
        print("USE CASES")
        for case in use_cases:
            print("  - %s" % case.get("description", ""))
            if case.get("configuration"):
                print(_wrap(case["configuration"], indent="      "))

    if entry.get("additionalDetailsAvailable"):
        print()
        print("Extended docs available: kb.py show %s --details" % simple_name(entry["type"]))
    return 0


def _print_header(kb, entry, definition):
    print("%s  (%s)" % (simple_name(entry["type"]), entry["kind"].lower().replace("_", "-")))
    print("type   : %s" % entry["type"])
    print("bundle : %s" % bundle_of(entry))
    if entry.get("tags"):
        print("tags   : %s" % ", ".join(entry["tags"]))
    if entry.get("deprecated"):
        print("status : DEPRECATED in NiFi %s" % kb.nifi_version)
    if definition.get("restricted") or kb.definition(entry).get("documentedType", {}).get("restricted"):
        print("status : RESTRICTED - requires an explicit policy grant")

    if entry["kind"] == "PROCESSOR":
        print("input  : %s" % definition.get("inputRequirement", "?"))
        strategies = definition.get("supportedSchedulingStrategies") or []
        default_strategy = definition.get("defaultSchedulingStrategy", "?")
        periods = definition.get("defaultSchedulingPeriodBySchedulingStrategy") or {}
        print(
            "sched  : %s (default), supported: %s, default period %s"
            % (default_strategy, ", ".join(strategies) or "?", periods.get(default_strategy, "?"))
        )
        if definition.get("supportsBatching"):
            print("         supports batching - runDurationMillis may be raised above 0")
        else:
            print("         no batching - runDurationMillis must stay 0")
        if definition.get("primaryNodeOnly"):
            print("         primary node only - set executionNode to PRIMARY")
        if definition.get("triggerSerially"):
            print("         triggers serially - concurrentlySchedulableTaskCount must be 1")

    apis = definition.get("providedApiImplementations") or []
    if apis:
        print("implements : %s" % ", ".join(a["type"] for a in apis))

    dynamic = "yes" if definition.get("supportsDynamicProperties") else "no"
    if definition.get("supportsSensitiveDynamicProperties"):
        dynamic += " (sensitive dynamic properties supported)"
    print("dynamic properties : %s" % dynamic)


def _value_column(descriptor):
    if descriptor.get("typeProvidedByValue"):
        return "<service: %s>" % simple_name(descriptor["typeProvidedByValue"]["type"])
    allowed = descriptor.get("allowableValues") or []
    if allowed:
        values = [a["value"] for a in allowed]
        rendered = " | ".join(values)
        if len(rendered) > 60:
            rendered = " | ".join(values[:4]) + " | ... (%d total)" % len(values)
        return rendered
    return ""


def _print_properties(definition):
    descriptors = definition.get("propertyDescriptors", {})
    if not descriptors:
        print("PROPERTIES: none")
        return
    print("PROPERTIES  (left column is the key to use in the flow JSON)")
    print("%-38s %-4s %-10s %-16s %s" % ("JSON KEY", "REQ", "EL", "DEFAULT", "ALLOWED / SERVICE API"))
    print("-" * 112)
    for key, descriptor in descriptors.items():
        required = "yes" if descriptor.get("required") else "no"
        el = descriptor.get("expressionLanguageScope", "NONE")
        default = descriptor.get("defaultValue")
        sensitive = "  [SENSITIVE]" if descriptor.get("sensitive") else ""
        print(
            "%-38s %-4s %-10s %-20s %s%s"
            % (
                key[:38],
                required,
                el[:10],
                ("" if default is None else str(default))[:20],
                _value_column(descriptor),
                sensitive,
            )
        )
        display = descriptor.get("displayName", key)
        if display != key:
            print('    UI shows "%s" - that is NOT the JSON key' % display)
        for dependency in descriptor.get("dependencies") or []:
            values = dependency.get("dependentValues") or []
            condition = (" in %s" % ", ".join(values)) if values else " is set"
            print("    only applies when '%s'%s" % (dependency.get("propertyName", "?"), condition))


def cmd_props(kb, args):
    entry = kb.resolve(args.name, args.kind)
    definition = kb.definition(entry)["definition"]
    _print_header(kb, entry, definition)
    print()
    _print_properties(definition)
    return 0


def cmd_services(kb, args):
    entry = kb.resolve(args.name, args.kind)
    definition = kb.definition(entry)["definition"]
    descriptors = definition.get("propertyDescriptors", {})

    wanted = {}
    for key, descriptor in descriptors.items():
        api = descriptor.get("typeProvidedByValue")
        if not api:
            continue
        if args.property and args.property.lower() not in (
            key.lower(),
            descriptor.get("displayName", "").lower(),
        ):
            continue
        wanted[key] = api["type"]

    if not wanted:
        target = "property '%s' of " % args.property if args.property else ""
        print("No controller service property on %s%s." % (target, simple_name(entry["type"])))
        return 0

    for key, api_type in wanted.items():
        print("%s  requires  %s" % (key, api_type))
        impls = [
            e
            for e in kb.index
            if e["kind"] == "CONTROLLER_SERVICE" and api_type in e.get("controllerServiceApis", [])
        ]
        if not impls:
            print("    no implementation in this Knowledge Base")
        for impl in impls:
            print("    %-38s %s" % (simple_name(impl["type"]), bundle_of(impl)))
        print()
    return 0


# ---------------------------------------------------------------------------
# Import-mandatory fields
# ---------------------------------------------------------------------------

# NiFi's importer deserializes the flow into Java objects and reads enums, integers and
# maps without null checks, so an omitted field arrives as null and throws before any
# validation runs - the API answers HTTP 500, not a useful message. These are the fields
# a NiFi 2.10 import was observed to dereference. Rather than memorize them, run
# `kb.py normalize`, which fills every field NiFi writes on export.
REQUIRED_ON_IMPORT = {
    "processors": ["propertyDescriptors", "bulletinLevel", "penaltyDuration", "yieldDuration",
                   "runDurationMillis", "concurrentlySchedulableTaskCount", "position",
                   "schedulingStrategy", "executionNode"],
    "controllerServices": ["propertyDescriptors"],
    "connections": ["labelIndex", "zIndex", "backPressureObjectThreshold",
                    "backPressureDataSizeThreshold", "flowFileExpiration",
                    "selectedRelationships"],
}


def _check_import_fields(report, where, component, collection):
    missing = [f for f in REQUIRED_ON_IMPORT.get(collection, []) if component.get(f) is None]
    if missing:
        report.error(
            where,
            "missing field(s) %s. NiFi's importer dereferences these directly, so the upload "
            "fails with HTTP 500 before validation runs. Run `kb.py normalize` to fill them."
            % ", ".join(missing),
        )


# ---------------------------------------------------------------------------
# Flow validation
# ---------------------------------------------------------------------------


class Report:
    def __init__(self):
        self.errors = []
        self.warnings = []

    def error(self, where, message):
        self.errors.append((where, message))

    def warn(self, where, message):
        self.warnings.append((where, message))

    def emit(self):
        for where, message in self.errors:
            print("ERROR  %s: %s" % (where, message))
        for where, message in self.warnings:
            print("WARN   %s: %s" % (where, message))
        print()
        print("%d error(s), %d warning(s)" % (len(self.errors), len(self.warnings)))
        return 1 if self.errors else 0


def root_group(doc):
    """Accept a versioned flow snapshot, a registry export, or a bare process group."""
    if isinstance(doc, dict):
        if "flowContents" in doc:
            return doc["flowContents"], doc
        for key in ("snapshot", "versionedFlowSnapshot"):
            if isinstance(doc.get(key), dict) and "flowContents" in doc[key]:
                return doc[key]["flowContents"], doc[key]
        if "processors" in doc or "processGroups" in doc:
            return doc, doc
    raise KbError(
        "This file is not a NiFi flow definition. Expected a 'flowContents' object or a "
        "process group with 'processors'."
    )


def walk_groups(group, path="flowContents"):
    yield path, group
    for child in group.get("processGroups") or []:
        name = child.get("name") or child.get("identifier", "?")
        for item in walk_groups(child, "%s.%s" % (path, name)):
            yield item


def _is_literal(value):
    return isinstance(value, str) and not EL_TOKEN.search(value) and not PARAM_TOKEN.search(value)


def _effective(descriptors, properties, key):
    """The value NiFi sees for a property: the configured one, else its default."""
    if key in properties and properties[key] is not None:
        return properties[key]
    return (descriptors.get(key) or {}).get("defaultValue")


def _is_active(descriptors, properties, key, _seen=None):
    """Whether a property applies, given the dependencies declared on it.

    NiFi hides a dependent property until its controlling property holds one of the
    listed values, so an absent 'required' property is only a problem when the
    property is actually active.
    """
    _seen = _seen or set()
    if key in _seen:
        return True
    _seen.add(key)
    descriptor = descriptors.get(key) or {}
    for dependency in descriptor.get("dependencies") or []:
        parent = dependency.get("propertyName")
        if parent is None:
            continue
        if not _is_active(descriptors, properties, parent, _seen):
            return False
        value = _effective(descriptors, properties, parent)
        expected = dependency.get("dependentValues") or []
        if expected:
            if value not in expected:
                return False
        elif value in (None, ""):
            return False
    return True


def _check_properties(kb, report, where, component, entry):
    definition = kb.definition(entry)["definition"]
    descriptors = definition.get("propertyDescriptors", {})
    by_display = {
        d.get("displayName"): name
        for name, d in descriptors.items()
        if d.get("displayName") and d.get("displayName") != name
    }
    properties = component.get("properties") or {}
    dynamic_ok = definition.get("supportsDynamicProperties", False)

    for key, value in properties.items():
        descriptor = descriptors.get(key)
        if descriptor is None:
            if key in by_display:
                report.error(
                    where,
                    "property key '%s' is the display name. NiFi matches on the descriptor "
                    "name - use '%s'." % (key, by_display[key]),
                )
                continue
            if dynamic_ok:
                continue
            hint = difflib.get_close_matches(key, list(descriptors), n=3, cutoff=0.6)
            suffix = (" Did you mean: %s?" % ", ".join(hint)) if hint else ""
            report.error(
                where,
                "'%s' is not a property of %s and the component takes no dynamic "
                "properties.%s" % (key, simple_name(entry["type"]), suffix),
            )
            continue

        if value is None:
            continue

        scope = descriptor.get("expressionLanguageScope", "NONE")
        if isinstance(value, str) and EL_TOKEN.search(value) and scope == "NONE":
            report.error(
                where,
                "property '%s' contains Expression Language but its EL scope is NONE. "
                "NiFi stores the text literally." % key,
            )

        allowed = descriptor.get("allowableValues") or []
        if allowed and _is_literal(value):
            values = [a["value"] for a in allowed]
            if value not in values:
                hint = difflib.get_close_matches(str(value), values, n=3, cutoff=0.5)
                suffix = (" Did you mean: %s?" % ", ".join(hint)) if hint else ""
                report.error(
                    where,
                    "property '%s' is set to '%s', which is not an allowable value (%s).%s"
                    % (key, value, ", ".join(values), suffix),
                )

        if descriptor.get("sensitive") and _is_literal(value) and value != "":
            report.warn(
                where,
                "property '%s' is sensitive and holds a literal value. Reference a "
                "parameter (#{...}) so the secret stays out of the flow file." % key,
            )

    for key, descriptor in descriptors.items():
        if not descriptor.get("required"):
            continue
        if descriptor.get("defaultValue") is not None:
            continue
        if properties.get(key) not in (None, ""):
            continue
        if not _is_active(descriptors, properties, key):
            continue
        report.error(
            where,
            "required property '%s' has no value and no default." % key,
        )


def _check_service_refs(kb, report, where, component, entry, services, external):
    definition = kb.definition(entry)["definition"]
    descriptors = definition.get("propertyDescriptors", {})
    for key, value in (component.get("properties") or {}).items():
        descriptor = descriptors.get(key)
        if not descriptor or not descriptor.get("typeProvidedByValue"):
            continue
        if value in (None, ""):
            continue
        api = descriptor["typeProvidedByValue"]["type"]
        if value in external:
            report.warn(
                where,
                "property '%s' points at external controller service '%s'. Its type is not "
                "in the flow file, so the %s API cannot be verified here."
                % (key, external[value], simple_name(api)),
            )
            continue
        target = services.get(value)
        if target is None:
            report.error(
                where,
                "property '%s' must hold the identifier of a controller service that "
                "implements %s, but '%s' matches no service in this flow and no entry in "
                "externalControllerServices." % (key, api, value),
            )
            continue
        target_entry = kb.lookup("CONTROLLER_SERVICE", target.get("type", ""))
        if target_entry is None:
            continue
        if api not in target_entry.get("controllerServiceApis", []):
            report.error(
                where,
                "property '%s' requires %s, but '%s' is a %s, which does not implement it."
                % (key, simple_name(api), target.get("name"), simple_name(target["type"])),
            )


def _dynamic_relationships(definition, component):
    """Whether this processor mints relationships from dynamic properties, and which ones.

    RouteOnAttribute is the familiar case: every user-defined property becomes a
    relationship of the same name, so the catalog lists only 'unmatched' and the flow
    itself declares the rest. Without this, routing a flow through RouteOnAttribute always
    looks like a reference to a relationship that does not exist.
    """
    supported = (definition.get("supportsDynamicRelationships")
                 or definition.get("supportsDynamicRelationship") or False)
    if not supported:
        return False, set()
    declared = {d.get("name") for d in (definition.get("propertyDescriptors") or {}).values()}
    declared |= set(definition.get("propertyDescriptors") or {})
    dynamic = {key for key in (component.get("properties") or {}) if key not in declared}
    return True, dynamic


def _relationship_key(name):
    return re.sub(r"[\s._-]+", "", (name or "").lower())


def _prose_relationships(kb, entry, definition):
    """Relationship names the documentation mentions but the catalog does not declare.

    Some processors build their relationship set from property values - ConsumeKafka only
    exposes 'parse failure' when Processing Strategy is RECORD - and the component
    definition NiFi publishes lists only the static set. The prose usually names the rest,
    so it is worth surfacing as a question rather than letting the flow look complete.
    """
    declared = {_relationship_key(r["name"]) for r in definition.get("supportedRelationships") or []}
    prose = definition.get("typeDescription", "") or ""
    details = kb.root / "components" / entry["path"] / "additionalDetails.md"
    if details.is_file():
        prose += "\n" + details.read_text(encoding="utf-8", errors="replace")
    found = {}
    for match in re.finditer(r"'([A-Za-z][A-Za-z0-9._ -]{1,30})'\s+relationship", prose):
        candidate = match.group(1)
        if _relationship_key(candidate) not in declared:
            found.setdefault(_relationship_key(candidate), candidate)
    return found


def _context_parameters(contexts, name, _seen=None):
    """Parameter names visible in a context, following inheritance."""
    _seen = _seen or set()
    if name in _seen or name not in contexts:
        return set()
    _seen.add(name)
    context = contexts[name]
    names = set()
    for parameter in context.get("parameters") or []:
        # NiFi writes either {"name": ...} or {"parameter": {"name": ...}}.
        entry = parameter.get("parameter", parameter)
        if entry.get("name"):
            names.add(entry["name"])
    for inherited in context.get("inheritedParameterContexts") or []:
        parent = inherited if isinstance(inherited, str) else inherited.get("name")
        if parent:
            names |= _context_parameters(contexts, parent, _seen)
    return names


def _check_parameters(report, root, envelope):
    """A #{...} reference needs a context bound to the group and declaring the parameter."""
    contexts = envelope.get("parameterContexts") or {}

    def walk(group, path, inherited):
        bound = group.get("parameterContextName") or inherited
        references = {}
        for component in (group.get("processors") or []) + (group.get("controllerServices") or []):
            for key, value in (component.get("properties") or {}).items():
                if isinstance(value, str):
                    for name in re.findall(r"(?<!#)#\{([^}]+)\}", value):
                        references.setdefault(name, "%s[%s].%s"
                                              % (path, component.get("name", "?"), key))
        if references:
            if not bound:
                report.error(
                    path,
                    "properties reference parameter(s) %s but no parameterContextName is set on "
                    "this group or an ancestor. NiFi reports 'Property references one or more "
                    "Parameters but no Parameter Context is currently set on the Process Group'."
                    % ", ".join(sorted(references)),
                )
            elif bound not in contexts:
                report.error(
                    path,
                    "parameterContextName is '%s', which parameterContexts does not define."
                    % bound,
                )
            else:
                declared = _context_parameters(contexts, bound)
                for name in sorted(references):
                    if name not in declared:
                        report.error(
                            references[name],
                            "references parameter '%s', which context '%s' does not declare."
                            % (name, bound),
                        )
        for child in group.get("processGroups") or []:
            walk(child, "%s.%s" % (path, child.get("name") or child.get("identifier", "?")), bound)

    walk(root, "flowContents", None)


def cmd_validate(kb, args):
    path = Path(args.flow)
    if not path.is_file():
        raise KbError("No such file: %s" % path)
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as exc:
        raise KbError("%s is not valid JSON: %s" % (path, exc))

    root, envelope = root_group(doc)
    report = Report()

    if "latest" in envelope:
        report.warn(
            "flow",
            "top-level 'latest' is metadata NiFi adds when you download a flow. A NiFi "
            "Registry snapshot never carries it and no importer reads it, so leaving it "
            "out is what makes one file loadable by both. `kb.py normalize` removes it.",
        )

    # Every other component is checked as part of its parent's collections; the root
    # group is the one identifier nothing else lists.
    root_problem = identifier_problem(root.get("identifier"))
    if root_problem:
        report.warn(
            "flowContents",
            "identifier of the root process group %s (%r). Generate one with `kb.py ids`."
            % (root_problem, root.get("identifier")),
        )

    services = {}
    external = {
        identifier: value.get("name", identifier)
        for identifier, value in (envelope.get("externalControllerServices") or {}).items()
    }
    parameters = set()
    for context in (envelope.get("parameterContexts") or {}).values():
        for parameter in context.get("parameters") or []:
            name = parameter.get("parameter", parameter).get("name")
            if name:
                parameters.add(name)

    identifiers = {}
    outgoing = {}
    incoming = set()
    connections = []
    # Groups whose contents live in a registry rather than in this file.
    external_groups = {}
    # Collected rather than reported per group: a nested flow has one per group, and the
    # fix is the same single command for all of them.
    empty_variables = []
    # Collected rather than reported one by one: run duration is a tuning decision for the
    # flow as a whole, not a defect in any single processor.
    unbatched = []

    for group_path, group in walk_groups(root):
        if "variables" in group and nifi_major(kb) != 1:
            if group["variables"]:
                report.error(
                    group_path,
                    "'variables' holds %d entry/entries, but the Variable Registry was "
                    "removed in NiFi 2.0. NiFi %s ignores the map, so every ${...} that "
                    "reads one of these resolves to nothing at runtime. Move them to a "
                    "parameter context and reference them as #{name}."
                    % (len(group["variables"]), kb.nifi_version),
                )
            else:
                empty_variables.append(group_path)
        if group.get("versionedFlowCoordinates"):
            external_groups[group.get("identifier")] = group.get("name") or "?"
        for service in group.get("controllerServices") or []:
            services[service.get("identifier")] = service
        for collection in (
            "processors",
            "controllerServices",
            "inputPorts",
            "outputPorts",
            "funnels",
            "labels",
            "processGroups",
            "remoteProcessGroups",
            "connections",
        ):
            for item in group.get(collection) or []:
                identifier = item.get("identifier")
                if identifier in identifiers:
                    report.error(
                        group_path,
                        "identifier %s is used by both '%s' and '%s'. Identifiers must be "
                        "unique across the flow."
                        % (identifier, identifiers[identifier], item.get("name", collection)),
                    )
                identifiers[identifier] = item.get("name", collection)
                problem = identifier_problem(identifier)
                if problem:
                    report.warn(
                        group_path,
                        "identifier of '%s' %s (%r). NiFi mints a random UUID for every "
                        "component and keeps the identifier you write through import and "
                        "re-export, so this becomes the component's permanent portable id. "
                        "Generate one per component with `kb.py ids`."
                        % (item.get("name", collection), problem, identifier),
                    )
        for connection in group.get("connections") or []:
            connections.append((group_path, connection))
            source = (connection.get("source") or {}).get("id")
            destination = (connection.get("destination") or {}).get("id")
            outgoing.setdefault(source, set()).update(connection.get("selectedRelationships") or [])
            if destination:
                incoming.add(destination)

    bundle_mismatch = []

    for group_path, group in walk_groups(root):
        for service in group.get("controllerServices") or []:
            where = "%s.controllerServices[%s]" % (group_path, service.get("name", "?"))
            _check_import_fields(report, where, service, "controllerServices")
            entry = kb.lookup("CONTROLLER_SERVICE", service.get("type", ""))
            if entry is None:
                report.error(
                    where, "controller service %s" % _unknown_type(kb, "CONTROLLER_SERVICE", service.get("type"))
                )
                continue
            _check_bundle(report, where, service, entry, bundle_mismatch)
            _check_properties(kb, report, where, service, entry)
            _check_service_refs(kb, report, where, service, entry, services, external)
            if entry.get("deprecated"):
                report.warn(where, "%s is deprecated in NiFi %s." % (simple_name(entry["type"]), kb.nifi_version))

        for processor in group.get("processors") or []:
            name = processor.get("name", "?")
            where = "%s.processors[%s]" % (group_path, name)
            _check_import_fields(report, where, processor, "processors")
            entry = kb.lookup("PROCESSOR", processor.get("type", ""))
            if entry is None:
                report.error(where, "processor %s" % _unknown_type(kb, "PROCESSOR", processor.get("type")))
                continue

            _check_bundle(report, where, processor, entry, bundle_mismatch)
            _check_properties(kb, report, where, processor, entry)
            _check_service_refs(kb, report, where, processor, entry, services, external)

            definition = kb.definition(entry)["definition"]
            if entry.get("deprecated"):
                report.warn(where, "%s is deprecated in NiFi %s." % (simple_name(entry["type"]), kb.nifi_version))

            supported = {r["name"] for r in definition.get("supportedRelationships", [])}
            auto = set(processor.get("autoTerminatedRelationships") or [])
            connected = outgoing.get(processor.get("identifier"), set())
            dynamic_relationships, dynamic_properties = _dynamic_relationships(
                definition, processor)

            prose_relationships = _prose_relationships(kb, entry, definition)
            for unknown in sorted((auto | connected) - supported):
                if unknown in dynamic_properties:
                    # RouteOnAttribute and its kind create one relationship per dynamic
                    # property, so this name is declared by the flow itself.
                    continue
                if dynamic_relationships:
                    report.warn(
                        where,
                        "relationship '%s' is not in the catalog, and no dynamic property "
                        "of this processor carries that name either. %s does build "
                        "relationships from its dynamic properties, so check the two agree."
                        % (unknown, simple_name(entry["type"])),
                    )
                    continue
                if _relationship_key(unknown) in prose_relationships:
                    # The catalog lists only the relationships a processor declares
                    # unconditionally, so handling one it adds for a particular property
                    # value looks undeclared here and is accepted by NiFi.
                    report.warn(
                        where,
                        "relationship '%s' is not in the catalog for %s, but the documentation "
                        "names it. Processors can add relationships for particular property "
                        "values, so this is probably right. Confirm with verify_live.py."
                        % (unknown, simple_name(entry["type"])),
                    )
                    continue
                report.error(
                    where,
                    "relationship '%s' does not exist on %s. Available: %s."
                    % (unknown, simple_name(entry["type"]), ", ".join(sorted(supported)) or "none"),
                )
            for dangling in sorted(supported - auto - connected):
                report.error(
                    where,
                    "relationship '%s' is neither connected nor auto-terminated. NiFi refuses "
                    "to start a processor with an unhandled relationship." % dangling,
                )
            for both in sorted(auto & connected):
                report.warn(
                    where,
                    "relationship '%s' is auto-terminated and also connected. NiFi rejects that "
                    "combination." % both,
                )

            handled = {_relationship_key(r) for r in auto | connected}
            for key, label in sorted(prose_relationships.items()):
                if key not in handled:
                    report.warn(
                        where,
                        "the documentation for %s mentions a '%s' relationship that the catalog "
                        "does not declare. Some processors add relationships for particular "
                        "property values, and NiFi refuses to start with one unhandled. Confirm "
                        "with verify_live.py."
                        % (simple_name(entry["type"]), label),
                    )

            requirement = definition.get("inputRequirement")
            has_input = processor.get("identifier") in incoming
            if requirement == "INPUT_REQUIRED" and not has_input:
                report.error(where, "%s requires an incoming connection." % simple_name(entry["type"]))
            if requirement == "INPUT_FORBIDDEN" and has_input:
                report.error(
                    where,
                    "%s forbids incoming connections, but one targets it." % simple_name(entry["type"]),
                )

            strategy = processor.get("schedulingStrategy")
            supported_strategies = definition.get("supportedSchedulingStrategies") or []
            if strategy and supported_strategies and strategy not in supported_strategies:
                report.error(
                    where,
                    "schedulingStrategy '%s' is not supported. Available: %s."
                    % (strategy, ", ".join(supported_strategies)),
                )
            if (definition.get("inputRequirement") == "INPUT_FORBIDDEN"
                    and processor.get("schedulingStrategy", "TIMER_DRIVEN") == "TIMER_DRIVEN"
                    and zero_period(processor.get("schedulingPeriod"))):
                default_period = (definition.get("defaultSchedulingPeriodBySchedulingStrategy")
                                  or {}).get("TIMER_DRIVEN")
                # Some sources ship with a zero default of their own, so citing it as the
                # remedy would recommend the very setting being complained about.
                remedy = ("NiFi's own default for %s is %r"
                          % (simple_name(entry["type"]), default_period)
                          if default_period and not zero_period(default_period)
                          else "%s ships with a zero default of its own, so this one is a "
                               "judgement call" % simple_name(entry["type"]))
                report.warn(
                    where,
                    "source processor scheduled every %r. With no incoming connection there "
                    "is nothing to throttle it, so the task is rescheduled the moment it "
                    "returns and holds a thread spinning on empty polls. %s; 100 millis is a "
                    "common floor for a source that must react quickly, and seconds are "
                    "normal for a directory poll."
                    % (processor.get("schedulingPeriod"), remedy),
                )
            if definition.get("supportsBatching") and not processor.get("runDurationMillis"):
                unbatched.append(processor.get("name") or simple_name(entry["type"]))
            if definition.get("primaryNodeOnly") and processor.get("executionNode") != "PRIMARY":
                report.warn(
                    where,
                    "%s is primary-node-only; set executionNode to PRIMARY."
                    % simple_name(entry["type"]),
                )

    for group_path, connection in connections:
        where = "%s.connections[%s]" % (group_path, connection.get("name") or "unnamed")
        _check_import_fields(report, where, connection, "connections")
        for role in ("source", "destination"):
            endpoint = connection.get(role) or {}
            if not endpoint.get("id") or endpoint["id"] in identifiers:
                continue
            # A child group under version control is exported as a reference, not inlined,
            # so its ports are absent from this file by design. A connection into one is
            # correct even though nothing here declares the port.
            owner = external_groups.get(endpoint.get("groupId"))
            if owner:
                continue
            report.error(
                where,
                "%s id %s matches no component in this flow." % (role, endpoint["id"]),
            )

    if unbatched:
        report.warn(
            "flow",
            "%d processor(s) that support batching run with runDurationMillis 0: %s%s. "
            "Raising it lets NiFi handle several FlowFiles in one session instead of paying "
            "the framework cost per file; 25 ms is the usual choice. Leave it at 0 where "
            "latency per FlowFile matters more than throughput."
            % (len(unbatched), ", ".join(sorted(unbatched)[:5]),
               "" if len(unbatched) <= 5 else " and %d more" % (len(unbatched) - 5)),
        )

    if empty_variables:
        report.warn(
            "flow",
            "%d process group(s) carry an empty 'variables' map. NiFi %s neither writes nor "
            "reads it; it only makes the file look like a 1.x export. `kb.py normalize` "
            "drops them." % (len(empty_variables), kb.nifi_version),
        )

    if len(bundle_mismatch) > 3:
        versions = sorted({v for _, v in bundle_mismatch})
        report.errors = [
            e
            for e in report.errors
            if "bundle version" not in e[1]
        ]
        report.error(
            "flow",
            "%d components declare bundle version(s) %s, but this Knowledge Base describes "
            "NiFi %s. Validate against a Knowledge Base built from the target NiFi, or update "
            "the bundle coordinates." % (len(bundle_mismatch), ", ".join(versions), kb.nifi_version),
        )

    _check_parameters(report, root, envelope)

    print("Validating %s against NiFi %s (%s)" % (path, kb.nifi_version, kb.root))
    print()
    return report.emit()


def descriptor_stub(kb, entry, key):
    """The propertyDescriptors entry NiFi writes for one property."""
    descriptors = {}
    if entry is not None:
        descriptors = kb.definition(entry)["definition"].get("propertyDescriptors", {}) or {}
    descriptor = descriptors.get(key, {})
    stub = {
        "name": key,
        "displayName": descriptor.get("displayName", key),
        "identifiesControllerService": bool(descriptor.get("typeProvidedByValue")),
        "sensitive": bool(descriptor.get("sensitive", False)),
    }
    # NiFi 2.x records whether a property is user-defined rather than part of the component;
    # a 1.x export has no such field, so writing one there would invent a key that version
    # never produces. A key the catalog does not list is dynamic by definition - `validate`
    # separately rejects one on a component that takes no dynamic properties.
    if nifi_major(kb) != 1:
        stub["dynamic"] = entry is not None and key not in descriptors
    return stub


def normalize_processor(kb, processor, index):
    entry = kb.lookup("PROCESSOR", processor.get("type", ""))
    definition = kb.definition(entry)["definition"] if entry else {}
    strategy = processor.setdefault(
        "schedulingStrategy", definition.get("defaultSchedulingStrategy") or "TIMER_DRIVEN")
    periods = definition.get("defaultSchedulingPeriodBySchedulingStrategy") or {}
    tasks = definition.get("defaultConcurrentTasksBySchedulingStrategy") or {}

    processor.setdefault("position", {"x": 0.0, "y": float(index * 200)})
    processor.setdefault("comments", "")
    processor.setdefault("style", {})
    processor.setdefault("schedulingPeriod", periods.get(strategy, "0 sec"))
    processor.setdefault("executionNode", "PRIMARY" if definition.get("primaryNodeOnly") else "ALL")
    processor.setdefault("penaltyDuration", definition.get("defaultPenaltyDuration") or "30 sec")
    processor.setdefault("yieldDuration", definition.get("defaultYieldDuration") or "1 sec")
    processor.setdefault("bulletinLevel", definition.get("defaultBulletinLevel") or "WARN")
    processor.setdefault("runDurationMillis", 0)
    processor.setdefault("concurrentlySchedulableTaskCount", tasks.get(strategy, 1))
    processor.setdefault("autoTerminatedRelationships", [])
    processor.setdefault("scheduledState", "ENABLED")
    processor.setdefault("retryCount", 10)
    processor.setdefault("retriedRelationships", [])
    processor.setdefault("backoffMechanism", "PENALIZE_FLOWFILE")
    processor.setdefault("maxBackoffPeriod", "10 mins")
    processor.setdefault("componentType", "PROCESSOR")
    processor.setdefault("properties", {})
    if not processor.get("propertyDescriptors"):
        processor["propertyDescriptors"] = {
            key: descriptor_stub(kb, entry, key) for key in processor["properties"]
        }
    if entry and not processor.get("bundle"):
        processor["bundle"] = {"group": entry["group"], "artifact": entry["artifact"],
                               "version": entry["version"]}


def normalize_service(kb, service, index):
    entry = kb.lookup("CONTROLLER_SERVICE", service.get("type", ""))
    definition = kb.definition(entry)["definition"] if entry else {}
    service.setdefault("comments", "")
    service.setdefault("bulletinLevel", "WARN")
    service.setdefault("scheduledState", "ENABLED")
    service.setdefault("componentType", "CONTROLLER_SERVICE")
    service.setdefault("properties", {})
    service.setdefault(
        "controllerServiceApis",
        [{"type": a["type"], "bundle": {"group": a["group"], "artifact": a["artifact"],
                                        "version": a["version"]}}
         for a in definition.get("providedApiImplementations") or []],
    )
    if not service.get("propertyDescriptors"):
        service["propertyDescriptors"] = {
            key: descriptor_stub(kb, entry, key) for key in service["properties"]
        }
    if entry and not service.get("bundle"):
        service["bundle"] = {"group": entry["group"], "artifact": entry["artifact"],
                             "version": entry["version"]}


def normalize_connection(connection):
    connection.setdefault("name", "")
    connection.setdefault("labelIndex", 0)
    connection.setdefault("zIndex", 0)
    connection.setdefault("selectedRelationships", [])
    connection.setdefault("backPressureObjectThreshold", 10000)
    connection.setdefault("backPressureDataSizeThreshold", "1 GB")
    connection.setdefault("flowFileExpiration", "0 sec")
    connection.setdefault("prioritizers", [])
    connection.setdefault("bends", [])
    connection.setdefault("loadBalanceStrategy", "DO_NOT_LOAD_BALANCE")
    connection.setdefault("partitioningAttribute", "")
    connection.setdefault("loadBalanceCompression", "DO_NOT_COMPRESS")
    connection.setdefault("componentType", "CONNECTION")


def normalize_group(kb, group):
    group.setdefault("name", "Process Group")
    group.setdefault("comments", "")
    group.setdefault("position", {"x": 0.0, "y": 0.0})
    # The Variable Registry was removed in NiFi 2.0, and a 2.x export has no `variables`
    # key at all. Keep writing it for a 1.x Knowledge Base, and drop an empty one for 2.x
    # rather than leaving a field that suggests variables still work. A non-empty map is
    # left alone for `validate` to report, because deleting it would lose the values.
    if nifi_major(kb) == 1:
        group.setdefault("variables", {})
    elif group.get("variables") == {}:
        group.pop("variables")
    group.setdefault("defaultFlowFileExpiration", "0 sec")
    group.setdefault("defaultBackPressureObjectThreshold", 10000)
    group.setdefault("defaultBackPressureDataSizeThreshold", "1 GB")
    group.setdefault("flowFileConcurrency", "UNBOUNDED")
    group.setdefault("flowFileOutboundPolicy", "STREAM_WHEN_AVAILABLE")
    group.setdefault("componentType", "PROCESS_GROUP")
    for collection in ("processGroups", "remoteProcessGroups", "processors", "inputPorts",
                       "outputPorts", "connections", "labels", "funnels", "controllerServices"):
        group.setdefault(collection, [])

    for index, processor in enumerate(group["processors"]):
        processor.setdefault("groupIdentifier", group.get("identifier"))
        normalize_processor(kb, processor, index)
    for index, service in enumerate(group["controllerServices"]):
        service.setdefault("groupIdentifier", group.get("identifier"))
        normalize_service(kb, service, index)
    for connection in group["connections"]:
        connection.setdefault("groupIdentifier", group.get("identifier"))
        normalize_connection(connection)
    for port_collection, kind in (("inputPorts", "INPUT_PORT"), ("outputPorts", "OUTPUT_PORT")):
        for port in group[port_collection]:
            port.setdefault("groupIdentifier", group.get("identifier"))
            port.setdefault("position", {"x": 0.0, "y": 0.0})
            port.setdefault("type", kind)
            port.setdefault("componentType", kind)
            port.setdefault("concurrentlySchedulableTaskCount", 1)
            port.setdefault("scheduledState", "ENABLED")
            port.setdefault("allowRemoteAccess", False)
            port.setdefault("portFunction", "STANDARD")
    for child in group["processGroups"]:
        normalize_group(kb, child)


def cmd_ids(args):
    """Mint identifiers for a flow being written by hand.

    Needs no Knowledge Base: it is here so that writing a flow does not tempt anyone into
    numbering components by hand.
    """
    for _ in range(max(1, args.count)):
        print(uuid.uuid4())
    return 0


def cmd_normalize(kb, args):
    path = Path(args.flow)
    if not path.is_file():
        raise KbError("No such file: %s" % path)
    doc = json.loads(path.read_text(encoding="utf-8"))
    root, envelope = root_group(doc)

    envelope.setdefault("externalControllerServices", {})
    envelope.setdefault("parameterContexts", {})
    envelope.setdefault("parameterProviders", {})
    envelope.setdefault("flowEncodingVersion", "1.0")
    # `latest` is metadata NiFi adds on download; a Registry snapshot never carries it and
    # no importer reads it. Removing it is what lets one file load into both.
    envelope.pop("latest", None)
    normalize_group(kb, root)

    body = json.dumps(doc, indent=2) + "\n"
    target = Path(args.output) if args.output else path
    target.write_text(body, encoding="utf-8")
    print("Normalized %s -> %s" % (path, target))
    print("Filled the fields NiFi writes on export, using each component's own defaults from the")
    print("Knowledge Base, and removed the fields that belong to a NiFi download rather than to")
    print("the flow. Run `kb.py validate` next.")
    return 0


def _unknown_type(kb, kind, type_name):
    """Explain a type miss, separating a moved class from a genuinely absent one."""
    simple = simple_name(type_name or "")
    same_name = [e for e in kb.index if e["kind"] == kind and simple_name(e["type"]) == simple]
    if same_name:
        moved = same_name[0]
        return (
            "type '%s' does not exist in NiFi %s, but %s does under a different package. "
            "Use type '%s' with bundle %s."
            % (type_name, kb.nifi_version, simple, moved["type"], bundle_of(moved))
        )
    candidates = difflib.get_close_matches(
        simple,
        [simple_name(e["type"]) for e in kb.index if e["kind"] == kind],
        n=3,
        cutoff=0.6,
    )
    suffix = (" Closest names in the catalog: %s." % ", ".join(candidates)) if candidates else ""
    return "type '%s' does not exist in NiFi %s.%s" % (type_name, kb.nifi_version, suffix)


def _check_bundle(report, where, component, entry, mismatch):
    bundle = component.get("bundle") or {}
    if not bundle:
        report.error(where, "no bundle declared. Use %s." % bundle_of(entry))
        return
    if bundle.get("group") != entry["group"] or bundle.get("artifact") != entry["artifact"]:
        report.error(
            where,
            "bundle is %s:%s but %s ships in %s:%s."
            % (
                bundle.get("group"),
                bundle.get("artifact"),
                simple_name(entry["type"]),
                entry["group"],
                entry["artifact"],
            ),
        )
        return
    if bundle.get("version") != entry["version"]:
        mismatch.append((where, str(bundle.get("version"))))
        report.error(
            where,
            "bundle version is %s but this Knowledge Base has %s."
            % (bundle.get("version"), entry["version"]),
        )


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def build_parser():
    parser = argparse.ArgumentParser(
        prog="kb.py", description="Query a NiFi Knowledge Base and validate flow JSON."
    )
    parser.add_argument("--kb", help="Knowledge Base directory (overrides NIFI_KB_PATH)")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("locate", help="Show which Knowledge Base is in use and its NiFi version")

    find = sub.add_parser("find", help="Search the catalog by name, type or tag")
    find.add_argument("query", nargs="?", default="")
    find.add_argument("--kind", help="processor | controller-service | reporting-task")
    find.add_argument("--tag")
    find.add_argument("--limit", type=int, default=25)

    show = sub.add_parser("show", help="Print component.md for one component")
    show.add_argument("name")
    show.add_argument("--kind")
    show.add_argument("--details", action="store_true", help="Print additionalDetails.md instead")

    props = sub.add_parser("props", help="Print the property table with JSON keys")
    props.add_argument("name")
    props.add_argument("--kind")

    services = sub.add_parser(
        "services", help="List controller services that satisfy a component's service properties"
    )
    services.add_argument("name")
    services.add_argument("--kind")
    services.add_argument("--property", help="Limit to one property")

    validate = sub.add_parser("validate", help="Check a flow definition JSON against the catalog")
    validate.add_argument("flow")

    normalize = sub.add_parser(
        "normalize",
        help="Fill in the fields NiFi writes on export, using each component's KB defaults",
    )
    normalize.add_argument("flow")
    normalize.add_argument("-o", "--output", help="Write here instead of editing in place")

    ids = sub.add_parser("ids", help="Print random UUIDs to use as component identifiers")
    ids.add_argument("count", nargs="?", type=int, default=1)

    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.command == "ids":
        return cmd_ids(args)
    handlers = {
        "locate": cmd_locate,
        "find": cmd_find,
        "show": cmd_show,
        "props": cmd_props,
        "services": cmd_services,
        "validate": cmd_validate,
        "normalize": cmd_normalize,
    }
    try:
        search_from = Path(args.flow).parent if getattr(args, "flow", None) else None
        kb = Kb(discover(args.kb, search_from))
        return handlers[args.command](kb, args)
    except KbError as exc:
        print("%s" % exc, file=sys.stderr)
        return 2
    except BrokenPipeError:
        return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Query a NiFi Knowledge Base and validate flow definition JSON against it.

The Knowledge Base is produced by qubership-nifi-kb-builder-tool and describes one
exact NiFi version: component types, bundle coordinates, property descriptors,
relationships and controller service APIs. Everything here is a lookup against
those files - nothing contacts a NiFi instance.

Python 3.12+, standard library only.
"""

import argparse
import collections
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
# A time duration as NiFi parses it: a number and one of the units that
# org.apache.nifi.time.DurationFormat (2.x) and org.apache.nifi.util.FormatUtils (1.x) accept,
# matched case-insensitively. A bare number has no unit and is invalid.
TIME_DURATION = re.compile(
    r"^\s*([0-9]+(?:\.[0-9]+)?)\s*(ns|nano|nanos|nanosecond|nanoseconds|micro|micros|"
    r"microsecond|microseconds|ms|milli|millis|millisecond|milliseconds|s|sec|secs|second|"
    r"seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|wk|wks|week|weeks)"
    r"\s*$",
    re.IGNORECASE,
)

# The definitionFormat of a component.json built from NiFi 1.x. It combines the 1.x instance API
# with details taken from the component HTML, and its field names and shapes differ from those of
# the 2.x definition API that the rest of this script reads.
FORMAT_1X = "normalized-nifi-1x"

EL_SCOPES = ("NONE", "ENVIRONMENT", "FLOWFILE_ATTRIBUTES")

# The NARs of the scripted components. Their "Script Body" has EL scope NONE, so NiFi passes a
# Groovy "${...}" to the script engine unchanged, and validate does not report it.
SCRIPTING_ARTIFACTS = ("nifi-groovyx-nar", "nifi-scripting-nar")

# Default canvas spacing, from the grid steps in SKILL.md: processors in a column sit 300 px
# apart, and columns 640 px apart.
GRID_ROW = 300
GRID_COLUMN = 640

# The kinds of resource a property can reference, as resourceDefinition names them.
RESOURCE_TYPES = {"FILE": "file", "DIRECTORY": "directory", "URL": "URL", "TEXT": "text"}

# A bundle group that only a qubership-nifi image ships. A Knowledge Base built from such an image
# may use what the image adds to Apache NiFi, described in references/qubership-nifi.md.
QUBERSHIP_GROUP = "org.qubership.nifi"

# A soft limit: a process group with more processors than this gets a warning suggesting
# child process groups, because the canvas becomes hard to read.
LARGE_GROUP_PROCESSORS = 12

# Sources that belong at a zero run schedule: each one waits for the next event inside its own
# client or server, so a zero period costs no idle CPU and any other period only adds latency.
# The list comes from a review of the processors' source code and from measurements on a
# qubership-nifi container. One set serves 1.x and 2.x: a type the Knowledge Base lacks never
# matches, and the ConsumeKafka*_2_6 pair also ships with qubership-nifi 2.x. ConnectWebSocket
# accepts an incoming connection, so the zero-period check never reaches it; it is listed so
# that the set matches the documentation.
ZERO_PERIOD_SOURCES = frozenset({
    "org.apache.nifi.processors.standard.ListenHTTP",
    "org.apache.nifi.processors.standard.ListenFTP",
    "org.apache.nifi.snmp.processors.ListenTrapSNMP",
    "org.apache.nifi.processors.websocket.ListenWebSocket",
    "org.apache.nifi.processors.websocket.ConnectWebSocket",
    "org.apache.nifi.processors.slack.ListenSlack",
    "org.apache.nifi.kafka.processors.ConsumeKafka",
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafka_1_0",
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafka_2_0",
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafka_2_6",
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafkaRecord_1_0",
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafkaRecord_2_0",
    "org.apache.nifi.processors.kafka.pubsub.ConsumeKafkaRecord_2_6",
    "org.apache.nifi.jms.processors.ConsumeJMS",
    "org.apache.nifi.amqp.processors.ConsumeAMQP",
    "org.apache.nifi.processors.mqtt.ConsumeMQTT",
    "org.apache.nifi.processors.azure.eventhub.ConsumeAzureEventHub",
    "org.apache.nifi.processors.azure.eventhub.GetAzureEventHub",
    "org.apache.nifi.processors.aws.kinesis.stream.ConsumeKinesisStream",
    "org.apache.nifi.processors.aws.kinesis.ConsumeKinesis",
    "org.apache.nifi.processors.box.ConsumeBoxEvents",
    "org.apache.nifi.processors.twitter.ConsumeTwitter",
})

# The run schedule for HandleHttpRequest and for a listener that ZERO_PERIOD_SOURCES leaves out.
# At a zero period such a listener polls its internal queue continually: on an idle qubership-nifi
# container, HandleHttpRequest adds up to 10% idle CPU usage.
LISTENER_PERIOD = "50 millis"

# NiFi 1.x reports Expression Language support as a display label. In 1.x, ENVIRONMENT covers the
# Variable Registry as well as environment variables and system properties.
EL_SCOPE_1X = {
    "Not Supported": "NONE",
    "Variable Registry Only": "ENVIRONMENT",
    "Variable Registry and FlowFile Attributes": "FLOWFILE_ATTRIBUTES",
}

# Default concurrent tasks and scheduling period per strategy, from
# org.apache.nifi.scheduling.SchedulingStrategy in NiFi 1.28.1. A 1.x Knowledge Base records no
# per-component scheduling defaults, so these framework values stand in for every processor.
# EVENT_DRIVEN has no period. PRIMARY_NODE_ONLY is left out because 1.x deprecates it.
SCHEDULING_1X = {
    "TIMER_DRIVEN": (1, "0 sec"),
    "CRON_DRIVEN": (1, "* * * * * ?"),
    "EVENT_DRIVEN": (0, None),
}


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


def _workspace_root():
    """The directory a Knowledge Base scan must stay inside.

    $CLAUDE_PROJECT_DIR when set, otherwise the git repository that contains the current
    directory, otherwise the current directory itself.
    """
    project = os.environ.get("CLAUDE_PROJECT_DIR")
    if project:
        return Path(project).expanduser().resolve()
    cwd = Path.cwd().resolve()
    for candidate in [cwd] + list(cwd.parents):
        if (candidate / ".git").exists():
            return candidate
    return cwd


def _is_within(path, root):
    try:
        path.relative_to(root)
    except ValueError:
        return False
    return True


def _search_roots(start):
    """Directories worth scanning for a Knowledge Base, nearest first.

    `start` is scanned only when it lies inside the workspace, so a flow file kept elsewhere
    never makes the scan leave it.
    """
    workspace = _workspace_root()
    if start != workspace and _is_within(start, workspace):
        return [start, workspace]
    return [workspace]


def discover(explicit=None, search_from=None, flow_version=None):
    """Resolve the Knowledge Base: --kb, then NIFI_KB_PATH, then a scan of the workspace.

    The scan covers `search_from` (the current directory by default) when it lies inside the
    workspace, then the workspace root; see `_workspace_root`. When the scan finds several
    Knowledge Bases, `flow_version` picks the one whose NiFi version equals it. Otherwise the
    nearest scanned directory that holds a Knowledge Base wins, and several Knowledge Bases
    in that one directory are an error that lists them.
    """
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
    everywhere = []
    found = []
    for root in _search_roots(start):
        in_root = []
        for manifest in sorted(root.glob("*/manifest.json")) + sorted(
            root.glob("*/*/manifest.json")
        ):
            candidate = manifest.parent
            if _is_kb_root(candidate) and candidate not in everywhere:
                everywhere.append(candidate)
                in_root.append(candidate)
        # Without a flow version to match, the nearest root that has a Knowledge Base wins.
        if in_root and not found:
            found = in_root

    if not found:
        raise KbError(
            "No NiFi Knowledge Base found in %s. The search does not leave the workspace, so "
            "for a Knowledge Base kept elsewhere set NIFI_KB_PATH or pass --kb <path>. To "
            "build one, use %s-tool." % (_workspace_root(), BUILDER_PREFIX)
        )
    if flow_version and len(everywhere) > 1:
        matching = [p for p in everywhere
                    if _manifest(p).get("nifi", {}).get("version") == flow_version]
        if len(matching) == 1:
            print("Several Knowledge Bases found; using %s, which matches the flow's NiFi %s."
                  % (matching[0], flow_version), file=sys.stderr)
            return matching[0]
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


def flow_nifi_version(path):
    """The NiFi version a flow was built for, or None when the file does not say.

    Built-in components carry the NiFi version as their bundle version, so the most common version
    among the org.apache.nifi bundles is the flow's. Bundles from other groups carry versions of
    their own and are not counted.
    """
    try:
        root, _ = root_group(json.loads(Path(path).read_text(encoding="utf-8")))
    except (OSError, ValueError, KbError):
        return None
    counts = collections.Counter()
    for _, group in walk_groups(root):
        for component in (group.get("processors") or []) + (group.get("controllerServices") or []):
            bundle = component.get("bundle") or {}
            if bundle.get("group") == "org.apache.nifi" and bundle.get("version"):
                counts[bundle["version"]] += 1
    return counts.most_common(1)[0][0] if counts else None


def _adapt_descriptor_1x(descriptor):
    allowed = descriptor.get("allowableValues") or []
    if allowed and "allowableValue" in allowed[0]:
        descriptor["allowableValues"] = [
            {"value": item["allowableValue"].get("value"),
             "displayName": item["allowableValue"].get("displayName"),
             "description": item["allowableValue"].get("description", "")}
            for item in allowed
        ]
    service_api = descriptor.get("identifiesControllerService")
    if isinstance(service_api, str) and "typeProvidedByValue" not in descriptor:
        bundle = descriptor.get("identifiesControllerServiceBundle") or {}
        descriptor["typeProvidedByValue"] = dict(bundle, type=service_api)
    label = descriptor.get("expressionLanguageScope")
    if label not in EL_SCOPES:
        descriptor.setdefault("expressionLanguageScopeDescription", label)
        # "true (undefined scope)" marks a property that accepts Expression Language without
        # declaring a scope. UNDEFINED keeps it apart from NONE, so ${...} in it is not an error.
        descriptor["expressionLanguageScope"] = EL_SCOPE_1X.get(
            label, "UNDEFINED" if descriptor.get("supportsEl") else "NONE")


def adapt_1x(definition, kind, documentation=""):
    """Rewrite a NiFi 1.x component definition in the field names of the 2.x definition API.

    Every command reads the 2.x names, so translating once at load time keeps one code path for
    both versions. A 2.x key the definition already carries is kept as it is. `documentation` is
    the text of componentDocumentation.md, the only place a 1.x Knowledge Base records whether a
    processor has dynamic relationships.
    """
    if "typeDescription" not in definition and "description" in definition:
        definition["typeDescription"] = definition["description"]
    for descriptor in (definition.get("propertyDescriptors") or {}).values():
        _adapt_descriptor_1x(descriptor)
    definition.setdefault("supportsDynamicProperties", bool(definition.get("dynamicProperties")))
    if "providedApiImplementations" not in definition and definition.get("controllerServiceApis"):
        definition["providedApiImplementations"] = [
            dict(api.get("bundle") or {}, type=api["type"])
            for api in definition["controllerServiceApis"]
        ]
    if kind != "PROCESSOR":
        return definition

    definition.setdefault("supportsDynamicRelationships",
                          "### Dynamic Relationships" in documentation)
    definition.setdefault("primaryNodeOnly", bool(definition.get("executionNodeRestricted")))
    definition.setdefault("triggerSerially", definition.get("supportsParallelProcessing") is False)
    if "supportedSchedulingStrategies" not in definition:
        strategies = ["TIMER_DRIVEN", "CRON_DRIVEN"]
        if definition.get("supportsEventDriven"):
            strategies.append("EVENT_DRIVEN")
        definition["supportedSchedulingStrategies"] = strategies
        definition["defaultSchedulingStrategy"] = "TIMER_DRIVEN"
        definition["defaultConcurrentTasksBySchedulingStrategy"] = {
            s: SCHEDULING_1X[s][0] for s in strategies}
        definition["defaultSchedulingPeriodBySchedulingStrategy"] = {
            s: SCHEDULING_1X[s][1] for s in strategies if SCHEDULING_1X[s][1]}
        definition["schedulingDefaultsFromFramework"] = True
    return definition


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
        """Load and cache component.json for an index entry, in the 2.x field names."""
        key = entry["path"]
        if key not in self._definitions:
            base = self.root / "components" / key
            component = json.loads((base / "component.json").read_text(encoding="utf-8"))
            if component.get("definitionFormat") == FORMAT_1X:
                documentation = ""
                doc_path = base / "componentDocumentation.md"
                if entry["kind"] == "PROCESSOR" and doc_path.is_file():
                    documentation = doc_path.read_text(encoding="utf-8", errors="replace")
                adapt_1x(component["definition"], entry["kind"], documentation)
            self._definitions[key] = component
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


def valid_duration(period):
    """Whether NiFi accepts a value as a time duration, such as a timer-driven Run Schedule."""
    return isinstance(period, str) and bool(TIME_DURATION.match(period))


def zero_period(period):
    """Whether a timer-driven scheduling period means 'run again immediately'.

    Only a zero with a time unit, such as '0 sec' or '0 millis', counts. A bare '0' is not a
    valid time duration, so NiFi marks the processor invalid rather than running it.
    """
    match = TIME_DURATION.match(period) if isinstance(period, str) else None
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
    it - registry version diffs, external service references, flow comparison tooling - and
    where they collide inside one NiFi, NiFi may replace them with generated identifiers.
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
    print("Format         : %s"
          % kb.manifest.get("collection", {}).get("definitionFormat", "?"))
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
    if any(entry["group"] == QUBERSHIP_GROUP for entry in kb.index):
        print("Platform       : qubership-nifi (see references/qubership-nifi.md)")
    else:
        print("Platform       : Apache NiFi")
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

    # A 1.x Knowledge Base keeps the full component page, which documents dynamic properties,
    # dynamic relationships, and state that component.json covers only in part.
    documentation = base / "componentDocumentation.md"
    if args.doc:
        if not documentation.is_file():
            raise KbError(
                "%s has no componentDocumentation.md. Only a Knowledge Base built from NiFi 1.x "
                "has one; use --details for the extended docs." % simple_name(entry["type"])
            )
        sys.stdout.write(documentation.read_text(encoding="utf-8"))
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
    if documentation.is_file():
        print()
        print("Full component page: kb.py show %s --doc" % simple_name(entry["type"]))
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
        if definition.get("schedulingDefaultsFromFramework"):
            print("         framework defaults: a 1.x Knowledge Base records no per-component "
                  "schedule")
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
    print("%-38s %-4s %-10s %-20s %s" % ("JSON KEY", "REQ", "EL", "DEFAULT", "ALLOWED / SERVICE API"))
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
        resource = descriptor.get("resourceDefinition")
        if resource:
            kinds = [RESOURCE_TYPES.get(t, t.lower()) for t in resource.get("resourceTypes") or []]
            kinds = " or ".join(kinds) if len(kinds) < 3 else "%s, or %s" % (", ".join(kinds[:-1]),
                                                                            kinds[-1])
            several = " (comma-separated list)" if resource.get("cardinality") == "MULTIPLE" else ""
            print("    takes: %s%s" % (kinds, several))


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
# a NiFi 2.10 import was observed to dereference. Every NiFi 1.28.1 export writes all of them
# too, so the check holds for a 1.x flow. Rather than memorize them, run `kb.py normalize`,
# which fills every field NiFi writes on export.
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
    """The root process group and the envelope of a flow definition or a Registry export.

    Both carry the root group as a top-level `flowContents`; any other shape is refused.
    """
    if isinstance(doc, dict) and isinstance(doc.get("flowContents"), dict):
        return doc["flowContents"], doc
    raise KbError(
        "This file is not a NiFi flow definition. Expected a top-level 'flowContents' object."
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
        # Matched on the display name, because ExecuteGroovyScript used the key
        # groovyx-script-body before NiFi 2.7.
        script_body = (descriptor.get("displayName", key) == "Script Body"
                       and entry["artifact"] in SCRIPTING_ARTIFACTS)
        if (isinstance(value, str) and EL_TOKEN.search(value) and scope == "NONE"
                and not script_body):
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
        if descriptor.get("sensitive"):
            report.warn(
                where,
                "required property '%s' is sensitive and has no value. NiFi leaves sensitive "
                "values out of a downloaded flow, so an export always looks like this. Reference "
                "a parameter (#{...}) and set its value in the target NiFi." % key,
            )
            continue
        report.error(
            where,
            "required property '%s' has no value and no default." % key,
        )


def _check_service_refs(kb, report, where, component, entry, services, external, scope=None):
    """Check each service-typed property against the services the flow defines.

    `scope`, when given, is a pair: the identifiers of the groups whose services the
    component can see (its own group and every ancestor), and a map from each service
    identifier to its group's identifier and path.
    """
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
        if scope is not None:
            visible, service_groups = scope
            owner_id, owner_path = service_groups.get(value, (None, None))
            if owner_id is not None and owner_id not in visible:
                report.error(
                    where,
                    "property '%s' references controller service '%s', which is defined in %s. "
                    "A component can use only the services of its own group and of that "
                    "group's ancestors, so NiFi leaves the reference unresolved. Move the "
                    "service to a group that contains both." % (key, target.get("name"), owner_path),
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


# Processors whose every dynamic property becomes a relationship of the same name.
PER_PROPERTY_RELATIONSHIPS = {"QueryRecord", "RouteHL7", "RouteOnContent"}
# Processors whose 'Routing Strategy' decides between one relationship per dynamic property
# and a single 'matched' relationship, for which the dynamic properties are conditions.
ROUTING_STRATEGY_RELATIONSHIPS = {"RouteOnAttribute", "RouteText"}


def _configured(definition, component, label):
    """The value NiFi sees for the property whose name or display name is `label`."""
    descriptors = definition.get("propertyDescriptors") or {}
    for key, descriptor in descriptors.items():
        if label in (key, descriptor.get("name"), descriptor.get("displayName")):
            return _effective(descriptors, component.get("properties") or {}, key)
    return None


def _dynamic_relationships(definition, component):
    """The relationships this processor creates at run time, and whether the set is certain.

    Returns (None, set()) for a processor without dynamic relationships. Otherwise returns
    ("exact", names) when the configuration fixes the set, or ("unproven", names) with a
    best guess when it does not: the processor is not one this function knows, or a
    controlling property holds an expression or a parameter reference.

    The catalog lists only the relationships a processor always has, so without this set a
    flow routed through RouteOnAttribute always looks like a reference to a relationship
    that does not exist.
    """
    supported = (definition.get("supportsDynamicRelationships")
                 or definition.get("supportsDynamicRelationship") or False)
    if not supported:
        return None, set()
    declared = {d.get("name") for d in (definition.get("propertyDescriptors") or {}).values()}
    declared |= set(definition.get("propertyDescriptors") or {})
    dynamic = {key for key in (component.get("properties") or {}) if key not in declared}
    kind = simple_name(definition.get("type") or component.get("type") or "")
    if kind in PER_PROPERTY_RELATIONSHIPS:
        return "exact", dynamic
    if kind in ROUTING_STRATEGY_RELATIONSHIPS:
        strategy = _configured(definition, component, "Routing Strategy")
        if not _is_literal(strategy):
            return "unproven", dynamic
        # 'Route to Property name' and 'Route to each matching Property Name' create one
        # relationship per dynamic property. Every other strategy routes to 'matched'.
        if "property name" in strategy.lower():
            return "exact", dynamic
        return "exact", {"matched"}
    if kind == "DistributeLoad":
        # The relationships are named 1 to 'Number of Relationships'. A dynamic property
        # named after one of them sets its weight and creates nothing.
        count = _configured(definition, component, "Number of Relationships")
        if _is_literal(count) and count.strip().isdigit():
            return "exact", {str(number) for number in range(1, int(count) + 1)}
        return "unproven", {"1"} | dynamic
    return "unproven", dynamic


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
    """Map each parameter name visible in a context to its sensitive flag, following inheritance.

    A parameter the context declares itself takes precedence over an inherited one.
    """
    _seen = _seen or set()
    if name in _seen or name not in contexts:
        return {}
    _seen.add(name)
    context = contexts[name]
    names = {}
    for parameter in context.get("parameters") or []:
        # NiFi writes either {"name": ...} or {"parameter": {"name": ...}}.
        entry = parameter.get("parameter", parameter)
        if entry.get("name"):
            names[entry["name"]] = bool(entry.get("sensitive"))
    for inherited in context.get("inheritedParameterContexts") or []:
        parent = inherited if isinstance(inherited, str) else inherited.get("name")
        if parent:
            for key, sensitive in _context_parameters(contexts, parent, _seen).items():
                names.setdefault(key, sensitive)
    return names


# A parameter reference: #{name}, or #{'name'} with the name quoted. `##{` escapes it.
PARAMETER_REFERENCE = re.compile(r"(?<!#)#\{('[^']*'|[^}]+)\}")


def _parameter_names(value):
    return [name[1:-1] if name.startswith("'") and name.endswith("'") else name
            for name in PARAMETER_REFERENCE.findall(value)]


def _property_sensitive(kb, kind, component, key):
    """Whether a property is sensitive: from the catalog, else from the flow's own descriptor."""
    entry = kb.lookup(kind, component.get("type", ""))
    if entry is not None:
        descriptor = kb.definition(entry)["definition"].get("propertyDescriptors", {}).get(key)
        if descriptor is not None:
            return bool(descriptor.get("sensitive"))
    descriptor = (component.get("propertyDescriptors") or {}).get(key)
    return bool(descriptor.get("sensitive")) if isinstance(descriptor, dict) else None


def _check_parameters(kb, report, root, envelope):
    """A #{...} reference needs a context bound to the group and declaring the parameter.

    The parameter must also match the property's sensitivity: NiFi lets a sensitive property
    reference only a sensitive parameter, and a non-sensitive property only a non-sensitive one.
    """
    contexts = envelope.get("parameterContexts") or {}

    # A parameter context binds only the group that names it; a child group is not bound to
    # its parent's context.
    def walk(group, path):
        bound = group.get("parameterContextName")
        references = {}
        uses = []
        components = [("PROCESSOR", c) for c in group.get("processors") or []]
        components += [("CONTROLLER_SERVICE", c) for c in group.get("controllerServices") or []]
        for kind, component in components:
            for key, value in (component.get("properties") or {}).items():
                if isinstance(value, str):
                    for name in _parameter_names(value):
                        where = "%s[%s].%s" % (path, component.get("name", "?"), key)
                        references.setdefault(name, where)
                        uses.append((name, where, _property_sensitive(kb, kind, component, key)))
        if references:
            if not bound:
                report.error(
                    path,
                    "properties reference parameter(s) %s but no parameterContextName is set on "
                    "this group, and a child group is not bound to its parent's context. NiFi "
                    "reports 'Property references one or more "
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
                for name, where, sensitive in uses:
                    if name in declared and sensitive is not None and sensitive != declared[name]:
                        report.error(
                            where,
                            "a %s property references the %s parameter '%s'. NiFi requires "
                            "the property and the parameter to be both sensitive or both not."
                            % ("sensitive" if sensitive else "non-sensitive",
                               "sensitive" if declared[name] else "non-sensitive", name),
                        )
        for child in group.get("processGroups") or []:
            walk(child, "%s.%s" % (path, child.get("name") or child.get("identifier", "?")))

    walk(root, "flowContents")


def _ancestry(group_id, parent_of):
    """The identifiers of a group and of every group above it."""
    groups = set()
    while group_id is not None and group_id not in groups:
        groups.add(group_id)
        group_id = parent_of.get(group_id)
    return groups


def _check_group_size(report, group_path, group):
    count = len(group.get("processors") or [])
    if count > LARGE_GROUP_PROCESSORS:
        report.warn(
            group_path,
            "%d processors in one process group. Above %d the canvas gets hard to read; "
            "consider moving each stage of the flow into a child process group connected "
            "through ports. See SKILL.md, \"Split large groups into child process groups\"."
            % (count, LARGE_GROUP_PROCESSORS),
        )


def _check_port_names(report, group_path, group):
    for collection, kind, prefix in (("inputPorts", "input port", "in_"),
                                     ("outputPorts", "output port", "out_")):
        seen = set()
        for port in group.get(collection) or []:
            name = port.get("name") or ""
            if name in seen:
                report.error(
                    group_path,
                    "two %ss are named '%s'. Port names must be unique within a process "
                    "group, and NiFi rejects the import." % (kind, name),
                )
            seen.add(name)
            if not name.startswith(prefix):
                report.warn(
                    group_path,
                    "%s '%s' does not follow the naming convention: %s names start with "
                    "'%s', followed by what passes through the port, such as '%sstored'."
                    % (kind, name, kind, prefix, prefix),
                )


def _check_remote_endpoint(report, where, role, endpoint, kind, remote_group):
    """A remote input port receives FlowFiles, and a remote output port emits them."""
    declared = endpoint.get("groupId")
    if not declared:
        report.warn(
            where,
            "%s.groupId is missing. The %s is a port of remote process group %s, and NiFi "
            "exports name that group here. `kb.py normalize` fills it in."
            % (role, role, remote_group),
        )
    elif declared != remote_group:
        report.error(
            where,
            "%s.groupId is %s, but the %s is a port of remote process group %s. Set groupId "
            "to the identifier of the remote process group." % (role, declared, role, remote_group),
        )
    expected = "REMOTE_INPUT_PORT" if role == "destination" else "REMOTE_OUTPUT_PORT"
    if kind != expected:
        report.error(
            where,
            "the %s is %s of remote process group %s. A connection sends FlowFiles to a "
            "remote input port and receives them from a remote output port."
            % (role, kind, remote_group),
        )


def _check_endpoint_scope(report, where, role, endpoint, group_id, group_of, parent_of,
                          group_paths, port_kinds, remote_owner):
    """A connection joins components of its own group, a port of a direct child group, or a
    port of a remote process group in its own group."""
    component_id = endpoint["id"]
    owner = group_of.get(component_id)
    declared = endpoint.get("groupId")
    if component_id in remote_owner and owner == group_id:
        _check_remote_endpoint(report, where, role, endpoint, port_kinds[component_id],
                               remote_owner[component_id])
        return
    if declared and declared != owner:
        report.error(
            where,
            "%s.groupId is %s, but component %s is in %s. Set groupId to the identifier of "
            "the group that contains the component."
            % (role, declared, component_id, group_paths.get(owner, owner)),
        )
    kind = port_kinds.get(component_id)
    if owner == group_id:
        # Inside its own group an input port only emits FlowFiles and an output port only
        # receives them.
        if (role, kind) in (("source", "OUTPUT_PORT"), ("destination", "INPUT_PORT")):
            label = "an output port" if kind == "OUTPUT_PORT" else "an input port"
            report.error(
                where,
                "the %s %s is %s of this connection's own group. Inside its group, %s can "
                "only be a connection's %s."
                % (role, component_id, label, label,
                   "destination" if role == "source" else "source"),
            )
        return
    if parent_of.get(owner) == group_id and kind:
        if not declared:
            report.error(
                where,
                "%s.groupId is missing. The %s is a port of child group %s, and NiFi needs "
                "groupId to find it: without it the upload fails with HTTP 500. `kb.py "
                "normalize` fills it in." % (role, role, group_paths.get(owner, owner)),
            )
        expected = "OUTPUT_PORT" if role == "source" else "INPUT_PORT"
        if kind != expected:
            report.error(
                where,
                "the %s is %s of child group %s. From the parent group, a connection "
                "leaves a child through an output port and enters it through an input port."
                % (role, kind, group_paths.get(owner, owner)),
            )
        return
    report.error(
        where,
        "the %s %s is in %s, but this connection belongs to %s. A connection can join "
        "components of its own group, or a port of a direct child group; to reach anything "
        "else, route the FlowFiles through ports."
        % (role, component_id, group_paths.get(owner, owner), group_paths.get(group_id, group_id)),
    )


def _check_port_connections(report, root, outgoing, incoming, group_of, parent_of, group_paths):
    """NiFi marks a port invalid unless a connection meets it on each side."""
    root_id = root.get("identifier")
    for group_path, group in walk_groups(root):
        is_root = group.get("identifier") == root_id
        for collection, kind in (("inputPorts", "input port"), ("outputPorts", "output port")):
            for port in group.get(collection) or []:
                port_id = port.get("identifier")
                emits = port_id in outgoing
                receives = port_id in incoming
                inner, outer = (emits, receives) if kind == "input port" else (receives, emits)
                if not inner:
                    report.error(
                        group_path,
                        "%s '%s' has no connection inside its group. NiFi marks the port "
                        "invalid and cannot start it." % (kind, port.get("name")),
                    )
                # The root group's outer side is whatever the flow is imported into.
                if not outer and not is_root:
                    report.error(
                        group_path,
                        "%s '%s' has no connection in the parent group. NiFi marks the port "
                        "invalid and cannot start it." % (kind, port.get("name")),
                    )


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
            "top-level 'latest' is metadata NiFi adds when you download a flow, not part of "
            "the flow. NiFi and NiFi Registry 2.10 both accept a file that carries it, but a "
            "committed flow is cleaner without it. `kb.py normalize` removes it.",
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
    # Where each component lives, for the checks that cross a group boundary: connection
    # endpoints, ports, and which controller services a component can reference.
    group_of = {}
    parent_of = {}
    group_paths = {}
    port_kinds = {}
    # The remote process group that holds each remote port. A connection endpoint names the
    # port, and its groupId names the remote process group.
    remote_owner = {}
    service_groups = {}

    for group_path, group in walk_groups(root):
        group_id = group.get("identifier")
        group_paths[group_id] = group_path
        for child in group.get("processGroups") or []:
            parent_of[child.get("identifier")] = group_id
        for kind, collection in (("INPUT_PORT", "inputPorts"), ("OUTPUT_PORT", "outputPorts")):
            for port in group.get(collection) or []:
                port_kinds[port.get("identifier")] = kind
        remote_ports = []
        for remote in group.get("remoteProcessGroups") or []:
            for kind, collection in (("REMOTE_INPUT_PORT", "inputPorts"),
                                     ("REMOTE_OUTPUT_PORT", "outputPorts")):
                for port in remote.get(collection) or []:
                    port_kinds[port.get("identifier")] = kind
                    remote_owner[port.get("identifier")] = remote.get("identifier")
                    remote_ports.append(port)
        _check_group_size(report, group_path, group)
        _check_port_names(report, group_path, group)
        _check_layout(report, group_path, group)
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
            service_groups[service.get("identifier")] = (group_id, group_path)
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
            None,
        ):
            items = remote_ports if collection is None else group.get(collection) or []
            for item in items:
                identifier = item.get("identifier")
                if identifier in identifiers:
                    report.error(
                        group_path,
                        "identifier %s is used by both '%s' and '%s'. Identifiers must be "
                        "unique across the flow."
                        % (identifier, identifiers[identifier], item.get("name", collection or "remote port")),
                    )
                identifiers[identifier] = item.get("name", collection or "remote port")
                group_of[identifier] = group_id
                problem = identifier_problem(identifier)
                if problem:
                    report.warn(
                        group_path,
                        "identifier of '%s' %s (%r). NiFi mints a random UUID for every "
                        "component and keeps the identifier you write through import and "
                        "re-export, so this becomes the component's permanent portable id. "
                        "Generate one per component with `kb.py ids`."
                        % (item.get("name", collection or "remote port"), problem, identifier),
                    )
        for connection in group.get("connections") or []:
            connections.append((group_path, group_id, connection))
            source = (connection.get("source") or {}).get("id")
            destination = (connection.get("destination") or {}).get("id")
            outgoing.setdefault(source, set()).update(connection.get("selectedRelationships") or [])
            if destination:
                incoming.add(destination)

    bundle_mismatch = []

    for group_path, group in walk_groups(root):
        scope = (_ancestry(group.get("identifier"), parent_of), service_groups)
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
            _check_service_refs(kb, report, where, service, entry, services, external, scope)
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
            _check_service_refs(kb, report, where, processor, entry, services, external, scope)

            definition = kb.definition(entry)["definition"]
            if entry.get("deprecated"):
                report.warn(where, "%s is deprecated in NiFi %s." % (simple_name(entry["type"]), kb.nifi_version))

            supported = {r["name"] for r in definition.get("supportedRelationships", [])}
            auto = set(processor.get("autoTerminatedRelationships") or [])
            connected = outgoing.get(processor.get("identifier"), set())
            certainty, dynamic = _dynamic_relationships(definition, processor)
            handled = auto | connected
            # A guessed relationship set cannot make a flow wrong, so what does not match it
            # is collected into one warning instead of an error per name.
            unproven = set()

            prose_relationships = _prose_relationships(kb, entry, definition)
            for unknown in sorted(handled - supported):
                if unknown in dynamic:
                    continue
                if certainty == "unproven":
                    unproven.add(unknown)
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
                    % (unknown, simple_name(entry["type"]),
                       ", ".join(sorted(supported | dynamic)) or "none"),
                )
            if certainty == "unproven":
                unproven |= dynamic - handled
                required = supported
            else:
                required = supported | dynamic
            if unproven:
                report.warn(
                    where,
                    "%s creates relationships at run time, and this script cannot work out "
                    "which ones from its configuration. These names may not match them: %s. "
                    "verify_live.py reports an unhandled one."
                    % (simple_name(entry["type"]), ", ".join(sorted(unproven))),
                )
            for dangling in sorted(required - handled):
                report.error(
                    where,
                    "relationship '%s' is neither connected nor auto-terminated. NiFi refuses "
                    "to start a processor with an unhandled relationship." % dangling,
                )
            for both in sorted(auto & connected):
                report.warn(
                    where,
                    "relationship '%s' is auto-terminated and also connected. NiFi imports the "
                    "connection and drops the auto-termination, so the file no longer matches "
                    "the flow NiFi runs. Remove it from autoTerminatedRelationships." % both,
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
            if strategy == "PRIMARY_NODE_ONLY" and nifi_major(kb) == 1:
                report.warn(
                    where,
                    "schedulingStrategy 'PRIMARY_NODE_ONLY' is deprecated in NiFi 1.x and removed "
                    "in 2.0. Use 'TIMER_DRIVEN' and set executionNode to PRIMARY.",
                )
            elif strategy and supported_strategies and strategy not in supported_strategies:
                report.error(
                    where,
                    "schedulingStrategy '%s' is not supported. Available: %s."
                    % (strategy, ", ".join(supported_strategies)),
                )
            period = processor.get("schedulingPeriod")
            if (processor.get("schedulingStrategy", "TIMER_DRIVEN") == "TIMER_DRIVEN"
                    and period is not None and not valid_duration(period)):
                report.error(
                    where,
                    "schedulingPeriod %r is not a valid time duration. NiFi imports it but marks "
                    "the processor invalid: \"'Run Schedule' validated against %r is invalid "
                    "because Scheduling Period is not a valid time duration\". Give a number "
                    "with a unit, such as '0 sec' or '100 millis'." % (period, period),
                )
            # The catalog has no flag for a source that belongs at a zero period, so a fixed
            # list of types identifies them.
            if (definition.get("inputRequirement") == "INPUT_FORBIDDEN"
                    and entry["type"] not in ZERO_PERIOD_SOURCES
                    and processor.get("schedulingStrategy", "TIMER_DRIVEN") == "TIMER_DRIVEN"
                    and zero_period(processor.get("schedulingPeriod"))):
                name = simple_name(entry["type"])
                period = processor.get("schedulingPeriod")
                default_period = (definition.get("defaultSchedulingPeriodBySchedulingStrategy")
                                  or {}).get("TIMER_DRIVEN")
                if name == "HandleHttpRequest" or name.startswith("Listen"):
                    report.warn(
                        where,
                        "source processor scheduled every %r. %s polls its internal queue on "
                        "every run, so at a zero period it runs continually and costs idle CPU. "
                        "Set the run schedule to %r; keep '0 sec' only when the user asks for "
                        "the lowest latency." % (period, name, LISTENER_PERIOD),
                    )
                else:
                    # Some sources ship with a zero default of their own, so citing it as the
                    # remedy would recommend the very setting being complained about. A 1.x
                    # Knowledge Base holds only the framework default, which says nothing
                    # either way.
                    if definition.get("schedulingDefaultsFromFramework"):
                        remedy = ("a 1.x Knowledge Base records no default of %s's own, so "
                                  "choose the period yourself" % name)
                    elif default_period and not zero_period(default_period):
                        remedy = "NiFi's own default for %s is %r" % (name, default_period)
                    else:
                        remedy = ("%s ships with a zero default of its own, so this one is a "
                                  "judgment call" % name)
                    report.warn(
                        where,
                        "source processor scheduled every %r. With no incoming connection there "
                        "is nothing to throttle it, so the task is rescheduled the moment it "
                        "returns and holds a thread spinning on empty polls. %s; 100 millis is "
                        "a common floor for a source that must react quickly, and seconds are "
                        "normal for a directory poll." % (period, remedy),
                    )
            if definition.get("supportsBatching") and not processor.get("runDurationMillis"):
                unbatched.append(processor.get("name") or simple_name(entry["type"]))
            if definition.get("primaryNodeOnly") and processor.get("executionNode") != "PRIMARY":
                report.warn(
                    where,
                    "%s is primary-node-only; set executionNode to PRIMARY."
                    % simple_name(entry["type"]),
                )

    for group_path, group_id, connection in connections:
        where = "%s.connections[%s]" % (group_path, connection.get("name") or "unnamed")
        _check_import_fields(report, where, connection, "connections")
        for role in ("source", "destination"):
            endpoint = connection.get(role) or {}
            if not endpoint.get("id"):
                continue
            if endpoint["id"] in identifiers:
                _check_endpoint_scope(report, where, role, endpoint, group_id, group_of,
                                      parent_of, group_paths, port_kinds, remote_owner)
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

    _check_port_connections(report, root, outgoing, incoming, group_of, parent_of, group_paths)

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

    _check_parameters(kb, report, root, envelope)

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


def normalize_processor(kb, processor):
    entry = kb.lookup("PROCESSOR", processor.get("type", ""))
    definition = kb.definition(entry)["definition"] if entry else {}
    strategy = processor.setdefault(
        "schedulingStrategy", definition.get("defaultSchedulingStrategy") or "TIMER_DRIVEN")
    periods = definition.get("defaultSchedulingPeriodBySchedulingStrategy") or {}
    tasks = definition.get("defaultConcurrentTasksBySchedulingStrategy") or {}

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
    # Existing entries are kept; a property added to an exported flow gets its own.
    descriptors = processor.get("propertyDescriptors") or {}
    for key in processor["properties"]:
        if key not in descriptors:
            descriptors[key] = descriptor_stub(kb, entry, key)
    processor["propertyDescriptors"] = descriptors
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
    # Existing entries are kept; a property added to an exported flow gets its own.
    descriptors = service.get("propertyDescriptors") or {}
    for key in service["properties"]:
        if key not in descriptors:
            descriptors[key] = descriptor_stub(kb, entry, key)
    service["propertyDescriptors"] = descriptors
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

    # A new processor goes on the next row below everything already on the canvas, so one
    # added to an exported flow does not land on top of the existing layout.
    placed = [component["position"].get("y", 0.0)
              for collection in ("processors", "inputPorts", "outputPorts", "funnels", "labels",
                                 "processGroups", "remoteProcessGroups")
              for component in group[collection]
              if isinstance(component.get("position"), dict)]
    row = max(placed) + GRID_ROW if placed else 0.0
    for processor in group["processors"]:
        processor.setdefault("groupIdentifier", group.get("identifier"))
        if "position" not in processor:
            processor["position"] = {"x": 0.0, "y": float(row)}
            row += GRID_ROW
        normalize_processor(kb, processor)
    for index, service in enumerate(group["controllerServices"]):
        service.setdefault("groupIdentifier", group.get("identifier"))
        normalize_service(kb, service, index)
    # An endpoint is a component of this group, a port of a direct child group, or a port of
    # a remote process group, and NiFi exports name the group that holds it.
    owners = {}
    for collection in ("processors", "inputPorts", "outputPorts", "funnels"):
        for component in group[collection]:
            owners[component.get("identifier")] = group.get("identifier")
    for child in group["processGroups"] + group["remoteProcessGroups"]:
        for port in (child.get("inputPorts") or []) + (child.get("outputPorts") or []):
            owners[port.get("identifier")] = child.get("identifier")
    for connection in group["connections"]:
        connection.setdefault("groupIdentifier", group.get("identifier"))
        normalize_connection(connection)
        for role in ("source", "destination"):
            endpoint = connection.get(role)
            if isinstance(endpoint, dict) and endpoint.get("id") in owners:
                endpoint.setdefault("groupId", owners[endpoint["id"]])
    # Default positions follow the layout in SKILL.md: input ports on a row above the
    # processor column, output ports on a row below it.
    bottom = max([p["position"].get("y", 0.0) for p in group["processors"]] + [0.0]) + GRID_ROW
    for port_collection, kind, row in (("inputPorts", "INPUT_PORT", -GRID_ROW),
                                       ("outputPorts", "OUTPUT_PORT", bottom)):
        for index, port in enumerate(group[port_collection]):
            port.setdefault("groupIdentifier", group.get("identifier"))
            port.setdefault("position", {"x": float(index * GRID_COLUMN), "y": float(row)})
            port.setdefault("type", kind)
            port.setdefault("componentType", kind)
            port.setdefault("concurrentlySchedulableTaskCount", 1)
            port.setdefault("scheduledState", "ENABLED")
            port.setdefault("allowRemoteAccess", False)
            # Failure ports arrived in NiFi 2.0. A 1.x port has no portFunction field, and a
            # 1.28.1 export never writes one.
            if nifi_major(kb) != 1:
                port.setdefault("portFunction", "STANDARD")
    for child in group["processGroups"]:
        normalize_group(kb, child)


# ---------------------------------------------------------------------------
# Canvas layout: connection bends
# ---------------------------------------------------------------------------

# Box sizes on the NiFi 2.x canvas, in pixels. A component's `position` is its top-left
# corner. Labels are left out: they are background notes, and connections may cross them.
CANVAS_SIZES = {
    "processors": (352.0, 128.0),
    "processGroups": (384.0, 176.0),
    "remoteProcessGroups": (384.0, 176.0),
    "inputPorts": (240.0, 48.0),
    "outputPorts": (240.0, 48.0),
    "funnels": (48.0, 48.0),
}
# Approximate size of the label the canvas draws on every connection (name, from, to,
# relationships, queued count). Its height grows with the rows it shows. The label is
# centered on the bend that `labelIndex` names, or on the midpoint of a straight line.
CONNECTION_LABEL = (224.0, 100.0)
# Free space that two connected components need between their boxes to fit the label.
LABEL_CLEARANCE = 260.0
# Gap between the labels of neighboring connections in one bundle, and the steps
# `route_group` tries when a line has to go around a component.
PARALLEL_SPACING = 20.0
DETOUR_STEP = 120.0
DETOUR_TRIES = 6
# Routed lines keep this far from other boxes; validate reports only a real crossing.
ROUTE_MARGIN = 20.0


def _canvas_boxes(group):
    """Map each connectable identifier to (owner identifier, box) on the group's canvas.

    The box is (x, y, width, height). A port of a child group maps to the child group's
    box, because the parent canvas draws a connection to that port as ending at the group.
    """
    boxes = {}
    for collection, (width, height) in CANVAS_SIZES.items():
        for component in group.get(collection) or []:
            position = component.get("position") or {}
            box = (float(position.get("x", 0.0)), float(position.get("y", 0.0)), width, height)
            owner = component.get("identifier")
            boxes[owner] = (owner, box)
            if collection in ("processGroups", "remoteProcessGroups"):
                for port in (component.get("inputPorts") or []) + (component.get("outputPorts") or []):
                    boxes[port.get("identifier")] = (owner, box)
    return boxes


def _center(box):
    x, y, width, height = box
    return (x + width / 2.0, y + height / 2.0)


def _connection_ends(connection, boxes):
    source = boxes.get((connection.get("source") or {}).get("id"))
    destination = boxes.get((connection.get("destination") or {}).get("id"))
    if source is None or destination is None:
        return None
    return source, destination


def _connection_path(connection, boxes):
    ends = _connection_ends(connection, boxes)
    if ends is None:
        return None
    (_, source_box), (_, destination_box) = ends
    bends = [(float(b.get("x", 0.0)), float(b.get("y", 0.0)))
             for b in connection.get("bends") or []]
    return [_center(source_box)] + bends + [_center(destination_box)]


def _segment_hits_box(start, end, box, margin=0.0):
    """Whether the segment from `start` to `end` enters `box` grown by `margin` on each side."""
    x, y, width, height = box
    left, top, right, bottom = x - margin, y - margin, x + width + margin, y + height + margin
    (x0, y0), (x1, y1) = start, end
    dx, dy = x1 - x0, y1 - y0
    low, high = 0.0, 1.0
    # Liang-Barsky clipping: narrow [low, high] to the part of the segment inside the box.
    for p, q in ((-dx, x0 - left), (dx, right - x0), (-dy, y0 - top), (dy, bottom - y0)):
        if p == 0:
            if q < 0:
                return False
            continue
        t = q / p
        if p < 0:
            low = max(low, t)
        else:
            high = min(high, t)
        if low > high:
            return False
    return True


def _obstacles(boxes, *owners):
    """The distinct boxes on the canvas other than those of `owners`."""
    seen = {}
    for owner, box in boxes.values():
        if owner not in owners:
            seen[owner] = box
    return list(seen.values())


def _boxes_overlap(first, second, margin=0.0):
    ax, ay, aw, ah = first
    bx, by, bw, bh = second
    return (ax - margin < bx + bw and bx < ax + aw + margin
            and ay - margin < by + bh and by < ay + ah + margin)


def _label_box(path, label_index):
    """The box of the label the canvas draws on a connection with this path."""
    bends = path[1:-1]
    if bends:
        x, y = bends[min(max(label_index, 0), len(bends) - 1)]
    else:
        (x0, y0), (x1, y1) = path[0], path[-1]
        x, y = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    width, height = CONNECTION_LABEL
    return (x - width / 2.0, y - height / 2.0, width, height)


def _path_is_clear(path, obstacles, margin):
    return not any(_segment_hits_box(start, end, box, margin)
                   for start, end in zip(path, path[1:]) for box in obstacles)


def _offsets(base):
    """Perpendicular offsets to try for one connection, nearest first.

    A connection already pushed to one side by its bundle keeps moving to that side, so
    neighbors in the bundle cannot swap places and land on each other.
    """
    yield base
    for step in range(1, DETOUR_TRIES + 1):
        if base > 0:
            yield base + step * DETOUR_STEP
        elif base < 0:
            yield base - step * DETOUR_STEP
        else:
            yield step * DETOUR_STEP
            yield -step * DETOUR_STEP


def route_group(group):
    """Add bends to the connections of `group` and its descendants that would overlap.

    Only connections with no bends are changed, so bends drawn by a user or exported by
    NiFi stay as they are, and routing a routed flow changes nothing. Returns the number
    of connections that received bends.
    """
    routed = 0
    boxes = _canvas_boxes(group)
    bundles = {}
    # Labels already on the canvas. A new route must not put its label on top of one.
    placed = [_label_box(_connection_path(c, boxes), c.get("labelIndex") or 0)
              for c in group.get("connections") or []
              if c.get("bends") and _connection_ends(c, boxes) is not None]
    for connection in group.get("connections") or []:
        ends = _connection_ends(connection, boxes)
        if ends is None:
            continue
        (source_owner, source_box), (destination_owner, _) = ends
        if source_owner == destination_owner:
            if not connection.get("bends"):
                # Far enough right that the label, centered on the first bend, clears the box.
                x, y, width, height = source_box
                right = x + width + CONNECTION_LABEL[0] / 2.0 + ROUTE_MARGIN * 2
                middle = y + height / 2.0
                connection["bends"] = [{"x": round(right), "y": round(middle - 40.0)},
                                       {"x": round(right), "y": round(middle + 40.0)}]
                connection["labelIndex"] = 0
                placed.append(_label_box(_connection_path(connection, boxes), 0))
                routed += 1
            continue
        bundles.setdefault(tuple(sorted((source_owner, destination_owner))), []).append(connection)

    for (first, second), members in sorted(bundles.items()):
        members.sort(key=lambda c: c.get("identifier") or "")
        # One perpendicular for the whole bundle, taken from the owners in sorted order, so
        # two connections in opposite directions are pushed to opposite sides.
        (ax, ay), (bx, by) = _center(boxes[first][1]), _center(boxes[second][1])
        length = ((bx - ax) ** 2 + (by - ay) ** 2) ** 0.5 or 1.0
        normal = (-(by - ay) / length, (bx - ax) / length)
        middle = ((ax + bx) / 2.0, (ay + by) / 2.0)
        obstacles = _obstacles(boxes, first, second)
        count = len(members)
        # Neighbors in a bundle sit far enough apart that their labels do not cover each
        # other: a label's width across a vertical line, its height across a horizontal one.
        label_width, label_height = CONNECTION_LABEL
        spacing = (abs(normal[0]) * label_width + abs(normal[1]) * label_height
                   + PARALLEL_SPACING)
        for index, connection in enumerate(members):
            if connection.get("bends"):
                continue
            base = (index - (count - 1) / 2.0) * spacing
            source, destination = _connection_path(connection, boxes)
            for offset in _offsets(base):
                bend = (round(middle[0] + normal[0] * offset), round(middle[1] + normal[1] * offset))
                path = [source, destination] if offset == 0 else [source, bend, destination]
                label = _label_box(path, 0)
                if (_path_is_clear(path, obstacles, ROUTE_MARGIN)
                        and not any(_boxes_overlap(label, box, ROUTE_MARGIN) for box in obstacles)
                        and not any(_boxes_overlap(label, box) for box in placed)):
                    placed.append(label)
                    if offset != 0:
                        connection["bends"] = [{"x": bend[0], "y": bend[1]}]
                        connection["labelIndex"] = 0
                        routed += 1
                    break

    for child in group.get("processGroups") or []:
        routed += route_group(child)
    return routed


def _connection_label(connection):
    source = connection.get("source") or {}
    destination = connection.get("destination") or {}
    return "%s -> %s" % (source.get("name") or source.get("id"),
                         destination.get("name") or destination.get("id"))


def _check_layout(report, group_path, group):
    """Warn about what hides part of the canvas: overlapping boxes and connections."""
    boxes = _canvas_boxes(group)
    owners = {}
    for owner, box in boxes.values():
        owners[owner] = box
    names = {c.get("identifier"): c.get("name") or c.get("identifier")
             for collection in CANVAS_SIZES for c in group.get(collection) or []}
    ordered = sorted(owners)
    for i, first in enumerate(ordered):
        for second in ordered[i + 1:]:
            if _boxes_overlap(owners[first], owners[second]):
                report.warn(
                    group_path,
                    "'%s' and '%s' overlap on the canvas. Move one of them so that both are "
                    "visible." % (names.get(first), names.get(second)),
                )

    drawn = {}
    labels_drawn = []
    for connection in group.get("connections") or []:
        path = _connection_path(connection, boxes)
        if path is None:
            continue
        (source_owner, _), (destination_owner, _) = _connection_ends(connection, boxes)
        label = _connection_label(connection)
        label_box = _label_box(path, connection.get("labelIndex") or 0)
        covered = [names.get(owner) for owner in ordered
                   if _boxes_overlap(label_box, owners[owner])]
        if covered:
            report.warn(
                group_path,
                "the label of connection %s covers part of %s. Move the connected components "
                "apart, leaving about %d px between their boxes, or move the component the "
                "label covers." % (label, ", ".join("'%s'" % n for n in covered), LABEL_CLEARANCE),
            )
        for other_label, other_box in labels_drawn:
            if _boxes_overlap(label_box, other_box):
                report.warn(
                    group_path,
                    "the labels of connections %s and %s cover each other. Add bends that "
                    "separate the two lines, or move the components apart."
                    % (other_label, label),
                )
        labels_drawn.append((label, label_box))
        if source_owner == destination_owner:
            if not connection.get("bends"):
                report.warn(
                    group_path,
                    "connection %s loops back to its source with no bends, so the canvas does "
                    "not show it. `kb.py normalize` adds bends for it." % label,
                )
            continue
        # A path drawn in the opposite direction covers the same pixels.
        key = min(tuple(path), tuple(reversed(path)))
        drawn.setdefault(key, []).append(label)
        obstacles = _obstacles(boxes, source_owner, destination_owner)
        if not _path_is_clear(path, obstacles, 0.0):
            report.warn(
                group_path,
                "connection %s passes through another component on the canvas, which hides "
                "part of it. `kb.py normalize` adds a bend around the component, or move the "
                "components apart." % label,
            )
    for labels in drawn.values():
        if len(labels) > 1:
            report.warn(
                group_path,
                "connections %s are drawn on top of each other. `kb.py normalize` adds bends "
                "that separate them." % ", ".join(labels),
            )


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
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as exc:
        raise KbError("%s is not valid JSON: %s" % (path, exc))
    root, envelope = root_group(doc)

    envelope.setdefault("externalControllerServices", {})
    envelope.setdefault("parameterContexts", {})
    envelope.setdefault("parameterProviders", {})
    envelope.setdefault("flowEncodingVersion", "1.0")
    # `latest` is metadata NiFi adds on download, not part of the flow. NiFi and NiFi Registry
    # 2.10 both accept a file that carries it; it is dropped to keep the committed flow clean.
    envelope.pop("latest", None)
    normalize_group(kb, root)
    routed = route_group(root)

    body = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
    target = Path(args.output) if args.output else path
    target.write_text(body, encoding="utf-8")
    print("Normalized %s -> %s" % (path, target))
    print("Filled the fields NiFi writes on export, using each component's own defaults from the")
    print("Knowledge Base, and removed the fields that belong to a NiFi download rather than to")
    print("the flow. Run `kb.py validate` next.")
    if routed:
        print("Added bends to %d connection(s) that would otherwise overlap another connection "
              "or run through a component on the canvas." % routed)
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
    pages = show.add_mutually_exclusive_group()
    pages.add_argument("--details", action="store_true", help="Print additionalDetails.md instead")
    pages.add_argument("--doc", action="store_true",
                       help="Print componentDocumentation.md instead (1.x Knowledge Base only)")

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
        flow = getattr(args, "flow", None)
        search_from = Path(flow).parent if flow else None
        kb = Kb(discover(args.kb, search_from, flow_nifi_version(flow) if flow else None))
        return handlers[args.command](kb, args)
    except KbError as exc:
        print("%s" % exc, file=sys.stderr)
        return 2
    except BrokenPipeError:
        return 0


if __name__ == "__main__":
    sys.exit(main())

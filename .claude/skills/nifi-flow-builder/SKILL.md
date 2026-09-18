---
name: nifi-flow-builder
description: Build, modify, review or debug Apache NiFi flow definition JSON using a version-matched NiFi Knowledge Base
  as the source of truth for component types, bundle coordinates, property keys, allowable values, relationships
  and controller service APIs. Use this whenever the user wants to create or edit a NiFi flow, add or reconfigure
  a processor or controller service, wire connections, work with a flow.json, versioned flow snapshot or
  process group export, or asks which NiFi processor or controller service fits a task - even when they never mention
  the Knowledge Base or NIFI_KB_PATH. Reach for it before writing any NiFi component type, bundle version or
  property key by hand, because those are exactly the details that get guessed wrong and then fail silently on import.
---

# Building and modifying NiFi flows

A NiFi Knowledge Base (KB) is a directory of component definitions gathered from one exact
running NiFi instance. It tells you the fully qualified type of every processor and controller
service, the bundle that ships it, the property keys NiFi actually matches on, the allowable
values, and which relationships exist. Use it instead of recalling NiFi from memory.

Recalled NiFi knowledge is version-blurred, and the mistakes it produces are quiet ones. A
flow that names `org.apache.nifi.processors.standard.JoltTransformJSON` imports into NiFi 2.x
as a ghost component, because the class moved to `org.apache.nifi.processors.jolt`. A
`ConsumeKafka` property written as `"Auto Offset Reset"`, the label the UI shows, is dropped
on load and the processor starts on the default, because the key NiFi matches on is
`auto.offset.reset`. Neither failure looks like a failure until the flow runs.

Which of the two a component wants is not a rule you can derive, and it moves between
versions: the same JoltTransformJSON that took `jolt-spec` in 1.x takes `Jolt Specification`
in 2.x. Look it up.

Throughout this document, `kb.py` is shorthand for `python <this-skill-directory>/scripts/kb.py`.
It reads only the KB's JSON files and never contacts a NiFi instance. Every command accepts
`--kb <path>`.

## 1. Locate the Knowledge Base

```bash
kb.py locate
```

This resolves the KB in order: the `--kb` argument, then `$NIFI_KB_PATH`, then a scan of the
current directory and the enclosing repository. It prints the path, the NiFi version, the
definition format and the component counts. A KB built from NiFi 1.x (`normalized-nifi-1x`)
answers every command below the same way as one built from 2.x (`native-nifi-2x`); where the
two versions differ, this document says so.

Report the NiFi version to the user before you build anything. Everything downstream is
correct only for that version, so the user needs to know which one you used.

Two cases need a decision rather than a guess:

- **Several KBs found.** `validate` and `normalize` take the one whose NiFi version equals the
  flow's own bundle version, and name it. Every other command lists them and stops: pick the
  one whose NiFi version matches the target flow, or ask. Do not default to the first.
- **No KB found.** Stop and say so. Ask the user for `NIFI_KB_PATH`, or point them at
  `qubership-nifi-kb-builder-tool` to build one against their NiFi. Do not carry on from
  memory: producing a plausible flow with wrong bundle coordinates is worse than producing
  nothing, because it fails at import time in the user's environment rather than here.

## 2. Match the Knowledge Base to the flow

When editing an existing flow, check that its bundle versions match the KB before you touch
it. `kb.py validate <flow.json>` reports a version mismatch as a single error rather than
flooding you with one per component.

A mismatch is a real fork in the road, so raise it rather than resolving it silently. Editing
a NiFi 1.x flow with a 2.x KB means either a deliberate upgrade (types and properties move) or
the wrong KB. Ask which.

For an upgrade, pass the target's KB with `--kb`. Without it, `validate` picks the KB that
matches the flow's current version, and a 1.x flow checked against the 1.x KB looks clean.

## 3. Find the components

Work down this ladder and stop as soon as you have what you need. The catalog holds hundreds
of components; `components/index.md` and `index.json` run to thousands of lines, so reading
them whole wastes most of your context before you have written anything.

| Need | Command |
| --- | --- |
| Which component does X? | `kb.py find <term>` or `kb.py find --tag <tag> --kind processor` |
| Exact property keys, defaults, allowable values | `kb.py props <Name>` |
| Full picture: description, relationships, attributes, use cases | `kb.py show <Name>` |
| Vendor prose for a tricky component | `kb.py show <Name> --details` |
| The full component page of a 1.x KB: dynamic properties, dynamic relationships, state | `kb.py show <Name> --doc` |
| Which service fits this service-typed property? | `kb.py services <Name> --property "<Property>"` |

`kb.py props` is usually enough to configure a component and costs a fraction of `show`. Reach
for `show` when you need the relationship semantics or the attributes a processor reads and
writes.

For Expression Language and RecordPath syntax, read `guides/expression-language-guide.md` and
`guides/record-path-guide.md` under the KB root. Grep them for the function you need rather
than reading them end to end.

`kb.py services` answers the question that is easiest to get wrong by reasoning. A property
that takes a controller service declares the API it requires, and each service declares the
APIs it implements, so the set of valid choices is a lookup. A `JsonRecordSetWriter` in a
`Record Reader` slot is a type error, not a matter of judgment.

## 4. Write the flow JSON

`references/flow-json.md` has the element templates and field meanings. Read it before writing
a flow from scratch. The rules below are the ones that decide whether NiFi accepts the result.

**Property keys are descriptor names, not UI labels.** The `JSON KEY` column of `kb.py props`
is what belongs in `properties`. Where a property's UI label differs, `props` prints the label
underneath and marks it as not the key. NiFi silently ignores an unmatched key, so this
mistake surfaces only as a component running on defaults.

**Copy bundle coordinates verbatim** from `kb.py props` or `find`. Group, artifact and version
all matter. The version is the NiFi version of the KB for built-in components, but bundled
extensions carry their own, so do not pattern-match it across components.

**Handle every relationship.** Each relationship a processor declares must either appear in
`autoTerminatedRelationships` or be carried by an outgoing connection, and never both. NiFi
refuses to start a processor with an unhandled relationship.

**Respect `inputRequirement`.** `INPUT_FORBIDDEN` components such as `GenerateFlowFile` cannot
have an incoming connection; `INPUT_REQUIRED` components must have one.

**Reference controller services by identifier.** A service-typed property holds the
`identifier` of a service defined in the flow, or a key in `externalControllerServices`. It
never holds a service name or type.

**Use Expression Language only where it is allowed.** The `EL` column gives the scope. In a
`NONE` property, `${...}` is stored as literal text. A 1.x KB uses the same scope names:
`ENVIRONMENT` is the scope 1.x calls "Variable Registry Only", which resolves variables,
environment variables and system properties but no FlowFile attributes. `UNDEFINED` marks a
1.x property that accepts Expression Language without declaring a scope.

**Keep secrets out of the file.** Properties marked `[SENSITIVE]` should reference a parameter
(`#{db.password}`), never an inline literal. A flow definition is a committed artifact.

**Give every component a random UUID as its identifier** - `kb.py ids <count>` prints them -
and set `groupIdentifier` to the identifier of the process group that contains it. A
connection's `source.id` and `destination.id` must match identifiers that exist in the flow.
Numbered placeholders import without complaint, so nothing stops you, but NiFi keeps the
identifier you write through import and re-export: it becomes the component's permanent
portable id, and a second flow built from the same numbers collides with the first in every
tool that compares flows.

**Do not invent properties.** Add a key outside the descriptor list only when `kb.py props`
reports `dynamic properties: yes`.

**Give a source processor a real schedule.** A processor whose `inputRequirement` is
`INPUT_FORBIDDEN` has no upstream queue to throttle it, so `schedulingPeriod: "0 sec"`
reschedules the task the instant it returns and burns a thread spinning on empty polls.
That cost is invisible in a test flow and obvious on a busy cluster. `kb.py props` prints
the component's default under `sched`; take it when it is non-zero, and choose one
yourself when it is not - several sources, `GetFile` among them, ship with a zero default.
A 1.x KB records no default of the component's own, so there `props` shows the framework's
`0 sec` for every processor and the choice is always yours.
100 millis is a common floor for a source that must react quickly, and seconds are normal
for a directory poll. A processor fed by a
connection is different: an empty queue already stops it being scheduled, so `0 sec` is
the normal setting there.

**Use a scheduling strategy from the `supported` list** that `kb.py props` prints under
`sched`. NiFi 2.x offers `TIMER_DRIVEN` and `CRON_DRIVEN`; 1.x adds `EVENT_DRIVEN` for the
processors that support it. Never write `PRIMARY_NODE_ONLY`, which 1.x deprecates and 2.0
removed: use `TIMER_DRIVEN` with `executionNode: "PRIMARY"`.

**Batch where the processor allows it.** `kb.py props` reports whether a component supports
batching. When it does, `runDurationMillis` above zero lets NiFi handle several FlowFiles
in a single session rather than paying the framework cost once per file, which is most of
the cost for a cheap operation like setting an attribute. 25 ms is the usual choice and
what NiFi's own UI offers. Leave it at 0 when a FlowFile must move through with the least
possible latency, or when the processor does not support batching - there the field has to
stay 0.

**Write every field the templates show, not just the ones that carry meaning.** NiFi's
importer deserializes the file into Java objects and reads enums, integers and maps without
null checks, so an omitted field arrives as null and throws. The upload fails with HTTP 500
and a stack trace in the NiFi log, long before any validation message. `propertyDescriptors`
is the usual casualty: it looks redundant next to `properties`, and leaving it out produces
`NullPointerException: ... because "propertyDescriptors" is null`. Do not hand-maintain this;
`kb.py normalize` fills it in from the catalog.

**Bind a parameter context whenever you use `#{...}`.** Set `parameterContextName` on the
process group to the name of an entry in `parameterContexts`, and declare every parameter you
reference. Without the binding NiFi marks the component invalid with "Property references one
or more Parameters but no Parameter Context is currently set on the Process Group".

**Parameters are the only substitution mechanism in NiFi 2.x.** The Variable Registry went
away in 2.0, and a `variables` map in a 2.x flow is accepted and then ignored, which makes it
worse than an error: the flow imports cleanly and every `${name}` reading a variable resolves
to nothing once it runs. If the user asks for variables, give them a parameter context.

In NiFi 1.x the Variable Registry still works but is deprecated: a `variables` map on a
process group is read, and `${name}` resolves a variable in any property whose EL scope is
`ENVIRONMENT` or `FLOWFILE_ATTRIBUTES`. Every new 1.x flow uses parameters. When you edit an
existing 1.x flow that uses variables, keep them, and use them for the change as well:
converting the flow to parameters is a major modification that the edit does not need.

When a property shows `only applies when ...`, it is dependent: NiFi hides it until the
controlling property holds one of the listed values. Do not set a dependent property whose
condition is not met, and do not treat one as required when it is inactive.

## 5. Normalize, then validate

```bash
kb.py normalize <flow.json>     # fill the fields NiFi writes on export
kb.py validate  <flow.json>     # check the result against the catalog
```

`normalize` fills in what you left out, taking each component's own defaults from the catalog
(`defaultPenaltyDuration`, `defaultYieldDuration`, `defaultBulletinLevel`, the scheduling
defaults) rather than a fixed guess, and generates `propertyDescriptors` for the properties
you set. It edits in place unless you pass `-o`. Running it on a flow that is already complete
changes nothing, so it is safe on a file you are editing.

A 1.x KB records none of those per-component defaults, so for 1.x `normalize` writes NiFi's
framework defaults instead. It also writes the fields of the KB's NiFi version and no others:
a 1.x flow keeps `variables: {}` on every group, and gets no `portFunction` on ports and no
`dynamic` in `propertyDescriptors`, because 1.x has neither field.

It also removes the top-level `latest`, which belongs to a NiFi download rather than to the
flow and which Registry snapshots never carry. For 2.x it removes an empty `variables` map
too, since nothing in 2.x reads one. A `variables` map with entries in it is left alone and,
for 2.x, reported by `validate`, because deleting it would throw away values the user meant
to keep.

`validate` checks types, bundles, property keys, allowable values, EL scope, dependent and
required properties, controller service API compatibility, relationship handling, input
requirements, scheduling strategies, parameter context binding, duplicate identifiers,
connection endpoints, and the fields the importer needs. It exits non-zero on any error.

Run them, fix what is reported, and run again until clean. Then say so and quote the counts.

Warnings deserve a decision rather than a reflex. Three are worth reading closely:

- A relationship the documentation names but the catalog does not declare. Some processors
  build their relationship set from property values - `ConsumeKafka` adds `parse failure` when
  `Processing Strategy` is `RECORD` - and the published definition lists only the static set.
  NiFi will refuse to start the processor with that relationship unhandled, so treat this as
  probably real.
- A sensitive property holding a literal, or an external service whose type cannot be checked.
  Either may be what the user wants.
- A required sensitive property with no value. NiFi leaves sensitive values out of a
  downloaded flow, so every export shows this. In a flow you write, reference a parameter.

## 6. Verify against a live NiFi and Registry when they are reachable

The catalog cannot tell you everything. Conditional relationships and import-time failures
only appear when real NiFi parses the file.

```bash
export NIFI_PKCS12_PASSWORD='<password>'
verify_live.py <flow.json> \
  --nifi-url https://nifi.example.com \
  --registry-url https://registry.example.com \
  --auth certificate --certificate-file <client.p12> --ca-file <ca.pem>
```

The two targets answer different questions, and either may be given on its own. The same
command works against NiFi 1.x and 2.x.

`--nifi-url` imports the flow into a temporary process group, reads back every component's
validation state, and deletes the group again. This is the check that knows what a component
is: a wrong property key, an unhandled relationship, a service reference that does not
resolve.

`--registry-url` stores the flow as version 1 of a temporary versioned flow, then removes it
along with the bucket it created. The Registry never loads the NARs, so it says nothing about
component validity; what it proves is that the file deserializes into the versioned-flow
model and survives a round trip through storage. That is the question a flow headed for
version control has to pass, and a NiFi-only check leaves it untested. Its rejections quote
the exact JSON path that would not parse, which makes it the faster way to find a
malformed field. Pass `--registry-bucket <name>` to use an existing bucket when the account
may not create one.

Run both when the flow is destined for a registry, and quote both results.

`--auth token` and `--auth cookie` read `NIFI_ACCESS_TOKEN` and
`NIFI_AUTHORIZATION_BEARER_COOKIE`; no secret is ever passed as an argument. One certificate
and one CA file serve both targets. Use `--insecure` only against a local development
instance.

Because it writes to both targets, ask before pointing it at anything shared, and say which
instances you used. Messages about disabled controller services are suppressed: services
always import disabled, so they say nothing about the flow.

## Reference

- `references/flow-json.md` - flow definition structure, element templates, field meanings.
- `scripts/kb.py` - catalog queries, `normalize`, and static `validate`.
- `scripts/verify_live.py` - import into a running NiFi and read back validation state, and
  store into a running NiFi Registry to prove the file is a valid snapshot.

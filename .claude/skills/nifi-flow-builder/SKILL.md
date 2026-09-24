---
name: nifi-flow-builder
description: Build, modify, review or debug Apache NiFi flow definition JSON using a version-matched NiFi Knowledge Base
  as the source of truth for component types, bundle coordinates, property keys, allowable values, relationships
  and controller service APIs. Use this whenever the user wants to create or edit a NiFi flow, add or reconfigure
  a processor or controller service, wire connections, work with a flow.json, versioned flow snapshot or
  process group export, or asks which NiFi processor or controller service fits a task - even when they never mention
  the Knowledge Base or NIFI_KB_PATH. Reach for it before writing any NiFi component type, bundle version or
  property key by hand, because those are exactly the details that get guessed wrong and then surface only at import
  or at run time.
---

# Building and modifying NiFi flows

A NiFi Knowledge Base (KB) is a directory of component definitions gathered from one exact
running NiFi instance. It tells you the fully qualified type of every processor and controller
service, the bundle that ships it, the property keys NiFi actually matches on, the allowable
values, and which relationships exist. Use it instead of recalling NiFi from memory.

Recalled NiFi knowledge is version-blurred, and the mistakes it produces show up only once
the flow is in NiFi. A flow that names `org.apache.nifi.processors.standard.JoltTransformJSON`
imports into NiFi 2.x as a ghost component, because the class moved to
`org.apache.nifi.processors.jolt`. A Kafka offset policy written under its UI label rather than
its key, `auto.offset.reset`, is read as a dynamic property. In 2.x, `ConsumeKafka` takes no
dynamic properties, so `"Auto Offset Reset"` leaves the processor invalid. In 1.x,
`ConsumeKafka_2_6` accepts dynamic properties and passes them to the Kafka client, so
`"Offset Reset"` validates and runs, and the processor consumes from the default offset. That
second failure looks like a working flow until someone checks which messages it read.

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
flow's directory (the current directory for a command that takes no flow, such as `locate`)
and the workspace root, two levels deep. The workspace root is
`$CLAUDE_PROJECT_DIR` when set, otherwise the git repository that contains the current
directory, otherwise the current directory. Nothing outside the workspace is scanned, so a KB
kept elsewhere needs `--kb` or `NIFI_KB_PATH`. It prints the path, the NiFi version, the
definition format, the component counts, and the platform: `qubership-nifi` when the KB has
`org.qubership.nifi` bundles, otherwise `Apache NiFi`. A KB built from NiFi 1.x (`normalized-nifi-1x`)
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
underneath and marks it as not the key. NiFi treats a key that matches no descriptor as a
dynamic property. On a component that takes no dynamic properties, that makes the component
invalid. On one that does, the component validates and runs, but the value is used as a
dynamic property, and the property you meant stays at its default.

**Copy bundle coordinates verbatim** from `kb.py props` or `find`. The group and artifact must
exist on the target NiFi. The version matters less on import: when a flow is imported from a
Registry or uploaded as a process group, NiFi replaces a version it does not have with the one
it has installed, as long as the group, artifact and type match. Still build for one target
NiFi and write the KB's version. The version is the NiFi version of the KB for built-in
components, but bundled extensions carry their own, so do not pattern-match it across
components.

**Handle every relationship.** Each relationship a processor declares must either appear in
`autoTerminatedRelationships` or be carried by an outgoing connection, and never both. NiFi
refuses to start a processor with an unhandled relationship.

**Respect `inputRequirement`.** `INPUT_FORBIDDEN` components such as `GenerateFlowFile` cannot
have an incoming connection; `INPUT_REQUIRED` components must have one.

**Reference controller services by identifier.** A service-typed property holds the
identifier of a controller service: the `identifier` of a service defined in the flow, or the
identifier of a service outside the exported group, which is a key in
`externalControllerServices`. It never holds a service name or type.

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
tool that compares flows. Where the numbers collide inside one NiFi, NiFi may also replace
them with generated identifiers, so the ids you wrote are gone after the import.

**Do not invent properties.** Add a key outside the descriptor list only when `kb.py props`
reports `dynamic properties: yes`.

**Take the schedule from the catalog.** Set `schedulingStrategy`, `schedulingPeriod`,
`executionNode` and `runDurationMillis` from the `sched` lines of `kb.py props`, following the
Processor field notes in `references/flow-json.md`. Those notes cover the supported strategies,
the period a source processor needs, and when to batch. `kb.py validate` reports an
unsupported strategy or an invalid period as an error, and warns about a source scheduled
every `0 sec` and about processors that could batch but do not.

**Write every field the templates show, not just the ones that carry meaning.** NiFi's
importer deserializes the file into Java objects and reads enums, integers and maps without
null checks, so an omitted field arrives as null and throws. The upload fails with HTTP 500
and a stack trace in the NiFi log, long before any validation message. `propertyDescriptors`
is the usual casualty: it looks redundant next to `properties`, and leaving it out produces
`NullPointerException: ... because "propertyDescriptors" is null`. Do not hand-maintain this;
`kb.py normalize` fills it in from the catalog.

**Bind a parameter context whenever you use `#{...}`.** Set `parameterContextName` on the
process group to the name of an entry in `parameterContexts`, and declare every parameter you
reference. The binding applies to that group only: a child group is not bound to its
parent's context, so set it on every group whose components reference a parameter. Without
the binding NiFi marks the component invalid with "Property references one or more
Parameters but no Parameter Context is currently set on the Process Group".

**NiFi 2.x does not support variables.** A 2.x import ignores a `variables` map, and every
`${name}` that referred to a variable evaluates to an empty string at runtime. If the user
asks for variables, give them a parameter context.

In NiFi 1.x the Variable Registry still works but is deprecated: a `variables` map on a
process group is read, and `${name}` resolves a variable in any property whose EL scope is
`ENVIRONMENT` or `FLOWFILE_ATTRIBUTES`. Every new 1.x flow uses parameters. When you edit an
existing 1.x flow that uses variables, keep them, and use them for the change as well:
converting the flow to parameters is a major modification that the edit does not need.

When a property shows `only applies when ...`, it is dependent: NiFi hides it until the
controlling property holds one of the listed values. Do not set a dependent property whose
condition is not met, and do not treat one as required when it is inactive.

**Write a script only when no component does the job.** Search the catalog first, by format
and by role: `kb.py find xlsx`, `kb.py find --tag writer`. When a script is still needed and
the data is records, write a scripted record component, such as a `ScriptedRecordSetWriter`,
so that `QueryRecord`, `ConvertRecord` and the other record processors can use it. A
standalone `ExecuteScript` or `ExecuteGroovyScript` is the last choice.

**Build a file format with a library, never from string templates.** XML, JSON, CSV and the
ZIP-based Office formats have escaping and structure rules that a hand-written template gets
wrong. Use `groovy.json.JsonOutput` for JSON, `groovy.xml.MarkupBuilder` or StAX for XML, and
Apache POI for XLS and XLSX.

**A script can use only the libraries its component can load.** Every NAR has its own class
loader, whose parent is the class loader of the NAR it depends on. A component therefore
loads classes from its own NAR, from that NAR's ancestors, and from the JDK, and from no other
NAR. A script in `ExecuteScript` cannot import the POI classes that `ExcelReader` uses in
`nifi-poi-nar`. To use a library from elsewhere, point the component's classpath property at
the jars on the NiFi host. `kb.py props` prints `takes: ...` under a property that takes files
or directories, and its description says whether they go on the classpath. Some classpath
properties declare no resource type, so read the descriptions as well. When
`guides/developer-guide.md` in the KB has the "NiFi Archives (NARs)" and "Per-Instance
ClassLoading" sections, grep for them for the details.

**Where the jars are depends on the NiFi installation, not on the catalog.** When
`kb.py locate` prints `Platform : qubership-nifi`, read `references/qubership-nifi.md`: the
image puts the libraries of some NARs in fixed directories, POI among them. On plain Apache
NiFi, ask the user for a directory of jars on the NiFi host. If there is none, use the JDK and
the libraries of the scripting NAR, and say so in your summary.

**Groovy `${...}` in `Script Body` is not Expression Language.** The property's EL scope is
`NONE`, so NiFi passes the text to the script engine as written, and `kb.py validate` does
not report it. Keep Groovy string interpolation where it makes the script easier to read.

## 5. Split large groups into child process groups

A process group with more than roughly 10-12 processors is hard to read on the NiFi canvas.
When a flow grows past that, give each stage of it a child process group, and connect the
children through ports. The threshold is a judgment call rather than a rule: a 13-processor
linear pipeline can stay flat, and a 9-processor group that holds two unrelated paths can
split. Do not create groups for a small flow, and do not nest deeper than two levels unless
the flow needs it. `kb.py validate` warns when one group holds more than 12 processors.

**Cut along stages a reader would name**: accept the request, transform, persist, respond,
downstream or asynchronous processing, error handling. Each child should do one job, hold
about 3-8 processors, and have 1-3 ports. Cut where few connections cross the boundary, and
keep a tight loop inside one group: a retry that routes back to its processor, a split with
its merge, a `Wait` with its `Notify`.

**The parent holds what the children share**: the controller services, the child groups,
the connections between their ports, and the labels. Ideally it holds no processors; one or
two that join the children are fine.

**Wire the children the way NiFi requires:**

- A connection belongs to the group that contains it, and it can join components of that
  group or a port of a direct child. To cross a boundary, connect to the child's port: the
  connection sits in the parent's `connections`, and the endpoint has `type` `INPUT_PORT` or
  `OUTPUT_PORT` and `groupId` set to the child's identifier. From the parent, FlowFiles enter
  a child through an input port and leave it through an output port.
- Port names are unique within a group. An input port name starts with `in_` and an output
  port name with `out_`, followed by a snake_case name for what passes through the port:
  `in_accepted`, `out_stored`, `out_failure`. Never name a port just `in` or `out`.
- Every port needs a connection inside its group and one in the parent. Otherwise NiFi marks
  the port invalid and cannot start it. A port of the root group is the exception on the outer side, because the import
  places the root group inside another group.
- A child group is not bound to its parent's parameter context. Every child that references
  `#{...}` sets `parameterContextName` itself, usually to the parent's context.
- A component can reference a controller service of its own group or of an ancestor, never
  one of a sibling group. Define a service the children share on the parent.
- Give each child that can fail an `out_failure` output port (`portFunction` `STANDARD`), and
  handle failures in one place, in the parent or in a child of their own, rather than with a
  logger in every child.
- `HandleHttpRequest` and `HandleHttpResponse` can sit in different children. They share the
  `StandardHttpContextMap` defined on the parent.

**Lay the children out in flow order** on the parent's canvas, and leave room for connection
labels. The canvas draws a label of about 224 x 100 px on every connection, centered on its
midpoint, so two connected components need about 260 px of free space between their boxes in
the direction of the connection. That gives these grid steps:

- child groups (384 x 176) side by side: about 700 px apart, as in x = 0, 700, 1400;
- processors (352 x 128) in a column: about 300 px apart vertically;
- columns of processors: about 640 px apart horizontally.

Inside a child, put the input ports on the top row and the output ports on the bottom row.
`kb.py validate` warns when a label would cover a component or another label. Where you can, place components so that a connection runs between neighbors rather
than across a third component. The canvas draws a connection with no `bends` as a straight
line between the two boxes, so two connections between the same pair of components cover
each other, and a line across a third component is hidden behind it. `kb.py normalize` adds
bends for both cases (section 6), so you do not have to compute them. Keep any bends a user
drew.

`references/flow-json.md` has a worked example of a child group with its ports and the three
connections that wire it.

## 6. Normalize, then validate

```bash
kb.py normalize <flow.json>     # fill the fields NiFi writes on export
kb.py validate  <flow.json>     # check the result against the catalog
```

`normalize` fills in what you left out, taking each component's own defaults from the catalog
(`defaultPenaltyDuration`, `defaultYieldDuration`, `defaultBulletinLevel`, the scheduling
defaults) rather than a fixed guess, and generates `propertyDescriptors` for the properties
you set. It edits in place unless you pass `-o`. Running it on a flow that is already complete
changes nothing, so it is safe on a file you are editing.

`normalize` also adds `bends` to connections that would be hard to see on the canvas. It
spreads two or more connections between the same two components (or between ports of the
same two child groups) to either side of the straight line. It routes a line that would cross
a third component around it, and gives a connection from a processor back to itself a loop
to the right. Each bend is placed so that the connection's label clears other components and
other labels. It changes only connections that have no bends, so bends drawn by a user or
exported by NiFi stay as they are. It never moves a component: when two connected components
are too close for the label, `validate` reports it, and the fix is to move them apart.

A 1.x KB records none of those per-component defaults, so for 1.x `normalize` writes NiFi's
framework defaults instead. It also writes the fields of the KB's NiFi version and no others:
a 1.x flow keeps `variables: {}` on every group, and gets no `portFunction` on ports and no
`dynamic` in `propertyDescriptors`, because 1.x has neither field.

It also removes the top-level `latest`, which belongs to a NiFi download rather than to the
flow. For 2.x it removes an empty `variables` map
too, since nothing in 2.x reads one. A `variables` map with entries in it is left alone and,
for 2.x, reported by `validate`, because deleting it would throw away values the user meant
to keep.

`validate` checks types, bundles, property keys, allowable values, EL scope, dependent and
required properties, controller service API compatibility, relationship handling, input
requirements, scheduling strategies, parameter context binding, duplicate identifiers,
connection endpoints, and the fields the importer needs. For child groups it also checks that
each connection stays in its group or reaches a port of a direct child, that port names are
unique and prefixed, that every port is connected on both sides, and that each service
reference points to the component's own group or an ancestor. On the canvas of each group, it
warns about components whose boxes overlap, connections drawn on top of each other, lines
that cross another component, labels that cover a component or another label, and loops with
no bends. It exits non-zero on any error.

Run them, fix what is reported, and run again until clean. Then say so and quote the counts.

Warnings deserve a decision rather than a reflex. Three are worth reading closely:

- A relationship the documentation names but the catalog does not declare. Some processors
  build their relationship set from property values - `ConsumeKafka` adds `parse failure` when
  `Processing Strategy` is `RECORD` - and the published definition lists only the static set.
  NiFi will refuse to start the processor with that relationship unhandled, so treat this as
  probably real.
- A sensitive property holding a literal value rather than a `#{...}` parameter reference, or
  an external service whose type cannot be checked. Either may be what the user wants.
- A required sensitive property with no value. NiFi leaves sensitive values out of a
  downloaded flow, so every export shows this. In a flow you write, reference a parameter.

## 7. Verify against a live NiFi and Registry when they are reachable

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

`--nifi-url` imports the flow into a temporary process group, reads back the validation state
of every processor, controller service, and port in it and in its child groups, and deletes
the group again. This is the check that knows what a component
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
and one CA file serve both targets.

Because it writes to both targets, ask before pointing it at anything shared, and say which
instances you used. Messages about disabled controller services are suppressed: services
always import disabled, so they say nothing about the flow.

## Reference

- `references/flow-json.md` - flow definition structure, element templates, field meanings.
- `references/qubership-nifi.md` - what the qubership-nifi image adds to Apache NiFi: the
  library directories a script can put on its classpath, and a tested XLSX record writer.
- `scripts/kb.py` - catalog queries, `normalize`, and static `validate`.
- `scripts/verify_live.py` - import into a running NiFi and read back validation state, and
  store into a running NiFi Registry to prove the file is a valid snapshot.

# Flow definition JSON structure

The structure NiFi produces when you download a flow definition, and the structure it accepts
on import. Field names below are exact. Values shown are the defaults NiFi itself writes, so
copying them gives you a file that round-trips cleanly.

Look up every `type`, `bundle`, property key and relationship name with `kb.py` rather than
from this page. The templates here fix the shape; the Knowledge Base fixes the content.

## Omitted fields fail the import, not the validation

NiFi deserializes a flow definition into Java objects and reads enums, integers and maps
without null checks. A field you leave out arrives as `null` and throws inside the importer,
so the API answers `HTTP 500` with a stack trace in log and no useful message. This
happens before any component is validated, which makes it look unrelated to the flow's
contents.

Observed on NiFi 2.10 as mandatory, and written by every NiFi 1.28.1 export as well:

| Element | Fields the importer dereferences |
| --- | --- |
| Processor | `propertyDescriptors`, `position`, `bulletinLevel`, `penaltyDuration`, `yieldDuration`, `runDurationMillis`, `concurrentlySchedulableTaskCount`, `schedulingStrategy`, `executionNode` |
| Controller service | `propertyDescriptors` |
| Connection | `labelIndex`, `zIndex`, `selectedRelationships`, `backPressureObjectThreshold`, `backPressureDataSizeThreshold`, `flowFileExpiration` |

Treat that table as evidence for a habit rather than a checklist to apply: write every field
the templates below show, and run `kb.py normalize` to fill any you missed. The list is what
one version happened to dereference, so a different NiFi may want more.

## Envelope

A downloaded flow definition wraps the root process group:

```json
{
  "flowContents": { },
  "externalControllerServices": { },
  "parameterContexts": { },
  "parameterProviders": { },
  "flowEncodingVersion": "1.0"
}
```

A file you downloaded from NiFi carries one more key, `"latest": false`. It is metadata
about the download, not part of the flow. NiFi and NiFi Registry 2.10 both accept a file that
carries it, but leave it out of a flow you commit. `kb.py normalize` removes it if you started
from a NiFi download.

A flow version exported from NiFi Registry has the same top-level `flowContents`, plus
Registry metadata such as `snapshotMetadata`, and the scripts accept it too. `kb.py` and
`verify_live.py` both require a top-level `flowContents` and reject any other shape, such as a
bare process group object or a `VersionedFlowSnapshotEntity` that nests the snapshot under
`versionedFlowSnapshot`.

## Process group

`flowContents` and every entry in `processGroups` share this shape. Empty collections are
written out rather than omitted. A child group in `processGroups` is a full process group
object: it sets `groupIdentifier` to its parent's identifier and binds its own
`parameterContextName`. "Wiring a child group" under "Input and output ports" shows how its
ports connect to the parent.

```json
{
  "identifier": "<uuid>",
  "instanceIdentifier": "<uuid>",
  "name": "My Group",
  "comments": "",
  "position": {"x": 0.0, "y": 0.0},
  "processGroups": [],
  "remoteProcessGroups": [],
  "processors": [],
  "inputPorts": [],
  "outputPorts": [],
  "connections": [],
  "labels": [],
  "funnels": [],
  "controllerServices": [],
  "defaultFlowFileExpiration": "0 sec",
  "defaultBackPressureObjectThreshold": 10000,
  "defaultBackPressureDataSizeThreshold": "1 GB",
  "flowFileConcurrency": "UNBOUNDED",
  "flowFileOutboundPolicy": "STREAM_WHEN_AVAILABLE",
  "parameterContextName": null,
  "componentType": "PROCESS_GROUP"
}
```

Set `parameterContextName` to a key of `parameterContexts` if anything in the group references
a parameter; leave it `null` otherwise. The binding applies to that group only. A child group
is not bound to its parent's context, so every group whose components reference a parameter
sets `parameterContextName` itself.

NiFi 2.x does not support variables. A 2.x import ignores a `variables` map, and a `${name}`
that referred to a variable evaluates to an empty string at runtime. Use parameters instead:
declare them in a parameter context and reference them as `#{name}`. NiFi 1.x still reads the
`variables` map that its exports write on every process group, but deprecates it.

## Processor

```json
{
  "identifier": "<uuid>",
  "instanceIdentifier": "<uuid>",
  "name": "PutDb",
  "comments": "",
  "position": {"x": 100.0, "y": 100.0},
  "type": "org.apache.nifi.processors.standard.PutDatabaseRecord",
  "bundle": {
    "group": "org.apache.nifi",
    "artifact": "nifi-standard-nar",
    "version": "2.10.0"
  },
  "properties": {
    "Record Reader": "<controller-service-identifier>",
    "Statement Type": "INSERT",
    "Table Name": "${table.name}"
  },
  "propertyDescriptors": {},
  "style": {},
  "schedulingPeriod": "0 sec",
  "schedulingStrategy": "TIMER_DRIVEN",
  "executionNode": "ALL",
  "penaltyDuration": "30 sec",
  "yieldDuration": "1 sec",
  "bulletinLevel": "WARN",
  "runDurationMillis": 0,
  "concurrentlySchedulableTaskCount": 1,
  "autoTerminatedRelationships": ["failure", "retry", "success"],
  "scheduledState": "ENABLED",
  "retryCount": 10,
  "retriedRelationships": [],
  "backoffMechanism": "PENALIZE_FLOWFILE",
  "maxBackoffPeriod": "10 mins",
  "componentType": "PROCESSOR",
  "groupIdentifier": "<parent-process-group-identifier>"
}
```

Field notes:

- `properties` keys are descriptor names from the `JSON KEY` column of `kb.py props`, not the
  labels shown in the UI. A property left at its default may be omitted or set to `null`.
- `propertyDescriptors` mirrors `properties`, one entry per key you set:
  `{"name": ..., "displayName": ..., "identifiesControllerService": false,
  "sensitive": false, "dynamic": false}`. Take the values from the Knowledge Base: `name` is
  the `JSON KEY` from `kb.py props`, and `displayName`, `sensitive` and
  `identifiesControllerService` (true for a service-typed property) come from that property's
  descriptor in the KB. It looks redundant, but the importer reads it
  and omitting the field, or setting it to `null`, fails the upload with HTTP 500. An
  empty map is accepted, and so is one that covers only some of the properties - NiFi
  does not read the contents at import. Write the full mirror anyway, because that is
  what exporting the flow again produces and anything less shows up as diff noise. A 1.x export goes
  further and lists a descriptor for every property the component declares, set or not,
  sometimes with a `resourceDefinition`; NiFi 1.28.1 accepts the shorter map that
  `normalize` writes.
- `dynamic` marks a property as user-defined rather than one the component declares, so
  it is `true` exactly for the keys that `kb.py props` does not list, which you may add only
  when it reports `dynamic properties: yes`. NiFi 2.x
  writes it; 1.x exports have no such field, and `kb.py normalize` follows the Knowledge
  Base version rather than adding a key the target NiFi never produces. Let `normalize`
  generate all of this from the catalog rather than writing it by hand.
- `schedulingStrategy` must be one of the strategies `kb.py props` lists under `sched`:
  `TIMER_DRIVEN` or `CRON_DRIVEN` in 2.x, plus `EVENT_DRIVEN` in 1.x for a processor that
  supports it. `PRIMARY_NODE_ONLY` is deprecated in 1.x and gone in 2.x; express it as
  `executionNode: "PRIMARY"` instead. A `CRON_DRIVEN` period is a Quartz cron expression,
  such as `* * * * * ?`.
- A `TIMER_DRIVEN` `schedulingPeriod` is a number with a time unit, such as `0 sec`,
  `100 millis` or `5 mins`. A bare `0` imports, but NiFi marks the processor invalid because
  "Scheduling Period is not a valid time duration", and the UI does not accept it.
  `TIMER_DRIVEN` with `schedulingPeriod: "0 sec"` means run as often as possible. That is
  the right setting for a processor fed by a connection, because an empty queue stops it
  being scheduled anyway, and usually the wrong one for a source, a processor whose
  `inputRequirement` is `INPUT_FORBIDDEN`. With no upstream queue, nothing throttles a
  source: the task is rescheduled the instant it returns and holds a thread polling nothing.
  The cost is invisible in a test flow and obvious on a busy cluster. `kb.py props` prints
  the component's default period under `sched`; take it when it is not zero, and choose one
  yourself when it is, since several sources, `GetFile` among them, ship with a zero
  default. A 1.x KB records no default of the component's own, so there `props` shows the
  framework's `0 sec` for every processor and the choice is always yours. 100 millis is a
  common floor for a source that must react quickly, and seconds are normal for a directory
  poll. Two kinds of source differ from that rule; see the next two notes.
- A few sources belong at `0 sec`, because each one waits for the next event inside its own
  client or server: a zero period costs no idle CPU, and any other period only adds
  latency. They are exactly the types in the table below, which comes from a review of their
  source code and from measurements on a qubership-nifi container. A type that the target
  NiFi lacks does not matter. `kb.py validate` warns about every other source scheduled at
  `0 sec`, including a `Listen*` or `Consume*` processor that the table leaves out.

  | Type | Present in |
  | --- | --- |
  | `org.apache.nifi.processors.standard.ListenHTTP` | 1.x, 2.x |
  | `org.apache.nifi.processors.standard.ListenFTP` | 1.x, 2.x |
  | `org.apache.nifi.snmp.processors.ListenTrapSNMP` | 1.x, 2.x |
  | `org.apache.nifi.processors.websocket.ListenWebSocket` | 1.x, 2.x |
  | `org.apache.nifi.processors.websocket.ConnectWebSocket` | 1.x, 2.x |
  | `org.apache.nifi.processors.slack.ListenSlack` | 2.x |
  | `org.apache.nifi.kafka.processors.ConsumeKafka` | 2.x |
  | `org.apache.nifi.processors.kafka.pubsub.ConsumeKafka_2_6`, `ConsumeKafkaRecord_2_6` | 1.x; 2.x on qubership-nifi |
  | `org.apache.nifi.processors.kafka.pubsub.ConsumeKafka_1_0`, `ConsumeKafka_2_0`, `ConsumeKafkaRecord_1_0`, `ConsumeKafkaRecord_2_0` | 1.x |
  | `org.apache.nifi.jms.processors.ConsumeJMS` | 1.x, 2.x |
  | `org.apache.nifi.amqp.processors.ConsumeAMQP` | 1.x, 2.x |
  | `org.apache.nifi.processors.mqtt.ConsumeMQTT` | 1.x, 2.x |
  | `org.apache.nifi.processors.azure.eventhub.ConsumeAzureEventHub` | 1.x, 2.x |
  | `org.apache.nifi.processors.azure.eventhub.GetAzureEventHub` | 1.x, 2.x |
  | `org.apache.nifi.processors.aws.kinesis.stream.ConsumeKinesisStream` | 1.x, 2.x |
  | `org.apache.nifi.processors.aws.kinesis.ConsumeKinesis` | 2.x |
  | `org.apache.nifi.processors.box.ConsumeBoxEvents` | 2.x |
  | `org.apache.nifi.processors.twitter.ConsumeTwitter` | 1.x, 2.x |

- `HandleHttpRequest`, and every `Listen*` processor that the table leaves out (`ListenTCP`,
  `ListenUDP`, `ListenSyslog` and others), gets `50 millis`. Such a listener polls its
  internal queue on every run, so at `0 sec` it runs continually: on qubership-nifi, an idle
  `HandleHttpRequest` adds up to 10% idle CPU usage. The period adds up to 50 ms of latency per
  request. `HandleHttpRequest` takes one request per run, so one concurrent task handles
  about 20 requests per second; raise `concurrentlySchedulableTaskCount` for more. Set
  `0 sec` only when the user asks for the lowest latency.
- `runDurationMillis` trades latency for throughput on processors that support batching,
  which `kb.py props` reports. Above zero, NiFi handles several FlowFiles in one session
  instead of paying the framework cost once per file, which is most of the cost of a cheap
  operation such as setting an attribute. 25 is the usual value and the one NiFi's UI
  offers. Leave it at 0 when a FlowFile must pass through with the least possible latency.
  It must stay 0 on a processor that does not support batching.
- `executionNode` is `ALL` or `PRIMARY`. Use `PRIMARY` when `kb.py props` reports the
  processor as primary-node-only, otherwise it runs on every node of a cluster.
- `scheduledState` is `ENABLED`, `DISABLED` or `RUNNING`.
- `autoTerminatedRelationships` and outgoing connections together must cover every
  relationship the processor declares, with no overlap. "Declares" can exceed what the
  catalog lists: a processor may build its relationship set from property values, so
  `ConsumeKafka` gains `parse failure` once `Processing Strategy` is `RECORD`. `kb.py validate`
  warns when the documentation names a relationship the catalog does not, and
  `verify_live.py` settles it.

## Controller service

```json
{
  "identifier": "<uuid>",
  "instanceIdentifier": "<uuid>",
  "name": "JsonTreeReader",
  "comments": "",
  "type": "org.apache.nifi.json.JsonTreeReader",
  "bundle": {
    "group": "org.apache.nifi",
    "artifact": "nifi-record-serialization-services-nar",
    "version": "2.10.0"
  },
  "properties": {},
  "propertyDescriptors": {},
  "controllerServiceApis": [],
  "scheduledState": "ENABLED",
  "bulletinLevel": "WARN",
  "componentType": "CONTROLLER_SERVICE",
  "groupIdentifier": "<parent-process-group-identifier>"
}
```

A service is available to components in its own process group and every group beneath it.
Define a service that several child groups share on their common parent. `scheduledState`
is `ENABLED` or `DISABLED`; a service must be enabled before a component referencing it can
start.

## Connection

```json
{
  "identifier": "<uuid>",
  "instanceIdentifier": "<uuid>",
  "name": "",
  "source": {
    "id": "<source-component-identifier>",
    "type": "PROCESSOR",
    "groupId": "<process-group-identifier>",
    "name": "Gen"
  },
  "destination": {
    "id": "<destination-component-identifier>",
    "type": "PROCESSOR",
    "groupId": "<process-group-identifier>",
    "name": "PutDb"
  },
  "labelIndex": 0,
  "zIndex": 0,
  "selectedRelationships": ["success"],
  "backPressureObjectThreshold": 10000,
  "backPressureDataSizeThreshold": "1 GB",
  "flowFileExpiration": "0 sec",
  "prioritizers": [],
  "bends": [],
  "loadBalanceStrategy": "DO_NOT_LOAD_BALANCE",
  "partitioningAttribute": "",
  "loadBalanceCompression": "DO_NOT_COMPRESS",
  "componentType": "CONNECTION",
  "groupIdentifier": "<process-group-identifier>"
}
```

`type` on either endpoint is `PROCESSOR`, `INPUT_PORT`, `OUTPUT_PORT` or `FUNNEL`. Crossing a
process group boundary means connecting to a port, not to a component inside the other group.
`selectedRelationships` applies to a processor source and must name relationships that
processor declares.

### Bends and the canvas

`bends` is a list of points, `[{"x": 692, "y": -152}]`, in the canvas coordinates of the
group that holds the connection. The canvas draws the connection from the source box through
each bend in order to the destination box. `labelIndex` is the index of the bend that carries
the connection's label. With no bends, the line runs straight between the two boxes.

A component's `position` is the top-left corner of its box. The NiFi 2.x canvas draws the
boxes at these sizes, in pixels:

| Component | Width x height |
| --- | --- |
| Processor | 352 x 128 |
| Process group, remote process group | 384 x 176 |
| Input or output port | 240 x 48 |
| Funnel | 48 x 48 |
| Connection label (approximate; its height grows with the rows it shows) | 224 x 100 |

The label is centered on the bend that `labelIndex` names, or on the midpoint of a straight
connection. Two connected components therefore need about 260 px of free space between their
boxes, or the label covers one of them.

A connection to a port of a child group ends at the child group's box on the parent's canvas,
so two connections between ports of the same two child groups share one line unless bends
separate them. `kb.py normalize` adds those bends, and `kb.py validate` warns about any
overlap it finds.

## Input and output ports

```json
{
  "identifier": "<uuid>",
  "instanceIdentifier": "<uuid>",
  "name": "in_accepted",
  "comments": "",
  "position": {"x": 0.0, "y": 0.0},
  "type": "INPUT_PORT",
  "concurrentlySchedulableTaskCount": 1,
  "scheduledState": "ENABLED",
  "allowRemoteAccess": false,
  "portFunction": "STANDARD",
  "componentType": "INPUT_PORT",
  "groupIdentifier": "<process-group-identifier>"
}
```

Set `type` and `componentType` to `OUTPUT_PORT` for an output port. `portFunction` is
`STANDARD` or `FAILURE`. It exists from NiFi 2.0: a 1.x port has no such field, and a 1.x
flow leaves it out.

A connection into or out of a child process group names the child's port as its endpoint,
with `groupId` set to the child group's identifier. Without that `groupId`, the upload fails
with HTTP 500 on NiFi 1.28.1 and 2.10.0; `kb.py normalize` fills it in.

Port names are unique within a group. Input port names start with `in_` and output port
names with `out_`, as in `in_accepted` and `out_stored`.

### Wiring a child group

The parent below holds a processor `Receive`, a processor `Respond`, and a child group
`Store` that receives FlowFiles on `in_accepted` and emits them on `out_stored`. Three
connections wire it; placeholders stand for the identifiers.

```json
{
  "identifier": "<parent-id>",
  "name": "Parent",
  "processors": [
    {"identifier": "<receive-id>", "name": "Receive", "...": "..."},
    {"identifier": "<respond-id>", "name": "Respond", "...": "..."}
  ],
  "processGroups": [
    {
      "identifier": "<store-id>",
      "name": "Store",
      "groupIdentifier": "<parent-id>",
      "parameterContextName": "app-context",
      "inputPorts": [
        {"identifier": "<in-accepted-id>", "name": "in_accepted", "type": "INPUT_PORT",
         "componentType": "INPUT_PORT", "groupIdentifier": "<store-id>", "...": "..."}
      ],
      "outputPorts": [
        {"identifier": "<out-stored-id>", "name": "out_stored", "type": "OUTPUT_PORT",
         "componentType": "OUTPUT_PORT", "groupIdentifier": "<store-id>", "...": "..."}
      ],
      "processors": [
        {"identifier": "<insert-id>", "name": "Insert", "groupIdentifier": "<store-id>",
         "...": "..."}
      ],
      "connections": [
        {"source": {"id": "<in-accepted-id>", "type": "INPUT_PORT", "groupId": "<store-id>"},
         "destination": {"id": "<insert-id>", "type": "PROCESSOR", "groupId": "<store-id>"},
         "selectedRelationships": [], "groupIdentifier": "<store-id>", "...": "..."},
        {"source": {"id": "<insert-id>", "type": "PROCESSOR", "groupId": "<store-id>"},
         "destination": {"id": "<out-stored-id>", "type": "OUTPUT_PORT", "groupId": "<store-id>"},
         "selectedRelationships": ["success"], "groupIdentifier": "<store-id>", "...": "..."}
      ]
    }
  ],
  "connections": [
    {"source": {"id": "<receive-id>", "type": "PROCESSOR", "groupId": "<parent-id>"},
     "destination": {"id": "<in-accepted-id>", "type": "INPUT_PORT", "groupId": "<store-id>"},
     "selectedRelationships": ["success"], "groupIdentifier": "<parent-id>", "...": "..."},
    {"source": {"id": "<out-stored-id>", "type": "OUTPUT_PORT", "groupId": "<store-id>"},
     "destination": {"id": "<respond-id>", "type": "PROCESSOR", "groupId": "<parent-id>"},
     "selectedRelationships": [], "groupIdentifier": "<parent-id>", "...": "..."}
  ]
}
```

- The two connections that cross the boundary live in the parent's `connections`. Each names
  the port by its own identifier, and gives the child's identifier as the port's `groupId`.
- The connections from `in_accepted` and to `out_stored` live in the child's `connections`.
- A connection whose source is a port leaves `selectedRelationships` empty, because a port
  has no relationships.
- `Store` sets `parameterContextName` itself, since the parent's binding does not reach it.
- `Insert` can reference a controller service defined on `Parent` or on `Store`, but not one
  defined in a sibling of `Store`.

## Funnels

A funnel merges several connections into one and has no configuration of its own:

```json
{
  "identifier": "<uuid>",
  "instanceIdentifier": "<uuid>",
  "position": {"x": 0.0, "y": 0.0},
  "componentType": "FUNNEL",
  "groupIdentifier": "<process-group-identifier>"
}
```

Connect to and from it with `type: "FUNNEL"` on the connection endpoint. A funnel has no
relationships, so a connection whose source is a funnel leaves `selectedRelationships` empty.

## Labels

A label is a note on the canvas and affects nothing at runtime:

```json
{
  "identifier": "<uuid>",
  "instanceIdentifier": "<uuid>",
  "position": {"x": 0.0, "y": 0.0},
  "label": "Loads orders into the warehouse",
  "width": 300.0,
  "height": 60.0,
  "zIndex": 0,
  "style": {"font-size": "12px"},
  "componentType": "LABEL",
  "groupIdentifier": "<process-group-identifier>"
}
```

## Parameter contexts and secrets

```json
{
  "parameterContexts": {
    "app-context": {
      "name": "app-context",
      "description": "",
      "parameters": [
        {
          "name": "db.password",
          "description": "",
          "sensitive": true,
          "value": null
        }
      ],
      "inheritedParameterContexts": []
    }
  }
}
```

Bind the context by setting `parameterContextName` on the process group to the context's name:

```json
{"flowContents": {"parameterContextName": "app-context"}}
```

Reference a parameter from a property as `#{db.password}`. Sensitive parameter values are not
exported, so `value` is `null` in a downloaded flow and should stay `null` in a flow you
write.

Without the binding, every referencing component is invalid with "Property references one or
more Parameters but no Parameter Context is currently set on the Process Group" - the
parameter context existing in the file is not enough on its own.

Parameter references work in any property, including those whose Expression Language scope is
`NONE`, because NiFi substitutes them before evaluating the property. Sensitivity must match
in both directions: a sensitive property can reference only a sensitive parameter, and a
non-sensitive property only a non-sensitive one.

## External controller services

When a flow references a service defined outside the exported group, NiFi records the
reference instead of the definition:

```json
{
  "externalControllerServices": {
    "18a13b92-2d58-445d-9983-c8745deefcae": {
      "identifier": "18a13b92-2d58-445d-9983-c8745deefcae",
      "name": "SharedDbcpPool"
    }
  }
}
```

The type is absent, so `kb.py validate` cannot confirm from this file alone that the service
implements the API the referencing property needs. Where the check can happen depends on where
the service is defined:

- In another file of the same repository, such as the parent group's flow or a standalone
  controller service export: read the service's `type` there and check it with
  `kb.py services <Name> --property "<Property>"`.
- In another flow in the registry, or only in the target NiFi: nothing you hold has the type.
  NiFi resolves the reference when the flow is imported into a group that can see the service,
  and a reference it cannot resolve leaves the referencing component invalid.

## Identifiers

`identifier` is the stable, portable ID and the one connections and service references point
at. `instanceIdentifier` is the ID of the live component the flow was exported from; it is
optional in a flow you write by hand, and NiFi assigns a new one on import.

Generate a random UUID for each one - `kb.py ids <count>` prints as many as you need. NiFi
mints a random UUID for every component it creates, so this is also what a real export
looks like.

Numbered placeholders such as `00000000-0000-0000-0000-000000000010` do import, but they
are not a convention local to the file. NiFi keeps an
identifier you write when nothing else in the target uses it: it survives the import and
comes back unchanged in the next export. Reuse the same numbered set in a second flow and
two things can go wrong. The two flows become indistinguishable to anything that keys on the
identifier - registry version diffs, external service references, flow comparison tooling.
And where the identifiers collide within one NiFi, NiFi may deduplicate them and generate
new ones, so the next export no longer carries the identifiers you wrote. `kb.py validate` warns when it sees one.

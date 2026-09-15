# Flow definition JSON structure

The structure NiFi produces when you download a flow definition, and the structure it accepts
on import. Field names below are exact. Values shown are the defaults NiFi itself writes, so
copying them gives you a file that round-trips cleanly.

Look up every `type`, `bundle`, property key and relationship name with `kb.py` rather than
from this page. The templates here fix the shape; the Knowledge Base fixes the content.

## Omitted fields fail the import, not the validation

NiFi deserializes a flow definition into Java objects and reads enums, integers and maps
without null checks. A field you leave out arrives as `null` and throws inside the importer,
so the API answers `HTTP 500` with a stack trace in `nifi-app.log` and no useful message. This
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
about the download, not part of the flow: NiFi Registry snapshots never contain it, and no
importer reads it. Leave it out, and one file loads into both products. `kb.py normalize`
removes it if you started from a NiFi download.

`kb.py validate` also accepts a bare process group object and a registry snapshot that nests
`flowContents` under `snapshot`.

## Process group

`flowContents` and every entry in `processGroups` share this shape. Empty collections are
written out rather than omitted.

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
a parameter; leave it `null` otherwise. Child groups inherit the nearest ancestor's binding.

There is no `variables` key in 2.x. The Variable Registry was removed in NiFi 2.0, so a 2.x
export has no such field and a 2.x import ignores one you supply. That last part is what makes
it worth stating: a flow carrying variables loads without complaint, and every `${name}` that
reads one silently evaluates to nothing at runtime. Parameters replace them - declare a
parameter context and reference it as `#{name}`.

A 1.x export writes a `variables` map of names to values on every process group, empty when
the group has none, and NiFi 1.x reads it on import. Variables are deprecated in 1.x: a new
flow uses parameters, and an existing flow keeps the variables it has.

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
  "sensitive": false, "dynamic": false}`. It looks redundant, but the importer reads it
  and omitting the field, or setting it to `null`, fails the upload with HTTP 500. An
  empty map is accepted, and so is one that covers only some of the properties - NiFi
  does not read the contents at import. Write the full mirror anyway, because that is
  what a re-export produces and anything less shows up as diff noise. A 1.x export goes
  further and lists a descriptor for every property the component declares, set or not,
  sometimes with a `resourceDefinition`; NiFi 1.28.1 accepts the shorter map that
  `normalize` writes.
- `dynamic` marks a property as user-defined rather than one the component declares, so
  it is `true` exactly for the keys you added under `dynamic properties: yes`. NiFi 2.x
  writes it; 1.x exports have no such field, and `kb.py normalize` follows the Knowledge
  Base version rather than adding a key the target NiFi never produces. Let `normalize`
  generate all of this from the catalog rather than writing it by hand.
- `schedulingStrategy` must be one of the strategies `kb.py props` lists under `sched`:
  `TIMER_DRIVEN` or `CRON_DRIVEN` in 2.x, plus `EVENT_DRIVEN` in 1.x for a processor that
  supports it. `PRIMARY_NODE_ONLY` is deprecated in 1.x and gone in 2.x; express it as
  `executionNode: "PRIMARY"` instead. A `CRON_DRIVEN` period is a Quartz cron expression,
  such as `* * * * * ?`.
  `TIMER_DRIVEN` with `schedulingPeriod: "0 sec"` means run as often as possible. That is
  the right setting for a processor fed by a connection, because an empty queue stops it
  being scheduled anyway, and the wrong one for a source: with no upstream queue, nothing
  throttles it and it holds a thread polling nothing. Give a source the default `kb.py
  props` prints, or an explicit period.
- `runDurationMillis` trades latency for throughput on processors that support batching,
  which `kb.py props` reports. Above zero, NiFi handles several FlowFiles per session
  instead of paying the framework cost per file; 25 is the usual value. It must stay 0 on
  a processor that does not support batching.
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
Define shared services on the root group. `scheduledState` is `ENABLED` or `DISABLED`; a
service must be enabled before a component referencing it can start.

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

## Ports, funnels and labels

```json
{
  "identifier": "<uuid>",
  "name": "In",
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

Funnels are minimal: `identifier`, `position`, `componentType: "FUNNEL"` and
`groupIdentifier`. Labels add `label`, `width` and `height`, and affect nothing at runtime.

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
write. Set the real value in the target NiFi.

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

The type is absent, so neither NiFi nor `kb.py validate` can confirm the service implements
the API the referencing property needs. That check moves to import time, where an unresolved
reference leaves the component invalid.

## Identifiers

`identifier` is the stable, portable id and the one connections and service references point
at. `instanceIdentifier` is the id of the live component the flow was exported from; it is
optional in a flow you write by hand, and NiFi assigns a new one on import.

Generate a random UUID for each one - `kb.py ids <count>` prints as many as you need. NiFi
mints a random UUID for every component it creates, so this is also what a real export
looks like.

Numbered placeholders such as `00000000-0000-0000-0000-000000000010` do import, which is
why they are tempting, but they are not a convention local to the file. NiFi keeps the
identifier you write: it survives the import and comes back unchanged in the next export.
Reuse the same numbered set in a second flow and the two become indistinguishable to
anything that keys on the identifier - registry version diffs, external service references,
flow comparison tooling. `kb.py validate` warns when it sees one.

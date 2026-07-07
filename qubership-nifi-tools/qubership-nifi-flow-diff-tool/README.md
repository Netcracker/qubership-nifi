# qubership-nifi-flow-diff-tool

`qubership-nifi-flow-diff-tool` is a Maven plugin that classifies the differences between two Apache NiFi Registry
versioned flow exports and can restore the technical identifiers NiFi rewrites when a flow is copied or recreated.

NiFi rewrites `instanceIdentifier`, the root process group `identifier`, the matching child `groupIdentifier`
back-references, and the `source`/`destination` `groupId` back-references on connections whose endpoints sit directly
under the root, every time a flow is copied or recreated, even when nothing functional changed. Committed exports then
produce diffs dominated by that technical changes, which buries the significant ones in code review. The plugin
matches components by identity, sorts every difference into one of three categories, and can rewrite the working copy so
only significant changes remain in the diff.

## Prerequisites

- Java - JDK 21
- Maven - Maven 3.x

## Comparison logic

Table below describes categories used when comparing two flow versions.

| Field / location                                               | Category      |
|----------------------------------------------------------------|---------------|
| `propertyDescriptors`, `snapshotMetadata`                      | Ignored       |
| `instanceIdentifier` of a component                            | Technical     |
| `instanceIdentifier` of a connection endpoint (`id` unchanged) | Technical     |
| `groupId` of a connection endpoint that back-references root   | Technical     |
| `identifier` of the root process group                         | Technical     |
| `groupIdentifier` of a direct child of the root                | Technical     |
| `bundle.version` of a NiFi bundle object                       | Environmental |
| `controllerServiceApis` of a controller service                | Environmental |
| `flowEncodingVersion` top-level scalar                         | Environmental |
| everything else                                                | Significant   |

- **Technical** - a NiFi-generated identifier change with no functional meaning;
  counted, and the only category reverted.
- **Environmental** - export metadata or runtime packaging,
  such as bundle versions, `controllerServiceApis` and `flowEncodingVersion`; reported, never reverted.
- **Significant** - a real flow-content change; the catch-all category.

A connection endpoint `groupId` is technical only when it references the root group on both sides.
When the endpoint is a port inside a process group, its `groupId` is that group's stable identifier,
so a real change there stays significant.

A connection endpoint `instanceIdentifier` is technical only when the endpoint `id` is unchanged. When the `id` changes
the connection points to a different component, so every endpoint field, `instanceIdentifier` included, is significant.

Dynamic properties definitions stored in `propertyDescriptors` are ignored for simplicity. Only property values are
compared both for regular and dynamic properties.

## Usage

The plugin exposes three goals. `diff` and `git-diff` are read-only and emit a report; `git-revert-technical` rewrites
the working copy in place. Exit code is `0` whenever a goal runs - finding changes is never a failure. A non-zero code
signals an execution error, such as malformed input, an unresolvable branch, or a duplicate identifier.

### diff

Compares two inputs and reports the differences. Each input is a directory tree or a single flow file, given as a
relative path (resolved against the Maven `basedir`) or an absolute path. Both sides must be the same kind.

```shell
mvn -q org.qubership.nifi:qubership-nifi-flow-diff-tool:<version>:diff \
  -Dbaseline=<baselineDirOrFile> \
  -Dtarget=<targetDirOrFile> \
  -Dformat=md \
  -Doutput=diff.md
```

### git-diff

Compares the working tree against a committed baseline read through JGit. With `branch` set, the baseline is the tip of
that branch rather than `HEAD`. The tip is deliberate: NiFi flows are replaced, not merged, so the report answers what a
replacement would introduce.

```shell
mvn -q org.qubership.nifi:qubership-nifi-flow-diff-tool:<version>:git-diff -Dpath=<dirOrFile> -Dbranch=main
```

### git-revert-technical

Rewrites the working copy so its technical fields match `HEAD`, leaving environmental and significant changes untouched.
It prints a per-file summary of the reverted counts. Writes are atomic and are skipped when the file changed between
read and write, so a concurrent edit is never clobbered.

```shell
mvn -q org.qubership.nifi:qubership-nifi-flow-diff-tool:<version>:git-revert-technical -Dpath=<dirOrFile>
```

The table below describes the plugin parameters:

| Parameter          | Goal                           | Default | Description                                                                    |
|--------------------|--------------------------------|---------|--------------------------------------------------------------------------------|
| `baseline`         | diff                           | -       | Required. Baseline directory or single flow file.                              |
| `target`           | diff                           | -       | Required. Target directory or single flow file.                                |
| `path`             | git-diff, git-revert-technical | -       | Required. Directory or single flow file, relative to the Maven `basedir`.      |
| `branch`           | git-diff                       | `HEAD`  | Branch whose tip is the baseline.                                              |
| `format`           | diff, git-diff                 | `text`  | Report format: `text`, `json`, or `md`.                                        |
| `output`           | diff, git-diff                 | -       | Report file. Required for `json` and `md`; `text` defaults to standard output. |
| `max-value-length` | diff, git-diff                 | `200`   | Value truncation budget for `text` and `md`; `0` disables truncation.          |
| `show-technical`   | diff, git-diff                 | `false` | Also list technical changes in the report, marked `[tech]`, for debugging.     |
| `skip-malformed`   | all                            | `false` | Continue past a malformed candidate file instead of failing.                   |

## Output formats

Three renderers share one diff model; only presentation differs. Every reported path uses `/` separators on every
operating system.

- **`text`** - a grouped tree per flow for the console: process groups as breadcrumb headers, each component once, field
  changes beneath. Component lines lead with a short type code (`[P]` processor, `[CS]` controller service, and so on),
  and the report opens with a legend of the codes it uses. Technical changes appear only in the counts header. A
  connection endpoint that now points to a different component collapses to one line,
  `destination: [OP] out (<id>) -> [FN] Funnel (<id>)`, rather than a line per endpoint field.
- **`md`** - a heading and table per process group, with the full component type in a `Type` column. A changed
  connection
  endpoint collapses to one row, with the full type names in the value cells. Good for pasting into a pull request.
- **`json`** - flat and machine-readable, for CI gating. Each change is a self-contained record with a canonical `path`
  and a `pathSegments` array. Technical changes are counted in `counts` and `totals` but not listed. The report carries
  a `schemaVersion` as its forward-compatibility contract.

By default, technical changes are only counted, not listed. Set `show-technical` to list them too: `text` and `md` mark
each one `[tech]`, and `json` includes it as a change with `category` `technical`.
Use it to see exactly which fields the tool reverts.

Component coordinates read as pairs in the `text` and `md` formats: a `position` renders as a single
`position: (x, y) -> (x, y)` line, and connection `bends` render as `bends: [(x, y), ...] -> [...]`, dropping the always
implied `x`/`y` keys. A `position` change is still counted per coordinate, so a move that shifts both `x` and `y` counts
as two changes even though it prints as one line. The `json` report is unchanged: it keeps `position/x` and `position/y`
as separate entries and `bends` as its raw array.

A whole added or removed flow is counted in `totals.addedFlows` and `totals.removedFlows`, not folded into
`significant`. A consumer gating on any reportable flow change checks
`significant > 0 || addedFlows > 0 || removedFlows > 0`.

Long or multiline property values are escaped to a single line (`\n`, `\r`, `\t`) and truncated to `max-value-length`
in the `text` and `md` formats; the `json` report keeps the full raw value. An empty-string value renders as `(empty)`
in `text` and as a blank cell in `md`, so it is not mistaken for a truncated line; a missing value renders as `(absent)`
in `text`.

### Text example

```text
Types: P = processor, CS = controller service
flows/BulkDataLoader1.json  (significant: 5, environmental: 1, technical: 61)
  BulkDataLoader1
    position: (2352.0, 1104.0) -> (2336.0, 1210.0)
    [P] LoadStaging
      properties/Batch Size: 1000 -> 5000
      [env] bundle/version: 2.0.0 -> 2.1.0
    [CS] RecordWriter
      properties/Pretty Print JSON: false -> true
    + [P] GenerateFlowFile (added)
  other attributes
    parameterContexts / Database / Max Connections
      value: 10 -> 20
```

### Markdown example

````markdown
## flows/BulkDataLoader1.json

Significant: 5, Environmental: 1, Technical: 61

### BulkDataLoader1

| Component | Type | Field | Baseline | Target |
| --- | --- | --- | --- | --- |
| _(group)_ | PROCESS_GROUP | `position` | (2352.0, 1104.0) | (2336.0, 1210.0) |
| `LoadStaging` | PROCESSOR | `properties/Batch Size` | 1000 | 5000 |
| `LoadStaging` | PROCESSOR | [env] `bundle/version` | 2.0.0 | 2.1.0 |
| `RecordWriter` | CONTROLLER_SERVICE | `properties/Pretty Print JSON` | false | true |
| `GenerateFlowFile` | PROCESSOR | _(added)_ | _(absent)_ | _(present)_ |

### other attributes

| Component | Type | Field | Baseline | Target |
| --- | --- | --- | --- | --- |
| `parameterContexts / Database / Max Connections` | _(parameter)_ | `value` | 10 | 20 |
````

### JSON example

```json
{
    "schemaVersion": 1,
    "flows": [
        {
            "path": "flows/BulkDataLoader1.json",
            "counts": {
                "technical": 61,
                "environmental": 1,
                "significant": 5
            },
            "changes": [
                {
                    "path": "BulkDataLoader1/position/x",
                    "pathSegments": [
                        "BulkDataLoader1",
                        "position",
                        "x"
                    ],
                    "category": "significant",
                    "baselineValue": 2352.0,
                    "targetValue": 2336.0
                },
                {
                    "path": "BulkDataLoader1/LoadStaging/properties/Batch Size",
                    "pathSegments": [
                        "BulkDataLoader1",
                        "LoadStaging",
                        "properties",
                        "Batch Size"
                    ],
                    "category": "significant",
                    "identifier": "3f2a...",
                    "componentType": "PROCESSOR",
                    "name": "LoadStaging",
                    "baselineValue": "1000",
                    "targetValue": "5000"
                },
                {
                    "path": "BulkDataLoader1/LoadStaging/bundle/version",
                    "pathSegments": [
                        "BulkDataLoader1",
                        "LoadStaging",
                        "bundle",
                        "version"
                    ],
                    "category": "environmental",
                    "identifier": "3f2a...",
                    "componentType": "PROCESSOR",
                    "name": "LoadStaging",
                    "baselineValue": "2.0.0",
                    "targetValue": "2.1.0"
                },
                {
                    "path": "BulkDataLoader1/RecordWriter/properties/Pretty Print JSON",
                    "pathSegments": [
                        "BulkDataLoader1",
                        "RecordWriter",
                        "properties",
                        "Pretty Print JSON"
                    ],
                    "category": "significant",
                    "identifier": "4e5f...",
                    "componentType": "CONTROLLER_SERVICE",
                    "name": "RecordWriter",
                    "baselineValue": "false",
                    "targetValue": "true"
                },
                {
                    "path": "BulkDataLoader1/GenerateFlowFile",
                    "pathSegments": [
                        "BulkDataLoader1",
                        "GenerateFlowFile"
                    ],
                    "category": "significant",
                    "change": "added",
                    "identifier": "9b1f...",
                    "componentType": "PROCESSOR",
                    "name": "GenerateFlowFile"
                },
                {
                    "path": "parameterContexts/Database/parameters/Max Connections/value",
                    "pathSegments": [
                        "parameterContexts",
                        "Database",
                        "parameters",
                        "Max Connections",
                        "value"
                    ],
                    "category": "significant",
                    "baselineValue": "10",
                    "targetValue": "20"
                }
            ]
        }
    ],
    "addedFlows": [],
    "removedFlows": [],
    "totals": {
        "technical": 61,
        "environmental": 1,
        "significant": 5,
        "addedFlows": 0,
        "removedFlows": 0
    }
}
```

# qubership-nifi-flow-diff-core

The library behind [`qubership-nifi-flow-diff-tool`](../qubership-nifi-flow-diff-tool/README.md), a Maven plugin, and
[`qubership-nifi-flow-diff-cli`](../qubership-nifi-flow-diff-cli/README.md), a command-line tool. It classifies the
differences between two NiFi Registry versioned flows or two NiFi flow exports (exported via `Download flow definition`
or via `/process-groups/{id}/download` API) and can restore the technical identifiers NiFi rewrites when a flow is
copied or recreated.

NiFi rewrites `instanceIdentifier`, the root process group `identifier`, the matching child `groupIdentifier`
back-references, and the `source`/`destination` `groupId` back-references on connections whose endpoints sit directly
under the root, every time a flow is copied or recreated, even when nothing functional changed. Committed exports then
produce diffs dominated by that technical changes, which buries the significant ones in code review. This library
matches components by identity, sorts every difference into one of four categories, and can rewrite the working copy so
only significant changes remain in the diff.

Both frontends share this code, so a report is identical whichever one produced it. Pick the frontend by where you
run it: the Maven plugin inside a build, the command-line tool anywhere else.

## Comparison logic

Table below describes categories used when comparing two flow versions.

| Field / location                                                        | Category      |
|-------------------------------------------------------------------------|---------------|
| `propertyDescriptors`, `snapshotMetadata`, `latest`, `flow`, `bucket`   | Ignored       |
| `instanceIdentifier` of a component                                     | Technical     |
| `instanceIdentifier` of a connection endpoint (`id` unchanged)          | Technical     |
| `groupId` of a connection endpoint that back-references root            | Technical     |
| `identifier` of the root process group                                  | Technical     |
| `groupIdentifier` of a direct child of the root                         | Technical     |
| `bundle.version` of a NiFi bundle object                                | Environmental |
| `controllerServiceApis` of a controller service                         | Environmental |
| `flowEncodingVersion` top-level scalar                                  | Environmental |
| everything else                                                         | Significant   |

- **Technical** - a NiFi-generated identifier change with no functional meaning;
  counted, and the only category reverted.
- **Environmental** - export metadata or runtime packaging,
  such as bundle versions, `controllerServiceApis` and `flowEncodingVersion`; reported, never reverted.
- **Significant** - a real flow-content change; the catch-all category.

A connection endpoint `groupId` change is technical only when the connection sits directly under the
root group and the `groupId` references the root group in both versions. If it does not reference the
root group in either version, the change is significant: it mirrors change of parent group for the referenced
component.

When reverting technical changes, the tool rewrites a `groupId` whenever the working-side value
references the root group, restoring it alongside the root process group `identifier`. It is deliberately broader
than the classifier, because it needs to eliminate technical changes created by copy or recreate operations, which
may be present along with significant changes.

A connection endpoint `instanceIdentifier` is technical only when the endpoint `id` is unchanged. When the `id` changes
the connection points to a different component, so every endpoint field, `instanceIdentifier` included, is significant.

Dynamic properties definitions stored in `propertyDescriptors` are ignored for simplicity. Only property values are
compared both for regular and dynamic properties.

The `flow` and `bucket` sections are written by Git-based flow storage. They record where the flow is stored, along
with registry-generated timestamps, version counters and permissions, so they are accepted and ignored rather than
compared.

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

By default, technical changes are only counted, not listed. Set the show-technical option to list them too: `text` and
`md` mark each one `[tech]`, and `json` includes it as a change with `category` `technical`.
Use it to see exactly which fields the tool reverts.

Component coordinates read as pairs in the `text` and `md` formats: a `position` renders as a single
`position: (x, y) -> (x, y)` line, and connection `bends` render as `bends: [(x, y), ...] -> [...]`, dropping the always
implied `x`/`y` keys. A `position` change is still counted per coordinate, so a move that shifts both `x` and `y` counts
as two changes even though it prints as one line. The `json` report is unchanged: it keeps `position/x` and `position/y`
as separate entries and `bends` as its raw array.

A whole added or removed flow is counted in `totals.addedFlows` and `totals.removedFlows`, not folded into
`significant`. A consumer gating on any reportable flow change checks
`significant > 0 || addedFlows > 0 || removedFlows > 0`.

Long or multiline property values are escaped to a single line (`\n`, `\r`, `\t`) and truncated to the max-value-length
option in the `text` and `md` formats; the `json` report keeps the full raw value. An empty-string value renders as
`(empty)` in `text` and as a blank cell in `md`, so it is not mistaken for a truncated line; a missing value renders as
`(absent)` in `text`.

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

## Library usage

A frontend needs two packages: `org.qubership.nifi.flowdiff.service` for the entry points below, and
`org.qubership.nifi.flowdiff.error` for the failures they raise. Relative paths resolve against the `basedir`
argument, which is `${project.basedir}` for the Maven plugin and the working directory for the command line.

```java
FlowDiffService service = new FlowDiffService();

// Two paths on disk. Both sides must be directories or both single files.
ReportModel model = service.diff(basedir, new File("flows/base"), new File("flows/head"), false);

// The working tree against a committed baseline. The path must be relative.
ReportModel gitModel = service.gitDiff(basedir, "flows", "main", false);

// Render it. The hint is how the calling frontend spells its own output option, quoted back
// to the user when a format that needs an output file was requested without one.
new ReportEmitter(new ReportOptions("md", new File("diff.md"), 200, false), "--output <file>").emit(model);

// Rewrite the working copy so its technical fields match HEAD, reporting each file as it is rewritten. Passing a
// listener rather than reading summaryLines() afterwards means a run that fails part way still names what it changed.
RevertSummary summary = new TechnicalRevertService().revertGit(basedir, "flows", false, System.out::println);
System.out.println(summary.totalLine());
```

Failures are unchecked and name the file, path, or option at fault. All three live in
`org.qubership.nifi.flowdiff.error`, so one import covers everything a frontend catches:

| Exception                    | Raised for                                                                                                                                      |
|------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `FlowParseException`         | malformed JSON, a flow export without `flowContents`, or a duplicate or missing identifier                                                      |
| `FlowDiffInputException`     | a missing path, a baseline and target of different kinds, an unresolvable revision, or a Git-mode path that is absolute or outside the worktree |
| `FlowDiffExecutionException` | an unknown format, a missing output file, or a report that cannot be written                                                                    |
| `IOException`                | a flow that cannot be read or written                                                                                                           |

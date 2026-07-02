# qubership-nifi-flow-diff-tool

`qubership-nifi-flow-diff-tool` is a Maven plugin that classifies the differences between two Apache NiFi versioned
flow exports and can restore the technical identifiers NiFi rewrites when a flow is copied or recreated.

NiFi rewrites `instanceIdentifier`, the root process group `identifier`, the matching child `groupIdentifier`
back-references, and the `source`/`destination` `groupId` back-references on connections whose endpoints sit directly
under the root, every time a flow is copied or recreated, even when nothing functional changed. Committed exports then
produce diffs dominated by that technical churn, which buries the significant changes in code review. The plugin matches
components by identity (never by array order), sorts every difference into one of three categories, and can rewrite the
working copy so only significant changes remain in the diff.

A connection endpoint `groupId` is technical only when it references the root group on both sides. When the endpoint is a
port inside a sub-group, its `groupId` is that sub-group's stable identifier, so a real change there stays significant.

A connection endpoint `instanceIdentifier` is technical only when the endpoint `id` is unchanged. When the `id` changes
the connection points to a different component, so every endpoint field, `instanceIdentifier` included, is significant.

- **technical** - a NiFi-generated identifier change with no functional meaning; counted, and the only category reverted.
- **environmental** - export metadata or runtime packaging, such as bundle versions and `flowEncodingVersion`; reported,
  never reverted.
- **significant** - a real flow-content change; the catch-all category.

## Prerequisites

- Java - JDK 21
- Maven - Maven 3.x

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
replace would introduce.

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

| Parameter          | Goal                            | Default | Description                                                                     |
|--------------------|---------------------------------|---------|---------------------------------------------------------------------------------|
| `baseline`         | diff                            | -       | Required. Baseline directory or single flow file.                               |
| `target`           | diff                            | -       | Required. Target directory or single flow file.                                 |
| `path`             | git-diff, git-revert-technical  | -       | Required. Directory or single flow file, relative to the Maven `basedir`.       |
| `branch`           | git-diff                        | `HEAD`  | Branch whose tip is the baseline.                                               |
| `format`           | diff, git-diff                  | `text`  | Report format: `text`, `json`, or `md`.                                         |
| `output`           | diff, git-diff                  | -       | Report file. Required for `json` and `md`; `text` defaults to standard output.  |
| `max-value-length` | diff, git-diff                  | `200`   | Value truncation budget for `text` and `md`; `0` disables truncation.           |
| `show-technical`   | diff, git-diff                  | `false` | Also list technical changes in the report, marked `[tech]`, for debugging.      |
| `skip-malformed`   | all                             | `false` | Continue past a malformed candidate file instead of failing.                    |

## Output formats

Three renderers share one diff model; only presentation differs. Every reported path uses `/` separators on every
operating system.

- **text** - a grouped tree per flow for the console: process groups as breadcrumb headers, each component once, field
  changes beneath. Component lines lead with a short type code (`[P]` processor, `[CS]` controller service, and so on),
  and the report opens with a legend of the codes it uses. Technical changes appear only in the counts header. A
  connection endpoint that now points to a different component collapses to one line,
  `destination: [OP] out (<id>) -> [FN] Funnel (<id>)`, rather than a line per endpoint field.
- **md** - a heading and table per process group, with the full component type in a `Type` column. A changed connection
  endpoint collapses to one row, with the full type names in the value cells. Good for pasting into a pull request.
- **json** - flat and machine-readable, for CI gating. Each change is a self-contained record with a canonical `path`
  and a `pathSegments` array. Technical changes are counted in `counts` and `totals` but not listed. The report carries
  a `schemaVersion` as its forward-compatibility contract.

By default technical changes are only counted, not listed. Set `show-technical` to list them too: `text` and `md` mark
each one `[tech]`, and `json` includes it as a change with `category` `technical`. Use it to see exactly which fields the
tool reverts.

A whole added or removed flow is counted in `totals.addedFlows` and `totals.removedFlows`, not folded into
`significant`. A consumer gating on any reportable flow change checks
`significant > 0 || addedFlows > 0 || removedFlows > 0`.

Long or multiline property values are escaped to a single line (`\n`, `\r`, `\t`) and truncated to `max-value-length`
in the `text` and `md` formats; the `json` report keeps the full raw value.

# qubership-nifi-flow-diff-tool

`qubership-nifi-flow-diff-tool` is a Maven plugin that classifies the differences between two Apache NiFi versioned
flow exports and can restore the technical identifiers NiFi rewrites when a flow is copied or recreated.

NiFi rewrites `instanceIdentifier`, the root process group `identifier`, and the matching child `groupIdentifier`
back-references every time a flow is copied or recreated, even when nothing functional changed. Committed exports then
produce diffs dominated by that technical churn, which buries the significant changes in code review. The plugin matches
components by identity (never by array order), sorts every difference into one of three categories, and can rewrite the
working copy so only significant changes remain in the diff.

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
| `skip-malformed`   | all                             | `false` | Continue past a malformed candidate file instead of failing.                    |

## Output formats

Three renderers share one diff model; only presentation differs. Every reported path uses `/` separators on every
operating system.

- **text** - a grouped tree per flow for the console: process groups as breadcrumb headers, each component once, field
  changes beneath. Component lines lead with a short type code (`[P]` processor, `[CS]` controller service, and so on),
  and the report opens with a legend of the codes it uses. Technical changes appear only in the counts header.
- **md** - a heading and table per process group, with the full component type in a `Type` column. Good for pasting into
  a pull request.
- **json** - flat and machine-readable, for CI gating. Each change is a self-contained record with a canonical `path`
  and a `pathSegments` array. Technical changes are counted in `counts` and `totals` but not listed. The report carries
  a `schemaVersion` as its forward-compatibility contract.

A whole added or removed flow is counted in `totals.addedFlows` and `totals.removedFlows`, not folded into
`significant`. A consumer gating on any reportable flow change checks
`significant > 0 || addedFlows > 0 || removedFlows > 0`.

Long or multiline property values are escaped to a single line (`\n`, `\r`, `\t`) and truncated to `max-value-length`
in the `text` and `md` formats; the `json` report keeps the full raw value.

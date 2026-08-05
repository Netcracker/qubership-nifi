# qubership-nifi-flow-diff-tool

`qubership-nifi-flow-diff-tool` is a Maven plugin that classifies the differences between two NiFi Registry
versioned flow or two NiFi flow exports (exported via `Download flow definition` or via
`/process-groups/{id}/download` API) and can restore the technical identifiers NiFi rewrites
when a flow is copied or recreated.

The classification rules, the report formats, and worked examples of each are documented once in
[`qubership-nifi-flow-diff-core`](../qubership-nifi-flow-diff-core/README.md), the library this plugin is a frontend
for. This page covers only how to drive it from Maven. To run the same thing outside a build, use
[`qubership-nifi-flow-diff-cli`](../qubership-nifi-flow-diff-cli/README.md), which takes the same parameters as
command-line options and produces identical reports.

## Prerequisites

- Java - JDK 21+
- Maven - Maven 3.x

## Usage

The plugin exposes three goals. `diff` and `git-diff` are read-only and emit a report; `git-revert-technical` rewrites
the working copy in place. Exit code is `0` whenever a goal runs - finding changes is never a failure. A non-zero code
signals an execution error, such as malformed input, an unresolvable branch, or a duplicate identifier.

To use plugin prefix instead of full name, add pluginGroup `org.qubership.nifi.plugins` in `settings.xml`:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
    <!--...-->
    <pluginGroups>
        <pluginGroup>org.qubership.nifi.plugins</pluginGroup>
    </pluginGroups>
    <!--...-->
</settings>
```

See Maven
[documentation](https://maven.apache.org/guides/introduction/introduction-to-plugin-prefix-mapping.html#configuring-maven-to-search-for-plugins)
for more details.

### diff

Compares two inputs and reports the differences. Each input is a directory tree or a single flow file, given as a
relative path (resolved against the Maven `basedir`) or an absolute path. Both sides must be the same kind.

```shell
mvn -q org.qubership.nifi.plugins:qubership-nifi-flow-diff-tool:<version>:diff \
  -Dbaseline=<baselineDirOrFile> \
  -Dtarget=<targetDirOrFile> \
  -Dformat=md \
  -Doutput=diff.md
```

```shell
mvn -q nifi-flow-diff:<version>:diff \
  -Dbaseline=<baselineDirOrFile> \
  -Dtarget=<targetDirOrFile> \
  -Dformat=md \
  -Doutput=diff.md
```

### git-diff

Compares the working tree against a committed baseline read through JGit. The `branch` parameter names the baseline
revision and defaults to `HEAD`. JGit resolves it, so it accepts any of: `HEAD`, a complete or abbreviated SHA-1, a
complete reference name (`refs/...`), or a short name under `refs/heads`, `refs/tags`, or `refs/remotes`. For a branch,
the baseline is its tip, not the merge-base: NiFi flows are replaced, not merged, so the report answers what a
replacement would introduce.

```shell
mvn -q org.qubership.nifi.plugins:qubership-nifi-flow-diff-tool:<version>:git-diff -Dpath=<dirOrFile> -Dbranch=main
```

```shell
mvn -q nifi-flow-diff:<version>:git-diff -Dpath=<dirOrFile> -Dbranch=main
```

### git-revert-technical

Rewrites the working copy so its technical fields match `HEAD`, leaving environmental and significant changes untouched.
It prints a per-file summary of the reverted counts. Writes are atomic and are skipped when the file changed between
read and write, so a concurrent edit is never clobbered.

```shell
mvn -q org.qubership.nifi.plugins:qubership-nifi-flow-diff-tool:<version>:git-revert-technical -Dpath=<dirOrFile>
```

```shell
mvn -q nifi-flow-diff:<version>:git-revert-technical -Dpath=<dirOrFile>
```

The table below describes the plugin parameters. The `CLI option` column names the equivalent option in
[`qubership-nifi-flow-diff-cli`](../qubership-nifi-flow-diff-cli/README.md).

| Parameter        | Maven property     | CLI option           | Goal                           | Default | Description                                                                    |
|------------------|--------------------|----------------------|--------------------------------|---------|--------------------------------------------------------------------------------|
| `baseline`       | `baseline`         | `--baseline`         | diff                           | -       | Required. Baseline directory or single flow file.                              |
| `target`         | `target`           | `--target`           | diff                           | -       | Required. Target directory or single flow file.                                |
| `path`           | `path`             | `--path`             | git-diff, git-revert-technical | -       | Required. Directory or single flow file, relative to the Maven `basedir`.      |
| `branch`         | `branch`           | `--branch`           | git-diff                       | `HEAD`  | Baseline revision resolved by JGit (see the git-diff section for forms).       |
| `format`         | `format`           | `--format`           | diff, git-diff                 | `text`  | Report format: `text`, `json`, or `md`.                                        |
| `output`         | `output`           | `--output`           | diff, git-diff                 | -       | Report file. Required for `json` and `md`; `text` defaults to standard output. |
| `maxValueLength` | `max-value-length` | `--max-value-length` | diff, git-diff                 | `200`   | Value truncation budget for `text` and `md`; `0` disables truncation.          |
| `showTechnical`  | `show-technical`   | `--show-technical`   | diff, git-diff                 | `false` | Also list technical changes in the report, marked `[tech]`, for debugging.     |
| `skipMalformed`  | `skip-malformed`   | `--skip-malformed`   | all                            | `false` | Continue past a malformed candidate file instead of failing.                   |
| `basedir`        | -                  | `--basedir`          | all                            | -       | Read-only in Maven, where it is `${project.basedir}`.                          |

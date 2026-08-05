# qubership-nifi-flow-diff-cli

`qubership-nifi-flow-diff-cli` is a command-line tool that classifies the differences between two NiFi Registry
versioned flows or two NiFi flow exports (exported via `Download flow definition` or via
`/process-groups/{id}/download` API) and can restore the technical identifiers NiFi rewrites when a flow is copied or
recreated.

The classification rules, the report formats, and worked examples of each are documented once in
[`qubership-nifi-flow-diff-core`](../qubership-nifi-flow-diff-core/README.md), the library this tool is a frontend
for. This page covers only how to drive it from a shell. The Maven plugin
[`qubership-nifi-flow-diff-tool`](../qubership-nifi-flow-diff-tool/README.md) exposes the same three operations as
goals and produces identical reports; use it when you are already inside a build.

## Prerequisites

- Java - JDK 21+
- Maven - Maven 3.x

## Getting the jars

The jar carries a `Main-Class` entry but bundles nothing third-party, so `java -jar` alone fails: the classpath has to
carry the dependencies too. Both options below fill a directory with the twelve runtime jars, and that directory is the
whole classpath. Take either one; the run command that follows is the same.

### Option 1: fetch script

Copy this into your flows repository as `fetch-nifi-flow-diff.sh` and run it once per release. It takes the version and
the output directory, and works on Linux and in Git Bash on Windows.

```shell
#!/usr/bin/env bash
# fetch-nifi-flow-diff.sh <version> <output-directory>
# Downloads qubership-nifi-flow-diff-cli and its dependencies into <output-directory>.
set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "usage: $0 <version> <output-directory>" >&2
    exit 2
fi

VERSION="$1"
ARTIFACT="org.qubership.nifi:qubership-nifi-flow-diff-cli:$VERSION"

mkdir -p "$2"
# Absolute, and Windows-shaped under Git Bash, because -f below moves the project
# basedir into the local repository and a relative path would land there.
case "$(uname -s)" in
    MINGW* | MSYS*) OUT_DIR="$(cd "$2" && pwd -W)" ;;
    *) OUT_DIR="$(cd "$2" && pwd)" ;;
esac

mvn -q dependency:get -Dartifact="$ARTIFACT"

REPO="$(mvn -q help:evaluate -Dexpression=settings.localRepository -DforceStdout)"
POM="$REPO/org/qubership/nifi/qubership-nifi-flow-diff-cli/$VERSION/qubership-nifi-flow-diff-cli-$VERSION.pom"

# The published pom is flattened, so Maven resolves exactly the runtime set from it.
mvn -q dependency:copy-dependencies -f "$POM" -DoutputDirectory="$OUT_DIR"
# copy-dependencies leaves out the tool's own jar.
mvn -q dependency:copy -Dartifact="$ARTIFACT" -DoutputDirectory="$OUT_DIR"

echo "Staged $(find "$2" -maxdepth 1 -name '*.jar' | wc -l) jars in $2"
```

```shell
chmod +x fetch-nifi-flow-diff.sh
./fetch-nifi-flow-diff.sh <version> lib
```

Both arguments are required, and the script exits `2` without them. `<version>` is the release to fetch; `lib` is the
output directory, created if it does not exist.

To move to a new release, run the script again with the new version and an empty directory.

### Option 2: helper pom

Take this route to pin the settings in a file rather than pass them on the command line; the two properties are the
script's two parameters. Save it next to the flows as `flowdiff-pom.xml`, a name that will not shadow a real
`pom.xml`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>org.qubership.nifi</groupId>
    <artifactId>nifi-flow-diff-runner</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <properties>
        <flow.diff.version>X.Y.Z</flow.diff.version>
        <flow.diff.lib.dir>${project.basedir}/lib</flow.diff.lib.dir>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.qubership.nifi</groupId>
            <artifactId>qubership-nifi-flow-diff-cli</artifactId>
            <version>${flow.diff.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <version>3.7.0</version>
                <configuration>
                    <outputDirectory>${flow.diff.lib.dir}</outputDirectory>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

```shell
mvn -f flowdiff-pom.xml dependency:copy-dependencies
```

Either property can be overridden per run, which gives the same pair of parameters the script takes positionally:

```shell
mvn -f flowdiff-pom.xml dependency:copy-dependencies \
  -Dflow.diff.version=<version> -Dflow.diff.lib.dir=<output-directory>
```

To move to a new release, edit `flow.diff.version`, delete the output directory, and run the command again.

## Running it

Whichever option produced the directory, the entry point is called the same way:

```shell
java -cp "lib/*" org.qubership.nifi.flowdiff.cli.FlowDiffCli git-diff --path flows --branch main
```

Relative paths resolve against `--basedir`, which defaults to the working directory,
the directory `java` was started from. The Git subcommands also discover the enclosing repository from it.

## Subcommands

Three subcommands. `diff` and `git-diff` are read-only and emit a report; `git-revert-technical` rewrites the working
copy in place. Run `--help` on the tool or on any subcommand for the full option list.

The examples below write `nifi-flow-diff` for the launcher, meaning
`java -cp "lib/*" org.qubership.nifi.flowdiff.cli.FlowDiffCli`.

### diff

Compares two inputs and reports the differences. Each input is a directory tree or a single flow file, given as a
relative or an absolute path. Both sides must be the same kind.

```shell
nifi-flow-diff diff \
  --baseline <baselineDirOrFile> \
  --target <targetDirOrFile> \
  --format md \
  --output diff.md
```

### git-diff

Compares the working tree against a committed baseline read through JGit. The `--branch` option names the baseline
revision and defaults to `HEAD`. JGit resolves it, so it accepts any of: `HEAD`, a complete or abbreviated SHA-1, a
complete reference name (`refs/...`), or a short name under `refs/heads`, `refs/tags`, or `refs/remotes`. For a branch,
the baseline is its tip, not the merge-base: NiFi flows are replaced, not merged, so the report answers what a
replacement would introduce.

```shell
nifi-flow-diff git-diff --path <dirOrFile> --branch main
```

### git-revert-technical

Rewrites the working copy so its technical fields match `HEAD`, leaving environmental and significant changes untouched.
It prints a per-file summary of the reverted counts. Writes are atomic and are skipped when the file changed between
read and write, so a concurrent edit is never clobbered.

```shell
nifi-flow-diff git-revert-technical --path <dirOrFile>
```

## Options

| Option               | Subcommand                     | Default           | Description                                                                    |
|----------------------|--------------------------------|-------------------|--------------------------------------------------------------------------------|
| `--baseline`         | diff                           | -                 | Required. Baseline directory or single flow file.                              |
| `--target`           | diff                           | -                 | Required. Target directory or single flow file.                                |
| `--path`             | git-diff, git-revert-technical | -                 | Required. Directory or single flow file, relative to `--basedir`.              |
| `--branch`           | git-diff                       | `HEAD`            | Baseline revision resolved by JGit (see the git-diff section for forms).       |
| `--format`           | diff, git-diff                 | `text`            | Report format: `text`, `json`, or `md`.                                        |
| `--output`           | diff, git-diff                 | -                 | Report file. Required for `json` and `md`; `text` defaults to standard output. |
| `--max-value-length` | diff, git-diff                 | `200`             | Value truncation budget for `text` and `md`; `0` disables truncation.          |
| `--show-technical`   | diff, git-diff                 | `false`           | Also list technical changes in the report, marked `[tech]`, for debugging.     |
| `--skip-malformed`   | all                            | `false`           | Continue past a malformed candidate file instead of failing.                   |
| `--basedir`          | all                            | working directory | Directory that relative paths resolve against.                                 |

Every option maps one to one onto a parameter of the Maven plugin; the plugin
[Readme](../qubership-nifi-flow-diff-tool/README.md) lists both spellings side by side.

## Exit codes

Finding changes is never a failure, so a run that completes exits `0` however much it reported.

| Code | Meaning                                                                                         |
|------|-------------------------------------------------------------------------------------------------|
| `0`  | The subcommand ran.                                                                             |
| `1`  | Execution error: malformed input, an unresolvable branch, a duplicate identifier, a bad format. |
| `2`  | Usage error: an unknown option, or a required one left out.                                     |

Reports go to standard output when no `--output` is given; warnings and errors always go to standard error, so piping
a `text` report never mixes the two.

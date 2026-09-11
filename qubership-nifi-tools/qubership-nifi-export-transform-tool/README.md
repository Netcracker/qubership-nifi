# qubership-nifi-export-transform-tool

`qubership-nifi-export-transform-tool` is a Maven plugin for managing inline property values
in exported Apache NiFi flow JSON files.
It provides two operations - **Extract** and **Build** - that together allow storing
SQL queries, Groovy scripts, Jolt specifications, and other large processor properties
as separate files in version control instead of embedding them inside the flow JSON.

- **Extract** reads exported flow JSON files, moves the configured property values
  into separate files, and replaces the original values with file references of the form `@path/to/file`.
- **Build** reads flow JSON files containing `@path` references, reads the referenced files,
  and restores the original property values back into the flow JSON.

## Prerequisites

- Java - JDK 21+
- Maven - Maven 3.x

## Usage

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

### Extract

Extracts processor property values from flow JSON files into separate files:

```shell
mvn org.qubership.nifi.plugins:qubership-nifi-export-transform-tool:<version>:extract \
  -Dconfig=<configFile> \
  -Dexport-dir=<exportDir>
```

```shell
mvn nifi-transform:<version>:extract \
  -Dconfig=<configFile> \
  -Dexport-dir=<exportDir>
```

### Build

Restores processor property values from separate files back into the flow JSON:

```shell
mvn org.qubership.nifi.plugins:qubership-nifi-export-transform-tool:<version>:build \
  -Dconfig=<configFile> \
  -Dexport-dir=<exportDir>
```

```shell
mvn nifi-transform:<version>:build \
  -Dconfig=<configFile> \
  -Dexport-dir=<exportDir>
```

To additionally delete the extracted files after a successful Build:

```shell
mvn org.qubership.nifi.plugins:qubership-nifi-export-transform-tool:<version>:build \
  -Dconfig=<configFile> \
  -Dexport-dir=<exportDir> \
  -Ddelete=true
```

```shell
mvn nifi-transform:<version>:build \
  -Dconfig=<configFile> \
  -Dexport-dir=<exportDir> \
  -Ddelete=true
```

The table below describes the plugin parameters:

| Parameter    | CLI property | Goal             | Default | Description                                                                                     |
|--------------|--------------|------------------|---------|-------------------------------------------------------------------------------------------------|
| `configFile` | `config`     | extract, build   | -       | Required. Path to the YAML configuration file specifying which processor types to process.      |
| `exportDir`  | `export-dir` | extract, build   | `nifi`  | Path to the directory containing exported NiFi flow JSON files.                                 |
| `delete`     | `delete`     | build            | `false` | When `true`, deletes extracted files and their directories after a successful Build.            |

### pom.xml configuration

The Build goal can be bound to a Maven lifecycle phase via `pom.xml`.
This is useful when flow JSON files need to be restored automatically before packaging:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.qubership.nifi.plugins</groupId>
            <artifactId>qubership-nifi-export-transform-tool</artifactId>
            <version>${export-transform-tool.version}</version>
            <executions>
                <execution>
                    <id>build-nifi-flows</id>
                    <phase>prepare-package</phase>
                    <goals>
                        <goal>build</goal>
                    </goals>
                    <configuration>
                        <configFile>${project.basedir}/config/configuration-default.yaml</configFile>
                        <exportDir>${project.basedir}/nifi</exportDir>
                        <delete>false</delete>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

## Configuration file

The configuration file is a YAML file that lists processor types and their property mappings.
Each entry maps a target filename to the NiFi property whose value should be extracted.

There are two forms for specifying the property name:

**Simple form** - use when the property display name is stable across NiFi versions:

```yaml
processorTypes:
  - <processorTypeFqn>:
      <targetFilename>: <propertyDisplayName>
```

**Regular expression form** - use when the property display name differs across NiFi versions:

```yaml
processorTypes:
  - <processorTypeFqn>:
      <targetFilename>:
        regex: <pattern>
```

### Example

```yaml
processorTypes:
  - org.apache.nifi.processors.standard.ExecuteSQL:
      sql_query.sql:
        regex: SQL (?:Query|select query)
  - org.apache.nifi.processors.groovyx.ExecuteGroovyScript:
      script_body.groovy: Script Body
  - org.apache.nifi.processors.jolt.JoltTransformJSON:
      jolt_spec.json: Jolt Specification
```

A ready-to-use configuration file covering the most common qubership-nifi processor types
is available at [config/configuration-default.yaml](../../qubership-nifi-tools/qubership-nifi-export-transform-tool/config/configuration-default.yaml).

## Reference path format

Extract writes each extracted value to
`flowConf_<flowName>/<group>/.../<group>/<processorName>/<targetFilename>`
and stores a reference to it in the flow JSON as
`@flowConf_<flowName>/<group>/.../<group>/<processorName>/<targetFilename>`.

Processor and process group names are free-form text and may contain characters
that are not allowed in file and directory names. Each such character is replaced
with a token in the path and in the reference (the original name in the flow JSON
is left unchanged):

| Character | Token  |
|-----------|--------|
| `\`       | `_bs_` |
| `/`       | `_sl_` |
| `:`       | `_cl_` |
| `*`       | `_st_` |
| `?`       | `_qm_` |
| `"`       | `_qt_` |
| `<`       | `_lt_` |
| `>`       | `_gt_` |
| `\|`      | `_vb_` |

For example, a processor named `Get value > 0` is stored under a directory named
`Get value _gt_ 0`.

The replacement is one-way and not unique: a name that already contains a token
spelling, such as `Get value _gt_ 0`, maps to the same directory as `Get value > 0`.
Extract resolves a collision like this the same way it resolves two processors sharing
a plain name; see [Colliding names](#colliding-names).

## Extracted file layout

Extract writes the extracted files next to the flow JSON file they came from, under a directory
named `flowConf_<flowName>`, where `<flowName>` is the flow export name without its extension.
Inside it, Extract creates one directory per process group on the path from the root group
(which contributes no directory) down to the processor's parent group, then a directory named
after the processor, then the target file named in the configuration.

For an `ExecuteSQL` processor named `Load customers`, placed in group `PutSQL_pg` inside group
`Extract`, in the flow export `nifi/main-flow.json`:

```text
nifi/
  main-flow.json
  flowConf_main-flow/
    Extract/
      PutSQL_pg/
        Load customers/
          sql_query.sql
```

The property value becomes the reference
`@flowConf_main-flow/Extract/PutSQL_pg/Load customers/sql_query.sql`.
References always use forward slashes, on every platform.

### Colliding names

The path is built from process group and processor *names*, not identifiers, and checked for
uniqueness across every configured processor type together. Two processors can therefore end up
with the same directory path: they can share a name outright, have names that differ only in
case, have names that resolve to the same path after the character replacement in
[Reference path format](#reference-path-format), or sit in process groups whose own names collide
the same way. The case-only check runs unconditionally, on every platform, because Windows and
the macOS default file system treat two such paths as the same directory regardless of where
Extract itself runs. Extract keeps every such processor's directory distinct by appending an
underscore and the last 12 characters of the processor's identifier (a UUID) to its directory name.

For two `ExecuteSQL` processors both named `Load customers` in group `Extract`, with identifiers
ending in `...111111111111` and `...222222222222`:

```text
nifi/
  main-flow.json
  flowConf_main-flow/
    Extract/
      Load customers_111111111111/
        sql_query.sql
      Load customers_222222222222/
        sql_query.sql
```

Only the directory on disk, and the `@`-reference built from it, carry the suffix; the processor's
name in the flow JSON is unchanged. Extract logs an INFO message naming both processors and the
resolved paths whenever it disambiguates a collision this way.

A processor's directory therefore depends on which other processors are present in the same
Extract run, not on that processor alone. Adding a second `Load customers` moves the first one
from `Extract/Load customers/` to a suffixed directory, and later removing the second processor
moves it back. Extract never deletes an export's existing `flowConf_*` tree, so a directory from
before such a change stays on disk next to the new one. Delete the `flowConf_*` tree before
running Extract again on a changed export, to avoid leaving an orphaned directory behind.

Extract still fails with a validation error when two processors' final export paths match even
after the suffix is added. That happens when two identifiers share their last 12 characters, or
when one processor's plain name already equals another processor's suffixed name (see
[Extract errors](#extract-errors)).

## Error scenarios

Both goals collect every error and report them together at the end, as numbered lines under
`Extract completed with <n> error(s):` or `Build completed with <n> error(s):`, then fail with
`Extract failed with <n> error(s). See log for details.` or the Build equivalent.

Failures never leave a half-written flow. Extract skips a flow export that has validation errors and
deletes everything it has already written for a flow that hits an I/O error part way through;
Build does not write back a flow export in which any error occurred. Other flow exports in the same run
are still processed. The `-Ddelete=true` cleanup runs only when the whole Build finished without
errors.

The errors below end the run with `BUILD FAILURE` and the summary message. An unexpected I/O error -
a missing `exportDir`, an unreadable file, malformed JSON - instead aborts the run immediately with
`BUILD ERROR` and a stack trace.

### Extract errors

| Message | Cause | Fix |
| ------- | ----- | --- |
| `Duplicate processor path '<path>': processor '<id>' and processor '<id>' map to the same export path. ...` | Two processors still map to the same path after disambiguation (see [Colliding names](#colliding-names)): either their identifiers share their last 12 characters, or one's plain name already equals the other's suffixed name. | Rename one of the processors, or move one into a process group with a different name. |
| `Regex '<pattern>' matches multiple properties [<names>] in processor '<name>'. ...` | A `regex:` mapping matched more than one property name, so it is ambiguous which value to extract. | Tighten the pattern, for example by anchoring it or removing an alternative branch. |

These checks run before Extract writes anything, across every configured processor type in the flow.
Two processors sharing a directory path do not block the export; Extract resolves that automatically
(see [Colliding names](#colliding-names)). An ambiguous `regex:` mapping does block it. Build runs
none of these checks: during Build, an ambiguous `regex:` mapping silently resolves to the first
matching property.

### Build errors

| Message | Cause | Fix |
| ------- | ----- | --- |
| `Referenced file '<path>' does not exist for property '<property>' of processor '<name>' (id: ..., flow: ...). ...` | The property holds an `@path` reference, but the file is missing - typically removed by an earlier `-Ddelete=true` run, or never committed. | Restore the extracted file, or re-run Extract on a flow export that still holds the inline value. |
| `Property '<property>' of processor '<name>' has an inline value, but an extracted file already exists at '<path>'. ...` | The flow JSON was exported again from NiFi with a literal value while the extracted file is still on disk. Build cannot tell which is current. | Delete the stale extracted file, or replace the inline value with the `@path` reference. |
| `Reference path '<path>' escapes the export directory. Only paths within the flow directory are allowed.` | An `@path` value resolves outside the directory that holds the flow JSON file, for example because it contains `..`. | Correct the reference so that it stays under the flow directory. |

### Cases that are skipped instead of failing

These produce no error, and explain runs that succeed without changing anything:

- Extract logs a warning and skips a property whose value is already a reference:
  `Property '<property>' of processor '<name>' already contains a reference (<value>). Skipping.`
- A `regex:` mapping that matches no property is skipped.
- JSON files without a `flowContents` node, and anything under a `flowConf_*` directory, are not
  treated as flow exports.
- A configuration file with no `processorTypes` entries logs
  `No processor types defined in config, nothing to extract.` (or `nothing to build.`)
  and finishes successfully.

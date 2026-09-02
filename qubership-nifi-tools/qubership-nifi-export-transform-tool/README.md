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

## Extracted file layout

Extract writes the extracted files next to the flow JSON file they came from,
under a directory named `flowConf_<flowName>`, where `<flowName>` is the name of
the flow JSON file without its extension.

Inside that directory, Extract creates one directory per process group on the path
from the root group down to the processor's parent group, then one directory named
after the processor, then the target file named in the configuration.
The root group itself contributes no directory.

For an `ExecuteSQL` processor named `Load customers`, placed in group `PutSQL_pg`
inside group `Extract`, in the flow file `nifi/main-flow.json`:

```text
nifi/
  main-flow.json
  flowConf_main-flow/
    Extract/
      PutSQL_pg/
        Load customers/
          sql_query.sql
```

The property value in `main-flow.json` becomes the reference
`@flowConf_main-flow/Extract/PutSQL_pg/Load customers/sql_query.sql`.
References always use forward slashes, on every platform.

Because the directory is derived from names alone, the path has to identify the
processor uniquely. Extract enforces this before it writes anything - see
[Error scenarios](#error-scenarios).

## Error scenarios

Both goals collect every error they find and report them together at the end,
rather than stopping at the first one. Each error is logged as a numbered line
under `Extract completed with <n> error(s):` or `Build completed with <n> error(s):`,
and the goal then fails with `Extract failed with <n> error(s). See log for details.`
or `Build failed with <n> error(s). See log for details.`

Failures never leave a half-written flow. Extract skips a flow file that has
validation errors, and Build does not write back a flow file in which any error
occurred; other flow files in the same run are still processed and written.
If Extract hits an I/O error part way through a flow, it deletes every file it has
already written for that flow. The `-Ddelete=true` cleanup runs only when the whole
Build finished without errors.

The errors below end the run with `BUILD FAILURE` and the summary message.
An unexpected I/O error - a missing `exportDir`, an unreadable file, malformed JSON -
is different: it aborts the run immediately with `BUILD ERROR` and a stack trace.

### Extract errors

| Message | Cause | Fix |
| ------- | ----- | --- |
| `Duplicate processor path '<path>': processor '<id>' and processor '<id>' produce the same path. Processors must have unique paths (parent process group names + processor name) within the flow, since the path determines the directory structure during Extract.` | Two processors resolve to the same parent group path plus processor name, so both would write to the same directory. | Rename one of the processors, or move one into a process group with a different name. |
| `Invalid characters in <flow name \| process group name \| processor name> '<name>'. The following characters are not allowed in file system paths: / \ : * ? " < > \|` | A name that becomes a directory segment - the flow filename, a process group name, or a processor name - contains a character that is not valid in a file system path. | Rename the process group or the processor, or rename the flow JSON file. |
| `Regex '<pattern>' matches multiple properties [<names>] in processor '<name>'. The pattern must match exactly one property.` | A `regex:` mapping matched more than one property name on a processor, so it is ambiguous which value to extract. | Tighten the pattern, for example by anchoring it or removing an alternative branch. |

Notes on these checks:

- They all run before Extract writes anything, across every processor of every
  configured type in the flow. One bad name therefore blocks the whole flow file,
  not just the offending processor.
- Path uniqueness is checked across all configured processor types together, not
  per type. Two different types can map to the same target filename, so two
  processors sharing a path would still collide.
- The path is built from process group *names*, not identifiers. Two distinct
  groups that happen to share a name collide.
- When more than two processors share a path, one message is reported per extra
  processor, each pairing it against the first one seen.
- These checks run for Extract only. Build does not validate paths, and an
  ambiguous `regex:` mapping silently resolves to the first matching property
  during Build.

### Build errors

| Message | Cause | Fix |
| ------- | ----- | --- |
| `Referenced file '<path>' does not exist for property '<property>' of processor '<name>' (id: <uuid>, group: '<group>', groupId: <uuid>, flow: '<flowFile>'). Run Extract first to generate the configuration files.` | The property holds an `@path` reference, but the file it points to is missing - typically because the extracted files were removed by an earlier `-Ddelete=true` run, or were never committed. | Restore the extracted file, or re-run Extract on a flow export that still holds the inline value. |
| `Property '<property>' of processor '<name>' has an inline value, but an extracted file already exists at '<path>'. This is ambiguous: remove either the inline value or the extracted file (flow file: '<flowFile>').` | The flow JSON was exported again from NiFi with a literal value while the extracted file from an earlier Extract is still on disk. Build cannot tell which of the two is current. | Keep one of them: delete the stale extracted file, or replace the inline value with the `@path` reference. |
| `Reference path '<path>' escapes the export directory. Only paths within the flow directory are allowed.` | An `@path` value resolves outside the directory that holds the flow JSON file, for example because it contains `..`. | Correct the reference so that it stays under the flow directory. |

### Cases that are skipped instead of failing

These produce no error. They explain runs that succeed without changing anything:

- Extract logs a warning and skips a property whose value is already a reference:
  `Property '<property>' of processor '<name>' already contains a reference (<value>). Skipping.`
- Properties that are unset or empty are skipped by both goals. A NiFi export
  contains only explicitly set properties, so a property left at its default is
  absent from the flow JSON.
- A `regex:` mapping that matches no property is skipped.
- JSON files without a `flowContents` node, and anything under a `flowConf_*`
  directory, are not treated as flow files.
- A configuration file with no `processorTypes` entries makes the goal log
  `No processor types defined in config, nothing to extract.` (or `nothing to build.`)
  and finish successfully.

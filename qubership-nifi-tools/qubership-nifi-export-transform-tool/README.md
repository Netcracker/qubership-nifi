# qubership-nifi-export-transform-tool

`qubership-nifi-export-transform-tool` is a Maven plugin for managing inline property values
in exported Apache NiFi flow JSON files.
It provides two operations — **Extract** and **Build** — that together allow storing
SQL queries, Groovy scripts, Jolt specifications, and other large processor properties
as separate files in version control instead of embedding them inside the flow JSON.

- **Extract** reads exported flow JSON files, moves the configured property values
  into separate files, and replaces the original values with file references of the form `@path/to/file`.
- **Build** reads flow JSON files containing `@path` references, reads the referenced files,
  and restores the original property values back into the flow JSON.

## Prerequisites

The plugin must be installed to the local Maven repository before use.

## Usage

### Extract

Extracts processor property values from flow JSON files into separate files:

```shell
mvn org.qubership.nifi:qubership-nifi-export-transform-tool:<version>:extract \
  -Dconfig=<configFile> \
  -Dexport-dir=<exportDir>
```

### Build

Restores processor property values from separate files back into the flow JSON:

```shell
mvn org.qubership.nifi:qubership-nifi-export-transform-tool:<version>:build \
  -Dconfig=<configFile> \
  -Dexport-dir=<exportDir>
```

To additionally delete the extracted files after a successful Build:

```shell
mvn org.qubership.nifi:qubership-nifi-export-transform-tool:<version>:build \
  -Dconfig=<configFile> \
  -Dexport-dir=<exportDir> \
  -Ddelete=true
```

The table below describes the plugin parameters:

| Parameter    | Goal             | Default | Description                                                                                     |
|--------------|------------------|---------|-------------------------------------------------------------------------------------------------|
| `config`     | extract, build   | —       | Required. Path to the YAML configuration file specifying which processor types to process.      |
| `export-dir` | extract, build   | `nifi`  | Path to the directory containing exported NiFi flow JSON files.                                 |
| `delete`     | build            | `false` | When `true`, deletes extracted files and their directories after a successful Build.             |

### pom.xml configuration

The Build goal can be bound to a Maven lifecycle phase via `pom.xml`.
This is useful when flow JSON files need to be restored automatically before packaging:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.qubership.nifi</groupId>
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
                        <configFile>${project.basedir}/config/configuration-default-regex.yaml</configFile>
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

**Simple form** — use when the property display name is stable across NiFi versions:

```yaml
processorTypes:
  - <processorTypeFqn>:
      <targetFilename>: <propertyDisplayName>
```

**Regex form** — use when the property display name differs across NiFi versions:

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
is available at [config/configuration-default.yaml](https://github.com/Netcracker/.github/blob/main/qubership-nifi-tools/qubership-nifi-export-transform-tool/config/configuration-default.yaml).

# qubership-nifi-component-comparator-tool

Command-line tool for comparing component properties from two different NiFi versions.
The qubership-nifi-component-comparator-tool produces four files:

- a CSV file containing detailed information about the comparison results.
- a Markdown file containing detailed information about the comparison results.
- a JSON file (`NiFiTypeMapping.json`) used later when updating Controller Services and Reporting Task
  exports.
- a JSON file (`NiFiProcessorTypeMapping.json`) used later when updating Processor exports.

## Prerequisites

- JDK 17 or 21
- Maven 3.x

## Usage

Run the tool from the repository root via the exec-maven-plugin:

```shell
mvn exec:java \
  -pl qubership-nifi-tools/qubership-nifi-component-comparator-tool \
  -Dexec.args="--sourceDir /path/to/source --targetDir /path/to/target --dictionaryPath /path/to/dict.yaml --outputPath /path/to/output"
```

## Parameters

| Parameter          | Default | Description                                                             |
|--------------------|---------|-------------------------------------------------------------------------|
| `--sourceDir`      |         | Path to directory with JSON components from the source version of NiFi. |
| `--targetDir`      |         | Path to directory with JSON components from the target version of NiFi. |
| `--dictionaryPath` |         | Path to the file with Display Name mapping between different versions.  |
| `--outputPath`     | `./`    | Path to the folder containing comparison results.                       |

## Output structure

```shell
<outputPath>/
  NiFiComponentsDelta.csv
  NiFiTypeMapping.json
  NiFiProcessorTypeMapping.json
  NiFiComponentsDelta.md
```

## Type-mapping JSON files

Two JSON files carry the rename and deletion mappings, split by component kind:

- `NiFiTypeMapping.json` covers controller services and reporting tasks.
- `NiFiProcessorTypeMapping.json` covers processors.

Both share the same shape, `{ componentType: { oldApiName: newApiName | null } }`: a renamed property maps
its old API name to the new one, and a deleted property maps its API name to `null`. Deletions are recorded
only when the property is listed in the dictionary's `propertiesAllowedToDelete` section for its component
type; renames are always recorded.

## Controller service references

Some properties hold a reference to a controller service rather than a literal value, for example
`Database Connection Pooling Service` in `ExecuteSQL`. The tool marks each changed property (renamed,
deleted, or added) that references a controller service and records the referenced controller-service
interface type, such as `org.apache.nifi.dbcp.DBCPService`.

The marker appears only in the CSV and Markdown outputs. The type-mapping JSON files do not carry it.

A reference is detected from the property descriptor in one of two ways, depending on the export's NiFi
version:

- `identifiesControllerService`: a string holding the interface type (NiFi 1.x exports).
- `typeProvidedByValue.type`: the interface type nested in an object (NiFi 2.x exports).

The marker appears in two outputs:

- **CSV**: a `Controller Service Reference` column holds the interface type, or is empty for plain
  properties.
- **Markdown**: the per-component tables gain a `Controller Service Reference` column. The summary adds
  a `Controller service reference changes` metric and a `CS Refs` column in the per-type breakdown.

## Running tests

Unit tests (no Docker required):

```shell
mvn test -pl qubership-nifi-tools/qubership-nifi-component-comparator-tool
```

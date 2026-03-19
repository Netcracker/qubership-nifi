# qubership-nifi-component-comparator-tool

Command-line tool for comparing component properties from two different NiFi versions.

## Prerequisites

- JDK 17 or 21
- Maven 3.x

## Usage

Run the tool from the repository root via the exec-maven-plugin:

```shell
mvn exec:java \
  -Dexec.mainClass="org.qubership.nifi.NifiComponentComparator" \
  -Dexec.args="--sourceDir /path/to/source --targetDir /path/to/target --dictionaryPath /path/to/dict.yaml --outputPath /path/to/output"
```

## Parameters

| Parameter          | Default | Description                                                             |
|--------------------|---------|-------------------------------------------------------------------------|
| `--sourceDir`      |         | Path to directory with JSON components from the source version of NiFi. |
| `--targetDir`      |         | Path to directory with JSON components from the target version of NiFi. |
| `--dictionaryPath` |         | Path to the file with Display Name mapping between different versions.  |
| `--outputPath`     | `./`    | Path to the folder containing comparison results.                      |

## Output structure

```shell
<outputPath>/
  NiFiComponentsDelta.csv
  NiFiTypeMapping.json
```

## Running tests

Unit tests (no Docker required):

```shell
mvn test -pl qubership-nifi-tools/qubership-nifi-component-comparator-tool
```

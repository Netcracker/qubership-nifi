# qubership-nifi-api-export-tool

Command-line tool for extracting NiFi component API descriptors (processors, controller services, and
reporting tasks) from a running NiFi instance managed by Testcontainers.

It starts a temporary NiFi Docker container, authenticates to its REST API, collects property
descriptor definitions for all available components, and writes them as JSON files. Both NiFi 1.x
and NiFi 2.x are supported.

## Prerequisites

- JDK 17 or 21
- Maven 3.x
- Docker (accessible to the current user)

## Usage

Run the tool from the repository root via the exec-maven-plugin:

```shell
mvn exec:java \
  -pl qubership-nifi-tools/qubership-nifi-api-export-tool \
  -Dexec.args="--version 2.7.2 --output-dir ./nifi-api-output"
```

## Parameters

| Parameter | Default | Description |
|---|---|---|
| `--version` | `2.7.2` | NiFi Docker image version tag (used as `apache/nifi:<version>`) |
| `--output-dir` | `./nifi-api-output` | Directory where JSON output files are written |
| `--timeout` | `180` | NiFi container startup timeout in seconds |
| `--port` | `18443` | Host port bound to NiFi's internal HTTPS port 8443 |

Parameters are optional; omit any to use the default value.

## Output structure

```shell
<output-dir>/
  processors/
    <ProcessorSimpleName>.json
    ...
  controllerService/
    <ServiceSimpleName>.json
    ...
  reportingTask/
    <TaskSimpleName>.json
    ...
```

Each JSON file contains:

```json
{
  "type": "org.example.MyProcessor",
  "propertyDescriptors": { ... }
}
```

## Running tests

Unit tests (no Docker required):

```shell
mvn test -pl qubership-nifi-tools/qubership-nifi-api-export-tool
```

Integration tests (Docker required):

```shell
mvn test -pl qubership-nifi-tools/qubership-nifi-api-export-tool -Dgroups=docker
```

# qubership-nifi-tools

Aggregator module for the tooling that automates NiFi-related activities: generating documentation,
exporting and comparing component APIs, transforming flow exports, and classifying flow differences.
Each tool ships as its own child module; follow the links below for usage details.

## Child modules

| Module                                                                                           | Description                                                                                                                                                                              |
|--------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`qubership-nifi-api-export-tool`](qubership-nifi-api-export-tool/README.md)                     | Command-line tool that extracts component API descriptors (processors, controller services, reporting tasks) from a NiFi instance started via Testcontainers. Supports NiFi 1.x and 2.x. |
| [`qubership-nifi-component-comparator-tool`](qubership-nifi-component-comparator-tool/README.md) | Command-line tool that compares component properties across two NiFi versions and emits the mapping files used to update exports.                                                        |
| [`qubership-nifi-docs-generator`](qubership-nifi-docs-generator/README.md)                       | Maven plugin that generates Markdown documentation for custom NiFi components from a template with marker comments.                                                                      |
| [`qubership-nifi-openapi-enricher`](qubership-nifi-openapi-enricher/README.md)                   | Command-line tool that enriches the NiFi OpenAPI specification so it passes API Hub validation rules.                                                                                    |
| [`qubership-nifi-export-transform-tool`](qubership-nifi-export-transform-tool/README.md)         | Maven plugin that extracts large processor property values (SQL, Groovy, Jolt) from flow JSON into separate files and restores them on demand.                                           |
| [`qubership-nifi-flow-diff-tool`](qubership-nifi-flow-diff-tool/README.md)                       | Maven plugin that classifies the differences between two flow exports and can revert the technical identifiers NiFi rewrites when a flow is copied or recreated.                         |
| [`qubership-nifi-tools-common`](qubership-nifi-tools-common/README.md)                           | Library that re-serializes a JSON file through Jackson while preserving the input's formatting, avoiding whitespace-only diffs.                                                          |
| [`qubership-nifi-tools-common-test-tool`](qubership-nifi-tools-common-test-tool/README.md)       | Command-line wrapper around `qubership-nifi-tools-common` for reformatting a single JSON file.                                                                                           |
| `qubership-nifi-api-export-tool-it`                                                              | Integration tests for `qubership-nifi-api-export-tool`.                                                                                                                                  |
| `qubership-nifi-docs-generator-it`                                                               | Integration tests for `qubership-nifi-docs-generator`.                                                                                                                                   |

## Building

Build all tools from the repository root:

```bash
mvn clean install -pl qubership-nifi-tools -am
```

Integration test modules (`*-it`) are skipped by default. Enable them with the `tools-integration-tests` profile:

```bash
mvn verify -pl qubership-nifi-tools -am -Ptools-integration-tests
```

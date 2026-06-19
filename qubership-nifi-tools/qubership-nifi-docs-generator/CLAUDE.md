### Documentation Generator Plugin

The `qubership-nifi-docs-generator` module is a custom Maven plugin (`@Mojo name="generate"`) that inspects NiFi component classes at build time and generates markdown documentation. Key classes:

- **`PropertyDocumentation`** — Main Mojo; reads `documentGeneratorConfig.yaml`, discovers Processors/ControllerServices/ReportingTasks, delegates to `MarkdownUtils`.
- **`MarkdownUtils`** — Reads `docs/template/user-guide-template.md` and replaces marker sections with generated tables.
- **`PropertyDescriptorEntity`** — DTO representing a single NiFi property for documentation purposes; escapes HTTP links in descriptions.
- **`CustomComponentEntity`** — DTO for custom components defined in config YAML (not auto-discovered from NARs).

The template file (`docs/template/user-guide-template.md`) contains marker comments that the plugin replaces with generated content. Do not remove these markers.

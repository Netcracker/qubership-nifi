# qubership-nifi-docs-generator

`qubership-nifi-docs-generator` is a Maven plugin that generates Markdown-based documentation
from projects containing custom Apache NiFi components.
As input, the generator uses a template documentation file with specific marker comments that indicate
the locations where documentation for processors, controller services, and reporting tasks will be injected.
The generated documentation includes:

- A summary table listing the component name, description, and the NAR file that packages the component
- A details section listing the component name, description, and a table of the component's properties

On each run, the output documentation file is overwritten with the template contents,
and then the generated sections are inserted at the specified marker locations.

The plugin is capable of processing multi-module projects and collecting data from all modules.

## Template file structure

The template file must be a Markdown-formatted document with specific marker comments indicating the locations
where auto-generated documentation should be injected.

The following marker comments must be included in the template file.
They must not be modified and must be kept as-is for the plugin to work correctly:

```markdown
...
<!-- Table for additional processors. DO NOT REMOVE. -->

...

<!-- Additional processors properties description. DO NOT REMOVE. -->
<!-- End of additional processors properties description. DO NOT REMOVE. -->

...

<!-- Table for additional controller services. DO NOT REMOVE. -->

...

<!-- Additional controller services description. DO NOT REMOVE. -->
<!-- End of additional controller services description. DO NOT REMOVE. -->

...

<!-- Table for additional reporting tasks. DO NOT REMOVE. -->

...

<!-- Additional reporting tasks description. DO NOT REMOVE. -->
<!-- End of additional reporting tasks description. DO NOT REMOVE. -->
```

An example template is available at [docs/template/user-guide-template.md](../docs/template/user-guide-template.md).

## Prerequisites

Before running the plugin, user must build the project at least locally, so that all JAR files in NAR dependencies are available.

## Usage

To run the documentation generator with default parameters, execute the following command in the repository root:

```shell
mvn org.qubership.nifi:qubership-nifi-docs-generator:generate
```

To run the documentation generator with explicit parameters, execute the following command:

```shell
mvn org.qubership.nifi:qubership-nifi-docs-generator:generate -Ddoc.template.file=<templateFile> -Ddoc.output.file=<docFile> -Ddoc.exclude.artifact.file=<docGeneratorConfig>
```

where `<templateFile>`, `<docFile>`, and `<docGeneratorConfig>` are paths to the template file, the target
documentation file, and the documentation generator configuration file, respectively.

The table below describes the plugin parameters:

| Parameter name                    | Default Value                                 | Description                                                                                                                   |
|-----------------------------------|-----------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| doc.template.file                 | `/docs/template/user-guide-template.md`       | The template file used to generate documentation. Must be provided and must match the requirements listed in the **Template file structure** section. |
| doc.output.file                   | `/docs/user-guide.md`                         | The output documentation file. Regenerated on each plugin execution.                                                         |
| doc.exclude.artifact.file         | `/docs/template/documentGeneratorConfig.yaml` | Optional. The documentation generator configuration file specifying NAR artifacts to exclude from processing.                 |

## Documentation generator configuration file

The documentation generator configuration file specifies the list of artifacts to exclude from the plugin's processing.
The file uses YAML format and has the following structure:

```yaml
excludedArtifacts:
  - <groupId1>:<artifactId1>
  - <groupId2>:<artifactId2>
  - ...
```

An example configuration file is available at [docs/template/documentGeneratorConfig.yaml](../docs/template/documentGeneratorConfig.yaml).

# qubership-nifi-kb-builder-tool

Builds a portable, version-matched NiFi Knowledge Base from a running NiFi instance. The Knowledge
Base gives an AI agent the component definitions and documentation it needs to create and modify
NiFi flows for that exact NiFi version.

The tool supports NiFi `[1.26.0, 2.0.0)` and `[2.5.0, 3.0.0)` on Java 21. NiFi 2.x collection uses
only GET requests. NiFi 1.x collection requires `--allow-temporary-components` and creates stopped
or disabled instances to read their property descriptors. The tool deletes these instances and
sends requests only to the supplied NiFi origin.

| Target | Certificate | Bearer token | Cookie | Collection |
| --- | --- | --- | --- | --- |
| NiFi 1.26.0 through 1.x | Supported | Supported | Rejected before mutation; use certificate or token | Temporary instances plus component HTML |
| NiFi 2.5.0 through 2.x | Supported | Supported | Supported | Native definitions plus Markdown |

NiFi 2.0.0 through 2.4.x are unsupported because the required definition and documentation APIs
start at 2.5.0.

Everything the tool prints, including usage, the version, and diagnostics, goes to standard error.
Standard output is left free for whatever consumes the tool.

## Prerequisites

- JDK 21
- Maven 3.x
- Network access to a running NiFi instance over HTTPS, with read permission for `/flow`

## Getting the jars

The published jar bundles nothing third-party, so its dependencies have to sit next to it. The helper
pom below fills a directory with the tool and every jar it needs at runtime. The manifest names its
siblings by filename, so that directory is all `java` needs.

Save it wherever you drive the tool from, as `kb-builder-pom.xml`, a name that will not shadow a real
`pom.xml`. The two properties are the only things to set: the release to fetch, and where to put it.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>org.qubership.nifi</groupId>
    <artifactId>nifi-kb-builder-runner</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <properties>
        <kb.builder.version>X.Y.Z</kb.builder.version>
        <kb.builder.lib.dir>${project.basedir}/lib</kb.builder.lib.dir>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.qubership.nifi</groupId>
            <artifactId>qubership-nifi-kb-builder-tool</artifactId>
            <version>${kb.builder.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <version>3.7.0</version>
                <configuration>
                    <outputDirectory>${kb.builder.lib.dir}</outputDirectory>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

```shell
mvn -f kb-builder-pom.xml dependency:copy-dependencies
```

The tool is a dependency of the helper pom, so this one command stages its jar along with the rest.
Either property can be overridden per run:

```shell
mvn -f kb-builder-pom.xml dependency:copy-dependencies \
  -Dkb.builder.version=<version> -Dkb.builder.lib.dir=<output-directory>
```

To move to a new release, edit `kb.builder.version`, delete the output directory, and run the command
again.

## Usage

```text
java -jar lib/qubership-nifi-kb-builder-tool-<version>.jar \
  --nifi-url <https-url> --auth <token|cookie|certificate> \
  [--certificate-file <pkcs12-path>] [--ca-file <pem-path>] \
  [--skip-guides] [--allow-temporary-components] --output-dir <directory>
```

The manifest lists the dependencies under `Class-Path` by exact filename, resolved against the jar's
own directory. Naming the classpath instead works too, and keeps working when a directory holds a
different set of versions than the jar was built against:

```shell
java -cp "lib/*" org.qubership.nifi.tools.kb.cli.KnowledgeBaseBuilderApplication \
  --nifi-url https://nifi.example.com/nifi --auth token --output-dir ./nifi-kb
```

### Arguments

| Argument                            | Required         | Description                                                                                           |
|-------------------------------------|------------------|-------------------------------------------------------------------------------------------------------|
| `--nifi-url <url>`                  | Yes              | NiFi deployment or UI URL. HTTPS is required. A trailing `/nifi` or `/nifi-api` is normalized.        |
| `--auth token\|cookie\|certificate` | Yes              | Selects the authentication mode explicitly.                                                           |
| `--certificate-file <path>`         | Certificate mode | PKCS#12 file with one private-key entry and its certificate chain.                                    |
| `--ca-file <path>`                  | No               | PEM file with one or more trusted CA certificates. Omit to use the JVM trust store.                   |
| `--skip-guides`                     | No               | Builds the component catalog without requesting or processing the guides.                             |
| `--allow-temporary-components`      | NiFi 1.x         | Allows temporary components on a disposable target. Ignored on NiFi 2.x.                              |
| `--output-dir <path>`               | Yes              | Destination directory. Existing content is replaced only after validation.                            |
| `-h`, `--help`                      | No               | Prints usage without reading secrets or making requests.                                              |
| `-V`, `--version`                   | No               | Prints the builder version.                                                                           |

The output directory must not be a file or contain the working directory, certificate file, or CA file.

No option accepts a token, cookie, or password. Token mode reads `NIFI_ACCESS_TOKEN`, cookie mode
reads `NIFI_AUTHORIZATION_BEARER_COOKIE`, and certificate mode reads `NIFI_PKCS12_PASSWORD`.

### Token example

```shell
export NIFI_ACCESS_TOKEN='<token>'

java -jar lib/qubership-nifi-kb-builder-tool-<version>.jar \
  --nifi-url https://nifi.example.com/nifi --auth token \
  --ca-file /etc/nifi/ca.pem --output-dir ./nifi-kb
```

### Certificate example

```shell
export NIFI_PKCS12_PASSWORD='<password>'

java -jar lib/qubership-nifi-kb-builder-tool-<version>.jar \
  --nifi-url https://nifi.example.com/nifi --auth certificate \
  --certificate-file /etc/nifi/agent-client.p12 --output-dir ./nifi-kb
```

### Cookie example

Cookie authentication supports NiFi 2.x only. Use certificate or token authentication for NiFi 1.x
because its writes require unsupported CSRF handling. Set `NIFI_AUTHORIZATION_BEARER_COOKIE` to your
user's `__Secure-Authorization-Bearer` cookie. It must remain valid until collection finishes.

```shell
export NIFI_AUTHORIZATION_BEARER_COOKIE='<cookie-value>'

java -jar lib/qubership-nifi-kb-builder-tool-<version>.jar \
  --nifi-url https://nifi.example.com/nifi --auth cookie \
  --ca-file /etc/nifi/ca.pem --output-dir ./nifi-kb
```

### Catalog-only example

```shell
export NIFI_ACCESS_TOKEN='<token>'

java -jar lib/qubership-nifi-kb-builder-tool-<version>.jar \
  --nifi-url https://nifi.example.com/nifi --auth token \
  --skip-guides --output-dir ./nifi-kb
```

### NiFi 1.x example and operating requirements

```shell
export NIFI_PKCS12_PASSWORD='<password>'
java -jar lib/qubership-nifi-kb-builder-tool-<version>.jar \
  --nifi-url https://disposable-nifi.example.com --auth certificate \
  --certificate-file /etc/nifi/client.p12 --ca-file /etc/nifi/ca.pem \
  --allow-temporary-components --output-dir ./nifi1-kb
```

Use a disposable instance with the same extensions as the target deployment. In a cluster, NiFi
replicates temporary component creation and deletion to every node. The tool never configures,
schedules, or enables these components, but creation can invoke initialization callbacks such as
`@OnAdded` and cause external effects.

The identity needs `/flow` read access; create, read, and delete access for temporary components;
and permission to manage controller-level reporting tasks. Restricted types require their matching
permissions.

Before creating components, the tool validates the NiFi version and component catalog, fetches the
first component page, and collects the guides. The tool does not check permissions in advance, so a
permission or page failure can occur after earlier resources were created and deleted. Cleanup
finishes before output is published, and a failed run preserves the previous output.
`--skip-guides` skips the three general guides but not NiFi 1.x component pages.

To recover after an interrupted run, find owned resources by their logged marker, IDs, and endpoints.
Verify each resource and its current revision. Delete components and controller-level reporting
tasks, then delete the process group.

### Building from source

From a clean checkout, build the tool and its reactor dependencies:

```shell
mvn -pl qubership-nifi-tools/qubership-nifi-kb-builder-tool -am install -DskipUnitTests=true
```

The build leaves the runtime jars beside the tool's own jar in
`qubership-nifi-tools/qubership-nifi-kb-builder-tool/target`, which gives that directory the same flat
shape a staged release has. Run it from there without any further setup:

```shell
export NIFI_ACCESS_TOKEN='<token>'

java -jar qubership-nifi-tools/qubership-nifi-kb-builder-tool/target/qubership-nifi-kb-builder-tool-<version>.jar \
  --nifi-url https://nifi.example.com/nifi --auth token --output-dir ./nifi-kb
```

## Output

The tool writes a portable directory:

```text
<output-dir>/
  manifest.json
  components/
    index.md
    index.json
    processors/<component-name>-<identity-hash>/
      component.md
      component.json
      componentDocumentation.md  # NiFi 1.x only
      additionalDetails.md       # When available
    controller-services/<component-name>-<identity-hash>/...
    reporting-tasks/<component-name>-<identity-hash>/...
  guides/                       # Absent with --skip-guides
    index.json
    expression-language-guide.md
    record-path-guide.md
    developer-guide.md
```

In schema `"2"`, each component JSON contains `documentedType`, `definition`,
`additionalDocumentation`, and `definitionFormat`. NiFi 1.x components also contain
`documentationSources`. The `documentedType` field preserves the NiFi catalog entry, and
`component.md` provides a concise, searchable summary.

Read `manifest.json.collection` before interpreting definitions. It records the definition format,
field sources, documentation formats, and unavailable metadata shared by the catalog. For NiFi 1.x,
an absent field listed in `unavailableMetadata` is unknown rather than unsupported.
`components/index.md` summarizes the same constraints.

| Target | Definitions | Documentation |
| --- | --- | --- |
| NiFi 1.x | Normalized descriptors and HTML. Excludes temporary IDs, revisions, configured values, validation errors, and controller-service instance choices. | Full component HTML becomes `componentDocumentation.md`; linked HTML becomes `additionalDetails.md`. |
| NiFi 2.x | Unmodified native definitions. | Additional Markdown remains verbatim in `additionalDetails.md`. |

NiFi 1.x definitions preserve static defaults, allowable values, dependencies, and service API and
bundle references. Unordered permission restrictions are sorted by ID for repeatable output.
Component documentation is untrusted. The tool writes `additionalDetails.md` only when NiFi
advertises and returns content. On NiFi 1.x, a missing link is not advertised; a linked 404 or blank
response is recorded as unavailable. Authentication errors, server errors, and malformed successful
HTML responses fail the build.

Consumers must read documented fields and types, ignore additional fields in a supported schema,
and reject unknown `schemaVersion` values.

All three component-kind directories exist, even when empty. `manifest.json` records provenance, the
applicable minimum NiFi version, component counts, guide status, and guide source paths. Guides come
from `/nifi-docs/html/` on NiFi 1.x and `/nifi-api/html/` on NiFi 2.x.

The aggregate `sha256:` fingerprint covers all component output, `manifest.json.collection`, and
guides unless `--skip-guides` is set. The collection metadata uses the label
`manifest.json#collection`; the rest of the manifest is excluded because it stores the fingerprint.
Use the fingerprint as a cache key. Full and catalog-only builds of the same NiFi instance have
different fingerprints.

## Exit codes

| Code | Category                    |
|------|-----------------------------|
| `0`  | Success                     |
| `2`  | Usage or configuration      |
| `3`  | TLS or authentication       |
| `4`  | Authorization               |
| `5`  | Unsupported version         |
| `6`  | Collection or parsing       |
| `7`  | Output                      |

A failed run leaves the previous output directory unchanged.

## Troubleshooting

- **Exit code 2, "must use HTTPS":** the `--nifi-url` scheme is not HTTPS. All authentication modes
  carry reusable credentials, so plain HTTP is rejected.
- **Exit code 2, "Missing required option":** `--nifi-url`, `--auth`, and `--output-dir` have no
  defaults. The usage block printed below the message lists every option.
- **Exit code 3 on a self-signed NiFi:** pass the NiFi CA chain with `--ca-file`. Without it, the JVM
  trust store is used and a self-signed certificate is not trusted.
- **Exit code 5:** the target is outside `[1.26.0, 2.0.0)` and `[2.5.0, 3.0.0)`, or the version
  cannot be parsed.
- **Exit code 6 during cleanup:** the message names the run marker; recover the owned resources using
  that marker and the logged IDs. Cleanup errors take precedence over earlier collection errors and
  preserve the previous Knowledge Base.
- **Exit code 6 on a guide heading:** the Developer's Guide layout changed and a required section is
  missing or ambiguous. Run with `--skip-guides` to build the component catalog without the guides.

## Running tests

```shell
# Unit tests (no NiFi required)
mvn test -pl qubership-nifi-tools/qubership-nifi-kb-builder-tool -am

# Integration tests (Docker required)
mvn verify -pl qubership-nifi-tools/qubership-nifi-kb-builder-tool-it -am -Ptools-integration-tests -DskipUnitTests=true
```

Both commands need `-am`. The tool depends on the `qubership-nifi-tools-nifi-common` snapshot, which a
clean checkout has not installed yet, and the integration tests launch the packaged jar in its own
process, so the tool module has to be packaged before they run.

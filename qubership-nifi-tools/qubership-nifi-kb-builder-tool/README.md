# qubership-nifi-kb-builder-tool

Builds a portable, version-matched NiFi Knowledge Base from a running NiFi instance. The Knowledge
Base gives an AI agent the component definitions and documentation it needs to create and modify
NiFi flows for that exact NiFi version.

The tool performs only HTTP GET requests. It does not read the configured flow, create or modify
components, or send data to any host other than the NiFi origin you provide. It supports NiFi
versions `>= 2.5.0` and `< 3.0.0`. It is a plain Java command-line application and runs on Java 21.

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
  [--skip-guides] --output-dir <directory>
```

The manifest lists the dependencies under `Class-Path` by exact filename, resolved against the jar's
own directory. Naming the classpath instead works too, and keeps working when a directory holds a
different set of versions than the jar was built against:

```shell
java -cp "lib/*" org.qubership.nifi.tools.kb.cli.KnowledgeBaseBuilderApplication \
  --nifi-url https://nifi.example.com/nifi --auth token --output-dir ./nifi-kb
```

### Arguments

| Argument                              | Required         | Description                                                                                    |
|---------------------------------------|------------------|------------------------------------------------------------------------------------------------|
| `--nifi-url <url>`                    | Yes              | NiFi deployment or UI URL. HTTPS is required. A trailing `/nifi` or `/nifi-api` is normalized. |
| `--auth token\|cookie\|certificate` | Yes              | Selects the authentication mode explicitly.                                                    |
| `--certificate-file <path>`           | Certificate mode | PKCS#12 file with one private-key entry and its certificate chain.                             |
| `--ca-file <path>`                    | No               | PEM file with one or more trusted CA certificates. Omit to use the JVM trust store.            |
| `--skip-guides`                       | No               | Builds the component catalog without requesting or processing the guides.                      |
| `--output-dir <path>`                 | Yes              | Destination directory. An existing directory is replaced only after a validated build.         |
| `-h`, `--help`                        | No               | Prints usage without reading secrets or making requests.                                       |
| `-V`, `--version`                     | No               | Prints the builder version.                                                                    |

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

Cookie mode is a fallback when certificate and token authentication are unavailable. Set
`NIFI_AUTHORIZATION_BEARER_COOKIE` to the value of your user's `__Secure-Authorization-Bearer`
cookie. The cookie must remain valid until data gathering finishes.

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
    processors/<component-name>-<identity-hash>/{component.md, component.json, additionalDetails.md}
    controller-services/<component-name>-<identity-hash>/...
    reporting-tasks/<component-name>-<identity-hash>/...
  guides/                       # Absent with --skip-guides
    index.json
    expression-language-guide.md
    record-path-guide.md
    developer-guide.md
```

`component.json` holds the lossless NiFi list entry and definition plus the derived
`additionalDocumentation` state. `component.md` is a concise, searchable summary. `additionalDetails.md`
is verbatim, untrusted component-supplied Markdown, written only when NiFi advertised it and returned
it. `manifest.json` records provenance, component counts, guide statuses, and a single aggregate
`sha256:` catalog fingerprint.

## Exit codes

| Code | Category                    |
|------|-----------------------------|
| `0`  | Success                     |
| `2`  | Usage or configuration      |
| `3`  | TLS or authentication       |
| `4`  | Authorization               |
| `5`  | Unsupported target version  |
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
- **Exit code 5:** the target NiFi version is outside `[2.5.0, 3.0.0)` or the about endpoint returned
  a version that could not be parsed.
- **Exit code 6 on a guide heading:** the Developer's Guide layout changed and a required section is
  missing or ambiguous. Run with `--skip-guides` to build the component catalog without the guides.

## Running tests

```shell
# Unit tests (no NiFi required)
mvn test -pl qubership-nifi-tools/qubership-nifi-kb-builder-tool

# Integration tests (Docker required)
mvn verify -pl qubership-nifi-tools/qubership-nifi-kb-builder-tool-it -am -Ptools-integration-tests -DskipUnitTests=true
```

The integration tests start a real NiFi container and launch the packaged jar in its own process, so
`-am` is required: it packages the tool module before the tests run.

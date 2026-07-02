# qubership-nifi agent instructions

Instructions for AI assistants working on this repository (Cursor, Claude Code, and compatible tools).

## Build & Development Commands

```bash
# Full build
mvn clean install

# Skip tests for faster builds
mvn clean install -DskipUnitTests=true

# Run integration tests
mvn verify

# Run tests for a single module
mvn test -pl qubership-nifi-docs-generator

# Docker image build
docker build .
```

Prerequisites: JDK 21, Maven 3.x, Docker.

Integration tests use Testcontainers and require Docker.
The `TEST_DOCKER_URL` environment variable can override the Docker host.

## Architecture Overview

This is a multi-module Maven project that extends Apache NiFi 2.x with custom processors, services, and tooling.

### Module Roles

- **`qubership-bundle/`** - Core NiFi processors and NAR packaging.
- **`qubership-nifi-common/`** - Shared utilities: JSON validation, Prometheus metrics, common abstractions used across
  modules.
- **`qubership-nifi-bundle-common/`** - Shared code used by NiFi bundles (common processor base classes).
- **`qubership-nifi-db-bundle/`** - Database-specific processors and services (e.g. bulk PostgreSQL loading).
- **`qubership-nifi-bulk-redis-service/`** - Redis bulk operations NiFi controller service.
- **`qubership-nifi-lookup-services/`** - NiFi lookup service implementation.
- **`qubership-nifi-consul-common/`** - Framework-agnostic consul integration library for configuration management and
  automated NiFi flow restore.
- **`qubership-nifi-consul-templates/`** - nifi.properties and logback.xml templates for consul integration. Must be
  updated during upgrades to newer Apache NiFi version.
- **`qubership-consul/`** - Spring Boot-based consul integration for configuration management and automated NiFi flow
  restore.
- **`qubership-nifi-quarkus-consul/`** - Quarkus-based consul integration for configuration management and automated
  NiFi flow restore.
- **`qubership-services/`** - Additional reporting tasks and services.
- **`qubership-nifi-deps/`** - BOM (Bill of Materials) for dependency version management.
- **`qubership-test-bundle/`** - Test-only NiFi components.
- **`qubership-nifi-tools/`** - various tools to automate nifi-related activities: docs generator plugin,
  api-export-tool, openapi-spec-enricher, component-comparator-tool, flow-diff-tool (classifies differences between
  flow exports and reverts NiFi-generated technical identifiers).

### NiFi Component Pattern

Custom processors extend standard NiFi base classes and declare `PropertyDescriptor` and `Relationship` constants.
They are packaged into NAR files via `nifi-nar-maven-plugin`.
Controller services follow the same pattern with interface and implementation split.

## Key Conventions

- PR titles and commit messages must follow Conventional Commits format (enforced by CI).
- The `docs/user-guide.md` is generated: edit `docs/template/user-guide-template.md` or
  `qubership-nifi-tools/qubership-nifi-docs-generator`.
- The `docs/openapi/openapi.json` is generated: edit `qubership-nifi-tools/qubership-nifi-openapi-enricher` to change.
- Use ASCII characters only in source code and documentation. Avoid Unicode punctuation such as em-dashes,
  smart quotes, and arrows; use the ASCII equivalents (`-`, `->`, straight quotes) instead.

# qubership-nifi

qubership-nifi is a service extending Apache NiFi.
Apache NiFi is scalable and configurable dataflow platform.
Compared with Apache NiFi it supports:

- additional environment variables for configuration
- integration with Consul as configuration source for logging levels and other configuration properties
- automated NiFi configuration restore: configuration version to restore can be set via Consul parameter
- additional processors for various tasks not supported in open-source Apache NiFi: bulk DB operations,
  complex JSON extract from DB, rules-based validation
- reporting tasks for additional monitoring of NiFi processes.

## Status

[![Build status](https://github.com/Netcracker/qubership-nifi/actions/workflows/maven-build.yaml/badge.svg)](https://github.com/Netcracker/qubership-nifi/actions/workflows/maven-build.yaml)
[![Autotests status](https://github.com/Netcracker/qubership-nifi/actions/workflows/docker-build-and-test.yml/badge.svg)](https://github.com/Netcracker/qubership-nifi/actions/workflows/docker-build-and-test.yml)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=Netcracker_qubership-nifi&metric=coverage)](https://sonarcloud.io/summary/overall?id=Netcracker_qubership-nifi)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=Netcracker_qubership-nifi&metric=ncloc)](https://sonarcloud.io/summary/overall?id=Netcracker_qubership-nifi)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Netcracker_qubership-nifi&metric=alert_status)](https://sonarcloud.io/summary/overall?id=Netcracker_qubership-nifi)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=Netcracker_qubership-nifi&metric=vulnerabilities)](https://sonarcloud.io/summary/overall?id=Netcracker_qubership-nifi)

## Overview

![qubership-nifi overview](./docs/images/nifi-overview.png)

qubership-nifi is scalable and configurable dataflow platform.
Depending on configuration, it relies on:

- externally provided TLS certificates: TLS is required for security to be enabled,
  so TLS certificates are need for all configurations, except may be configuration for local development
- Consul service: used as property source, as well as for configuring logging levels
- Zookeeper service: required for NiFi clustering, if it's enabled
- identity provider service: required for OIDC integration, if it's enabled
- several file-system based repositories:
  - Persistent Configuration (Database Repository and Flow Configuration).
    See [Administrator Guide](docs/administrator-guide.md) for more details.
  - FlowFile Repository. See [Administrator Guide](docs/administrator-guide.md) for more details.
  - Content Repository. See [Administrator Guide](docs/administrator-guide.md) for more details.
  - Provenance Repository. See [Administrator Guide](docs/administrator-guide.md) for more details.
- qubership-nifi-registry: for version control of NiFi flows, as well as export/import for versioned flows.

qubership-nifi can be started in single node (non-cluster) or cluster configuration.
In clustered configuration each cluster node has its own persistent volumes and secrets holding TLS certificates.
qubership-nifi-registry is not clustered and all cluster nodes must connect to the same service.

## Build

### Prerequisites

Build process requires the following tools:

- Java - JDK 21
- Maven - Maven 3.x, see [maven installation guide](https://maven.apache.org/install.html) for details on how to install
- Docker - any version of Docker Engine or any compatible Docker container runtime.

### Project build

To execute maven build, run:

```shell
mvn clean install
```

Once maven build is completed, you can execute Docker build. To do that, run:

```shell
docker build .
```

## Documentation

### Installation Guide

[Installation Guide](docs/installation-guide.md)

### Administrator's Guide

[Administrator Guide](docs/administrator-guide.md)

### User Guide

[User Guide](docs/user-guide.md)

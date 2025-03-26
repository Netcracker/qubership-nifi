# qubership-nifi

qubership-nifi is a fork of Apache NiFi.
Compared with Apache NiFi it supports:
1. additional environment variables for configuration.
2. integration with Consul as configuration source for logging levels and other configuration properties
3. automated NiFi configuration restore: configuration version to restore can be set via Consul parameter
4. additional processors for various tasks not supported in opensource Apache NiFi: bulk DB operations, complex JSON extract from DB, rules-based validation
5. reporting tasks for additional monitoring of NiFi processes.

## Installation

### Run in qubership-nifi in plain mode

1. Create `docker-compose.yaml` file with contents (where `<image>` should be replaced with qubership-nifi's image, `<some key value>` should be replaced with some string at least 12 characters in length):
```YAML
services:
  nifi:
    image: <image>
    ports:
      - 127.0.0.1:28080:8080
    environment:
      - NAMESPACE=local
      - CONSUL_ENABLED=true
      - CONSUL_URL=consul:8500
      - AUTH=none
      - NIFI_NEW_SENSITIVE_KEY=<some key value>
    container_name: nifi
  consul:
    image: hashicorp/consul:1.20
    ports:
      - 127.0.0.1:18500:8500
    container_name: consul
```
2. Run `docker compose -f docker-compose.yaml create`
3. Run `docker compose -f docker-compose.yaml start`

### Run in qubership-nifi in tls mode

1. Copy the [`docker-compose.yaml`](https://github.com/Netcracker/qubership-nifi/blob/feature/add-docker-compose-for-tls/dev/tls/docker-compose.yml) file to your local directory.  
2. Copy the [`.env`](https://github.com/Netcracker/qubership-nifi/blob/feature/add-docker-compose-for-tls/dev/tls/.env) file to the directory with `docker-compose.yaml`. In the `.env` file you need to set your values for the listed variables before startup. 
3. Run `docker compse -f docker-compose.yaml up`

## User Guide

Apache NiFi is scalable and conifgurable dataflow platform. Refer to Apache NiFi [User Guide](https://nifi.apache.org/docs/nifi-docs/html/user-guide.html) for basic usage.

### Logging levels

You can modify logging levels by:
1. Setting `ROOT_LOG_LEVEL` environment variable. Be mindful that this variable allows you to set only root logging level;
2. Setting logging level for specific package in Consul. Consul property name must start with "logger." followed by package name. Value should be one of logging level supported by Logback: ALL, TRACE, DEBUG, INFO, WARN, ERROR, OFF. Property should be located in one of two locations:
    1. config/${NAMESPACE}/application
    2. config/${NAMESPACE}/qubership-nifi
where `NAMESPACE` is a value of `NAMESPACE` environment variable, or value = `local`, if not set.

To change the logging settings:
1. Change the logger properties in Consul as per your requirements.
2. Wait until the update is propagated to qubership-nifi. It may take up to 1 minute.

### NiFi configuration properties

NiFi configuration properties could be set up in Consul:
1. Property name must start with "nifi."
2. Property should be located in one of two locations:
    1. config/${NAMESPACE}/application
    2. config/${NAMESPACE}/qubership-nifi

To change NiFi properties:
1. Change the NiFi properties in Consul as per your requirements.
2. Restart qubership-nifi.

The detailed description of all supported NiFi properties is available in the Apache NiFi System [Administrator's Guide](https://nifi.apache.org/docs/nifi-docs/html/administration-guide.html).

### Configuration restore

qubership-nifi supports automated configuration restore. Steps below describe restore process:
1. Set up configuration restore in Consul parameter `nifi-restore-version` located in `config/${NAMESPACE}/qubership-nifi`. The parameter must contain name of archived configuration to restore from, e.g. `20250115T120000+0000_flow.json.gz`. The list of archived configuration versions is available in logs.
2. Restart qubership-nifi.

On startup qubership-nifi does the following:
1. Checks if nifi-restore-version property is set
2. If it's set, then if specified file exists or not. If not exists, then prints warning and continues with normal startup.
3. If it's set and file exists in archive, then it moves current configuration to archive and replaces current configuration with specified archived version. Once it's done nifi-restore-version property is automatically cleared in Consul.
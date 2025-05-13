# Installation Guide

qubership-nifi service can be started in docker (or compatible) container runtime with support of `docker compose` command.

Sections below describe typical startup configurations:
1. plain - service without any authentication with plain (HTTP) communications
2. tls - service with mTLS authentication with encrypted (HTTPS) communications.

## Prerequisites

Make sure you have the following tools installed:
1. Docker - any version of Docker Engine or any compatible docker container runtime.
2. Docker Compose - any version of Docker Compose or any compatible docker container runtime.

## Running qubership-nifi in plain mode

The steps below provide instructions on how to start the following services locally:
1. qubership-nifi
2. qubership-nifi-registry
3. Consul.

To start qubership-nifi in plain (unencrypted) mode, do the following:
1. Copy the [`docker-compose.yaml`](../dev/plain/docker-compose.yml) and [`.env`](../dev/plain/.env) files to a local directory
2. In the `.env` file set up values for all variables:

   | Parameter                  | Required | Default | Description                                                                                                                                                                                                 |
   |----------------------------|----------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
   | PATH_TO_LOCAL_VOLUME       | Y        | .       | Defines directory, where local volumes will be located. Subdirectories include:<ul><li>nifi-registry/ - for storing NiFi Registry's data</li><li>nifi/conf/ - for storing nifi flow configuration</li></ul> |
   | NIFI_SENSITIVE_PROPS_KEY   | Y        |         | Sensitive key for NiFi. Some string at least 12 characters in length.                                                                                                                                       |

3. Change image in `docker-compose.yaml`, if needed. By default, `ghcr.io/netcracker/nifi:latest` is used
4. Run `docker compose -f docker-compose.yaml up`.

### Accessing NiFi UI in plain mode

Navigate to `http://localhost:28080/nifi/` to access qubership-nifi.

## Running qubership-nifi in TLS mode

The steps below provide instructions on how to start the following services locally:
1. qubership-nifi
2. qubership-nifi-registry
3. Consul
4. Apache NiFi Toolkit (for TLS certificates generation).

To start qubership-nifi in TLS mode, do the following:
1. Copy the [`docker-compose.yaml`](../dev/tls/docker-compose.yml) and [`.env`](../dev/tls/.env) files to a local directory
2. In the `.env` file set up values for all variables:

    | Parameter                  | Required | Default | Description                                                                                                                                                                                                                                                          |
    |----------------------------|----------|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
    | PATH_TO_LOCAL_VOLUME       | Y        | .       | Defines directory, where local volumes will be located. Subdirectories include:<ul><li>nifi-registry/ - for storing NiFi Registry's data</li><li>nifi/conf/ - for storing nifi flow configuration</li><li>tls-certificate/ - for storing TLS certificates</li></ul>  |
    | TRUSTSTORE_PASSWORD        | Y        |         | Defines password for keystore with trusted certificates. It'll be created during the first run.                                                                                                                                                                      |
    | KEYSTORE_PASSWORD_NIFI     | Y        |         | Defines password for qubership-nifi keystore with server certificates. It'll be created during the first run.                                                                                                                                                        |
    | KEYSTORE_PASSWORD_NIFI_REG | Y        |         | Defines password for qubership-nifi-registry keystore with server certificates. It'll be created during the first run.                                                                                                                                               |
    | NIFI_SENSITIVE_PROPS_KEY   | Y        |         | Sensitive key for NiFi. Some string at least 12 characters in length.                                                                                                                                                                                                |

3. Change image in `docker-compose.yaml`, if needed. By default, `ghcr.io/netcracker/nifi:latest` is used
4. Run `docker compose -f docker-compose.yaml up`.

### Accessing NiFi UI in TLS mode

Navigate to `https://localhost:8443/nifi/` to access qubership-nifi.
You'll be prompted to use client certificate for authentication.
See sections below on certificates configuration.

#### Import CA certificate

Self-signed CA certificate is generated in `$PATH_TO_LOCAL_VOLUME/tls-certificate/nifi-cert.pem`.
You can import it in your browser as trusted and then open new window or restart browser for changes to be applied.

#### Import client certificate

Client certificate is generated in `$PATH_TO_LOCAL_VOLUME/tls-certificate/CN=admin_OU=NIFI.p12` in PKCS12 (PFX) format.
Password for is available in `$PATH_TO_LOCAL_VOLUME/tls-certificate/CN=admin_OU=NIFI.password`.
To access qubership-nifi, you need to import it as Personal certificate in your browser.
After that you may need to open new window or restart browser for changes to be applied.
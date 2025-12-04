# Administrator's Guide

Qubership-nifi is built on top of Apache NiFi.
Apache NiFi is scalable and configurable dataflow platform.
This guide contains details on features added or customized by qubership-nifi.
Refer to Apache NiFi [System Administrator’s Guide](https://nifi.apache.org/docs/nifi-docs/html/administration-guide.html) for details on standard features.

## Environment variables

The table below describes environment variables supported by qubership-nifi.

| Parameter                            | Required                           | Default        | Description                                                                                                                                                                                                                                                |
|--------------------------------------|------------------------------------|----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| NAMESPACE                            | Y                                  | local          | Kubernetes namespace, where service is deployed.                                                                                                                                                                                                           |
| MICROSERVICE_NAME                    | N                                  | qubership-nifi | Service name to use.                                                                                                                                                                                                                                       |
| CONSUL_ENABLED                       | Y                                  | true           | Defines, if Consul integration is enabled (`true`) or not (`false`)                                                                                                                                                                                        |
| CONSUL_URL                           | Y                                  |                | URL to access Consul service. Must be in format: `<hostname>:<port>`.                                                                                                                                                                                      |
| CONSUL_CONFIG_JAVA_OPTIONS           | N                                  |                | A list of additional Java startup arguments for auxiliary application used for Consul integration.                                                                                                                                                         |
| CONSUL_ACL_TOKEN                     | N                                  |                | An access token that is used in Consul to manage permissions and security for interactions between NiFi and Consul.                                                                                                                                        |
| NIFI_NEW_SENSITIVE_KEY               | Y                                  |                | Key used for encrypting sensitive properties in the NiFi configuration (flow.json.gz). Must be at least 12 characters long. Do not change this value after the initial deployment; otherwise, an error will occur during startup.                          |
| NIFI_PREVIOUS_SENSITIVE_KEY          | N                                  |                | Previous value of `NIFI_NEW_SENSITIVE_KEY`. Must be set, when changing value for `NIFI_NEW_SENSITIVE_KEY` to new one.                                                                                                                                      |
| AUTH                                 | N                                  |                | Authentication method to support. One of: tls (mTLS), oidc (mTLS and OIDC), ldap (mTLS and LDAP).                                                                                                                                                          |
| INITIAL_ADMIN_IDENTITY               | Y (if AUTH = oidc or tls or ldap)  |                | The identity of an initial admin user that will be granted access to the UI and given the ability to create additional users, groups, and policies. The value of this property could be a DN when using certificates or LDAP, or a Kerberos principal.     |
| INITIAL_USER_IDENTITY                | Y (if AUTH = oidc or tls or ldap)  |                | The identity of an initial user with read-only access to the UI.                                                                                                                                                                                           |
| OIDC_DISCOVERY_URL_NEW               | Y (if AUTH = oidc)                 |                | The Discovery Configuration URL for the OpenID Connect Provider                                                                                                                                                                                            |
| OIDC_CLIENT_ID                       | Y (if AUTH = oidc)                 |                | The Client ID for NiFi registered with the OpenID Connect Provider                                                                                                                                                                                         |
| OIDC_CLIENT_SECRET                   | Y (if AUTH = oidc)                 |                | The Client Secret for NiFi registered with the OpenID Connect Provider                                                                                                                                                                                     |
| KEY_PASSWORD                         | Y (if AUTH = oidc or tls or ldap)  |                | The key password for secret key stored in the keystore. May contain any printable characters except for `\` (backslash).                                                                                                                                   |
| KEYSTORE_TYPE                        | Y (if AUTH = oidc or tls or ldap)  |                | Server keystore type. One of: JKS, PKCS12.                                                                                                                                                                                                                 |
| KEYSTORE_PATH                        | Y (if AUTH = oidc or tls or ldap)  |                | Server keystore file.                                                                                                                                                                                                                                      |
| KEYSTORE_PASSWORD                    | Y (if AUTH = oidc or tls or ldap)  |                | The keystore password for the keystore. May contain any printable characters except for `\` (backslash).                                                                                                                                                   |
| TRUSTSTORE_TYPE                      | Y (if AUTH = oidc or tls or ldap)  |                | Truststore keystore type. One of: JKS, PKCS12.                                                                                                                                                                                                             |
| TRUSTSTORE_PATH                      | Y (if AUTH = oidc or tls or ldap)  |                | Truststore file.                                                                                                                                                                                                                                           |
| TRUSTSTORE_PASSWORD                  | Y (if AUTH = oidc or tls or ldap)  |                | The truststore password. May contain any printable characters except for `\` (backslash).                                                                                                                                                                  |
| NIFI_WEB_HTTP_PORT                   | N                                  | 8080           | The HTTP port.                                                                                                                                                                                                                                             |
| NIFI_WEB_HTTPS_PORT                  | N                                  | 8443           | The HTTPS host. It is blank by default.                                                                                                                                                                                                                    |
| NIFI_WEB_HTTP_HOST                   | N                                  | 0.0.0.0        | The HTTP host. It is blank by default.                                                                                                                                                                                                                     |
| NIFI_JVM_HEAP_INIT                   | N                                  | 512m           | Initial heap memory reserved by JVM. Defines value for Xms JVM startup argument.                                                                                                                                                                           |
| NIFI_JVM_HEAP_MAX                    | N                                  | 512m           | Maximum heap memory reserved by JVM. Defines value for Xmx JVM startup argument.                                                                                                                                                                           |
| NIFI_CLUSTER_IS_NODE                 | N                                  |                | Defines whether this node belongs to NiFi cluster or not.                                                                                                                                                                                                  |
| ZOOKEEPER_ADDRESS                    | Y (if NIFI_CLUSTER_IS_NODE = true) |                | ZooKeeper address in format: `<hostname>:<port>`.                                                                                                                                                                                                          |
| NIFI_ZK_ROOT_NODE                    | Y (if NIFI_CLUSTER_IS_NODE = true) |                | ZooKeeper root node name to place nifi cluster data. Must be valid zk path.                                                                                                                                                                                |
| ZOOKEEPER_SSL_ENABLED                | N                                  |                | Defines whether to use TLS, when connecting to ZooKeeper. Trusted certificates for ZooKeeper connection must be put into `Trusted certificates` directory.                                                                                                 |
| ZOOKEEPER_CLIENT_KEYSTORE            | N                                  |                | File path to keystore used for client connections to ZooKeeper. This variable must be set, if x509 authentication should be used to access ZooKeeper and `ZOOKEEPER_SSL_ENABLED` = `true`.                                                                 |
| ZOOKEEPER_CLIENT_KEYSTORE_TYPE       | N                                  | PKCS12         | Keystore type for keystore specified in variable `ZOOKEEPER_CLIENT_KEYSTORE`.                                                                                                                                                                              |
| ZOOKEEPER_CLIENT_KEYSTORE_PASSWORD   | N                                  |                | Password for keystore specified in variable `ZOOKEEPER_CLIENT_KEYSTORE`. Must be set, if `ZOOKEEPER_CLIENT_KEYSTORE` is not empty. May contain any printable characters except for `\` (backslash), `&`.                                                   |
| NIFI_TLS_DEBUG                       | N                                  |                | Enables TLS debug logging in JVM, if set to non-empty value. Adds `-Djavax.net.debug=ssl,handshake` to Java startup arguments.                                                                                                                             |
| NIFI_DEBUG_NATIVE_MEMORY             | N                                  |                | Enables Native Memory Tracking feature in JVM, if set to non-empty value. Adds `-XX:NativeMemoryTracking=detail` to Java startup arguments.                                                                                                                |
| NIFI_DEBUG_JIT_LOGGING               | N                                  |                | Enables JIT logging feature in JVM, if set to non-empty value. Adds `-XX:+PrintCompilation` to Java startup arguments.                                                                                                                                     |
| HTTP_AUTH_PROXYING_DISABLED_SCHEMES  | N                                  |                | Sets non-standard proxying disabledSchemes in JVM, if this variable is not empty. Adds `-Djdk.http.auth.proxying.disabledSchemes=<value>` to Java startup arguments.                                                                                       |
| HTTP_AUTH_TUNNELING_DISABLED_SCHEMES | N                                  |                | Sets non-standard tunneling disabledSchemes in JVM, if this variable is not empty. Adds `-Djdk.http.auth.tunneling.disabledSchemes=<value>` to Java startup arguments.                                                                                     |
| NIFI_ADDITIONAL_JVM_ARGS             | N                                  |                | A list of additional Java startup arguments. Must be valid list of arguments separated by spaces just like in command-line.                                                                                                                                |
| X_JAVA_ARGS                          | N                                  |                | A list of additional Java startup arguments. Must be valid list of arguments separated by spaces just like in command-line.                                                                                                                                |
| NIFI_CLUSTER_NODE_PROTOCOL_PORT      | Y (if NIFI_CLUSTER_IS_NODE = true) |                | The node’s cluster protocol port. Any non-used port > 1024.                                                                                                                                                                                                |
| NIFI_ELECTION_MAX_WAIT               | N                                  | 5 mins         | Specifies the amount of time to wait before electing a Flow as the "correct" Flow. If the number of Nodes that have voted is equal to the number specified by the nifi.cluster.flow.election.max.candidates property, the cluster will not wait this long. |
| NIFI_WEB_PROXY_HOST                  | Y (if NIFI_CLUSTER_IS_NODE = true) |                | A comma separated list of allowed HTTP Host header values to consider when NiFi is running securely and will be receiving requests to a different `host[:port]` than it is bound to.                                                                       |

## Extension points

The qubership-nifi Docker image provides several predefined extension points that can be used to customize its behavior without significant changes to other parts of the image.
The table below lists these extension points and their descriptions.

| Extension point        | Path                                       | Description                                                                                                                                                                                               |
|------------------------|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Before Start           | /opt/nifi/scripts/before_start.sh          | Shell script to execute any operations before qubership-nifi startup.                                                                                                                                     |
| Start Extensions       | /opt/nifi/scripts/start_additional_func.sh | Shell script, which customizes several aspects via functions: set_additional_properties, call_additional_libs, set_additional_properties2, load_additional_resources, redirect_logs, check_java_ret_code. |

## Volumes and directories

The qubership-nifi Docker image defines several volumes for storing data and directories for storing or injecting data.
The table below lists these volumes and directories with their descriptions.

| Name                     | Type      | Path                                         | Description                                                                                                                                                                                                                  |
|--------------------------|-----------|----------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Service configuration    | Volume    | /opt/nifi/nifi-current/conf                  | Contains configuration files for qubership-nifi startup.                                                                                                                                                                     |
| Logs                     | Volume    | /opt/nifi/nifi-current/logs                  | Stores log files.                                                                                                                                                                                                            |
| Run                      | Volume    | /opt/nifi/nifi-current/run                   | Stores current nifi pid and status file.                                                                                                                                                                                     |
| Work                     | Volume    | /opt/nifi/nifi-current/work                  | Stores data used by nifi in runtime.                                                                                                                                                                                         |
| Content Repository       | Directory | /opt/nifi/nifi-current/persistent_data       | Stores Content Repository and local state for nifi flows. See Apache NiFi [System Administrator’s Guide](https://nifi.apache.org/docs/nifi-docs/html/administration-guide.html) for details.                                 |
| FlowFile Repository      | Directory | /opt/nifi/nifi-current/flowfile_repository   | Stores FlowFile Repository. See Apache NiFi [System Administrator’s Guide](https://nifi.apache.org/docs/nifi-docs/html/administration-guide.html) for details.                                                               |
| Provenance Repository    | Directory | /opt/nifi/nifi-current/provenance_repository | Stores Provenance Repository. See Apache NiFi [System Administrator’s Guide](https://nifi.apache.org/docs/nifi-docs/html/administration-guide.html) for details.                                                             |
| Persistent Configuration | Directory | /opt/nifi/nifi-current/persistent_conf       | Stores nifi configuration (flow.json.gz), users, access policies and Database Repository. See Apache NiFi [System Administrator’s Guide](https://nifi.apache.org/docs/nifi-docs/html/administration-guide.html) for details. |
| Server keystore          | File      | `$KEYSTORE_PATH`                             | Contains TLS keystore (keystore.p12 or keystore.jks). Required for startup with AUTH = oidc, tls, ldap.                                                                                                                      |
| Truststore               | File      | `$TRUSTSTORE_PATH`                           | Contains TLS truststore (truststore.p12 or truststore.jks). Required for startup with AUTH = oidc, tls, ldap.                                                                                                                |
| Trusted certificates     | Directory | /tmp/cert                                    | Contains trusted certificates in PEM-format that will be imported into Java trusted certificates store.                                                                                                                      |
| NAR Autoloader           | Directory | /opt/nifi/nifi-current/extensions            | Directory for auto loading custom NARs. NARs placed in this directory will be automatically loaded in runtime.                                                                                                               |

## Changing logging levels

You can modify logging levels by:
1. Setting the `ROOT_LOG_LEVEL` environment variable. Note that this variable only sets the root logging level.
2. Setting the logging level for a specific package in Consul. The Consul property name must start with `logger.` followed by the package name. The value should be one of the logging levels supported by Logback: ALL, TRACE, DEBUG, INFO, WARN, ERROR, OFF. The property should be located in one of the following locations:
    1. config/${NAMESPACE}/application
    2. config/${NAMESPACE}/qubership-nifi
       where `NAMESPACE` is the value of the `NAMESPACE` environment variable, or local if not set.

To change the logging settings:
1. Update the logger properties in Consul as needed.
2. Wait until the update is propagated to qubership-nifi. This may take up to 1 minute.

## Changing NiFi configuration properties

NiFi configuration properties can be set in Consul:
1. The property name must start with `nifi.`
2. The property should be located in one of the following locations:
    1. config/${NAMESPACE}/application
    2. config/${NAMESPACE}/qubership-nifi

To change NiFi properties:
1. Update the NiFi properties in Consul as needed.
2. Restart the qubership-nifi container.

A detailed description of all supported NiFi properties is available in the Apache NiFi [System Administrator's Guide](https://nifi.apache.org/docs/nifi-docs/html/administration-guide.html).

## NiFi configuration restore

qubership-nifi supports automated configuration restore.

The steps below describe the restore process:
1. Set the version to restore in the Consul parameter `nifi-restore-version` located in `config/${NAMESPACE}/qubership-nifi`. The parameter must contain the name of the archived configuration to restore from, e.g., `20250115T120000+0000_flow.json.gz`. The list of archived configuration versions is printed in the logs during service startup.
2. Restart the qubership-nifi container.

On startup, qubership-nifi performs the following:
1. Checks if the `nifi-restore-version` parameter is set
2. If the parameter is set and the specified archive file does not exist, a warning is printed in the logs and normal startup continues using the current configuration.
3. If the parameter is set and the specified archive file exists, the current configuration is moved to the archive and replaced with the specified archived version. Once complete, the `nifi-restore-version` parameter is automatically cleared in Consul.

## Cluster Configuration

Apache NiFi provides one option for starting a cluster: ZooKeeper for leader election and cluster-wide state storage.

### ZooKeeper-based Cluster Configuration

The following parameters must be set to configure a NiFi cluster using ZooKeeper:
1. environment variable `ZOOKEEPER_ADDRESS` - host and port to access ZooKeeper.
2. environment variable `NIFI_ZK_ROOT_NODE` - path to the root node on ZooKeeper. Must be defined to avoid conflicts with other applications or NiFi cluster instances.
3. environment variable `ZOOKEEPER_SSL_ENABLED` - set to `true`, if ZooKeeper access is secured with TLS.
4. environment variable `ZOOKEEPER_CLIENT_KEYSTORE` - path to the keystore with the private key and certificate used to access ZooKeeper. Set only if ZooKeeper is configured to use x509 authentication and `ZOOKEEPER_SSL_ENABLED = true`.
5. environment variable `ZOOKEEPER_CLIENT_KEYSTORE_TYPE` - type for the keystore specified by `ZOOKEEPER_CLIENT_KEYSTORE`. Set only if ZooKeeper is configured to use x509 authentication and `ZOOKEEPER_SSL_ENABLED = true`.
6. environment variable `ZOOKEEPER_CLIENT_KEYSTORE_PASSWORD` - password for the keystore specified by `ZOOKEEPER_CLIENT_KEYSTORE`. Set only if ZooKeeper is configured to use x509 authentication and `ZOOKEEPER_SSL_ENABLED = true`.

There are three supported configurations for ZooKeeper:
1. Plain with anonymous access - set `ZOOKEEPER_ADDRESS` and `NIFI_ZK_ROOT_NODE` only.
2. Secured (TLS) with anonymous access - set `ZOOKEEPER_ADDRESS`, `NIFI_ZK_ROOT_NODE`, `ZOOKEEPER_SSL_ENABLED = true`, and add trusted certificates for ZooKeeper into the`Trusted certificates` directory.
3. secured (TLS) with x509 authentication - set `ZOOKEEPER_ADDRESS`, `NIFI_ZK_ROOT_NODE`, `ZOOKEEPER_SSL_ENABLED = true`, `ZOOKEEPER_CLIENT_KEYSTORE`, `ZOOKEEPER_CLIENT_KEYSTORE_TYPE`, `ZOOKEEPER_CLIENT_KEYSTORE_PASSWORD`, add trusted certificates for ZooKeeper into the `Trusted certificates` directory, add the keystore referenced by `ZOOKEEPER_CLIENT_KEYSTORE`.

When using ZooKeeper, Apache NiFi creates ZNodes within the path specified by `NIFI_ZK_ROOT_NODE`, o it requires at least Create, Read, and Write privileges for this path if it was created by another application.
For more information on Apache NiFi behavior regarding ZooKeeper, refer to the [NiFi System Administrator’s Guide](https://nifi.apache.org/docs/nifi-docs/html/administration-guide.html).

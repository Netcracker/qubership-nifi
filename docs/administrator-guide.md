# Administrator's Guide

Qubership-nifi is built on top of Apache NiFi.
Apache NiFi is scalable and configurable dataflow platform.
This guide contains details on features added or customized by qubership-nifi.
Refer to Apache NiFi [System Administrator’s Guide](https://nifi.apache.org/docs/nifi-docs/html/administration-guide.html) for details on standard features.

## Environment variables

The table below describes environment variables supported by qubership-nifi.

| Parameter                            | Required                          | Default | Description                                                                                                                                                                                                                                            |
|--------------------------------------|-----------------------------------|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| NAMESPACE                            | Y                                 | local   | Kubernetes namespace, where service is deployed.                                                                                                                                                                                                       |
| CONSUL_ENABLED                       | Y                                 | true    | Defines, if Consul integration is enabled (`true`) or not (`false`)                                                                                                                                                                                    |
| CONSUL_URL                           | Y                                 |         | URL to access Consul service. Must be in format: `<hostname>:<port>`.                                                                                                                                                                                  |
| CONSUL_CONFIG_JAVA_OPTIONS           | N                                 |         | A list of additional java startup arguments for auxiliary application used for Consul integration.                                                                                                                                                     |
| AUTH                                 | N                                 |         | Authentication method to support. One of: tls (mTLS), oidc (mTLS and OIDC), ldap (mTLS and LDAP).                                                                                                                                                      |
| INITIAL_ADMIN_IDENTITY               | Y (if AUTH = oidc or tls or ldap) |         | The identity of an initial admin user that will be granted access to the UI and given the ability to create additional users, groups, and policies. The value of this property could be a DN when using certificates or LDAP, or a Kerberos principal. |
| INITIAL_USER_IDENTITY                | Y (if AUTH = oidc or tls or ldap) |         | The identity of an initial user with read-only access to the UI.                                                                                                                                                                                       |
| OIDC_DISCOVERY_URL_NEW               | Y (if AUTH = oidc)                |         | The Discovery Configuration URL for the OpenID Connect Provider                                                                                                                                                                                        |
| OIDC_CLIENT_ID                       | Y (if AUTH = oidc)                |         | The Client ID for NiFi registered with the OpenID Connect Provider                                                                                                                                                                                     |
| OIDC_CLIENT_SECRET                   | Y (if AUTH = oidc)                |         | The Client Secret for NiFi registered with the OpenID Connect Provider                                                                                                                                                                                 |
| KEY_PASSWORD                         | Y (if AUTH = oidc or tls or ldap) |         | The key password for secret key stored in the keystore.                                                                                                                                                                                                |
| KEYSTORE_TYPE                        | Y (if AUTH = oidc or tls or ldap) |         | Server keystore type. One of: JKS, PKCS12.                                                                                                                                                                                                             |
| KEYSTORE_PATH                        | Y (if AUTH = oidc or tls or ldap) |         | Server keystore file.                                                                                                                                                                                                                                  |
| KEYSTORE_PASSWORD                    | Y (if AUTH = oidc or tls or ldap) |         | The keystore password for the keystore.                                                                                                                                                                                                                |
| TRUSTSTORE_TYPE                      | Y (if AUTH = oidc or tls or ldap) |         | Truststore keystore type. One of: JKS, PKCS12.                                                                                                                                                                                                         |
| TRUSTSTORE_PATH                      | Y (if AUTH = oidc or tls or ldap) |         | Truststore file.                                                                                                                                                                                                                                       |
| TRUSTSTORE_PASSWORD                  | Y (if AUTH = oidc or tls or ldap) |         | The truststore password.                                                                                                                                                                                                                               |
| NIFI_WEB_HTTP_PORT                   | N                                 | 8080    | The HTTP port.                                                                                                                                                                                                                                         |
| NIFI_WEB_HTTPS_PORT                  | N                                 | 8443    | The HTTPS host. It is blank by default.                                                                                                                                                                                                                |
| NIFI_WEB_HTTP_HOST                   | N                                 | 0.0.0.0 | The HTTP host. It is blank by default.                                                                                                                                                                                                                 |
| NIFI_JVM_HEAP_INIT                   | N                                 | 512m    | Initial heap memory reserved by JVM. Defines value for Xms JVM startup argument.                                                                                                                                                                       |
| NIFI_JVM_HEAP_MAX                    | N                                 | 512m    | Maximum heap memory reserved by JVM. Defines value for Xmx JVM startup argument.                                                                                                                                                                       |
| NIFI_TLS_DEBUG                       | N                                 |         | Enables TLS debug logging in JVM, if set to non-empty value. Adds `-Djavax.net.debug=ssl,handshake` to Java startup arguments.                                                                                                                         |
| NIFI_DEBUG_NATIVE_MEMORY             | N                                 |         | Enables Native Memory Tracking feature in JVM, if set to non-empty value. Adds `-XX:NativeMemoryTracking=detail` to Java startup arguments.                                                                                                            |
| NIFI_DEBUG_JIT_LOGGING               | N                                 |         | Enables JIT logging feature in JVM, if set to non-empty value. Adds `-XX:+PrintCompilation` to Java startup arguments.                                                                                                                                 |
| HTTP_AUTH_PROXYING_DISABLED_SCHEMES  | N                                 |         | Sets non-standard proxying disabledSchemes in JVM, if this variable is not empty. Adds `-Djdk.http.auth.proxying.disabledSchemes=<value>` to Java startup arguments.                                                                                   |
| HTTP_AUTH_TUNNELING_DISABLED_SCHEMES | N                                 |         | Sets non-standard tunneling disabledSchemes in JVM, if this variable is not empty. Adds `-Djdk.http.auth.tunneling.disabledSchemes=<value>` to Java startup arguments.                                                                                 |
| NIFI_ADDITIONAL_JVM_ARGS             | N                                 |         | A list of additional java startup arguments. Must be valid list of arguments separated by spaces just like in command-line.                                                                                                                            |
| X_JAVA_ARGS                          | N                                 |         | A list of additional java startup arguments. Must be valid list of arguments separated by spaces just like in command-line.                                                                                                                            |

## Extension points

Qubership-nifi docker image has several predefined extension points that could be used to customize its
behavior without significant changes to other parts of the image.
The table below provides a list of such extension points and their description.

| Extension point        | Path                                       | Description                                                                                                                                                                                               |
|------------------------|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Before Start           | /opt/nifi/scripts/before_start.sh          | Shell script to execute any operations before qubership-nifi startup.                                                                                                                                     |
| Start Extensions       | /opt/nifi/scripts/start_additional_func.sh | Shell script, which customizes several aspects via functions: set_additional_properties, call_additional_libs, set_additional_properties2, load_additional_resources, redirect_logs, check_java_ret_code. |

## Volumes and directories

Qubership-nifi docker image has several volumes that are used for storing data
and several directories that used for storing or injecting data.
The table below provides a list of volumes and directories and their description.

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

## Changing logging levels

You can modify logging levels by:
1. Setting `ROOT_LOG_LEVEL` environment variable. Be mindful that this variable allows you to set only root logging level;
2. Setting logging level for specific package in Consul. Consul property name must start with "logger." followed by package name. Value should be one of logging level supported by Logback: ALL, TRACE, DEBUG, INFO, WARN, ERROR, OFF. Property should be located in one of two locations:
    1. config/${NAMESPACE}/application
    2. config/${NAMESPACE}/qubership-nifi
       where `NAMESPACE` is a value of `NAMESPACE` environment variable, or value = `local`, if not set.

To change the logging settings:
1. Change the logger properties in Consul as per your requirements.
2. Wait until the update is propagated to qubership-nifi. It may take up to 1 minute.

## Changing NiFi configuration properties

NiFi configuration properties could be set up in Consul:
1. Property name must start with "nifi."
2. Property should be located in one of two locations:
    1. config/${NAMESPACE}/application
    2. config/${NAMESPACE}/qubership-nifi

To change NiFi properties:
1. Change the NiFi properties in Consul as per your requirements.
2. Restart qubership-nifi container.

The detailed description of all supported NiFi properties is available in the Apache NiFi System [Administrator's Guide](https://nifi.apache.org/docs/nifi-docs/html/administration-guide.html).

## NiFi configuration restore

qubership-nifi supports automated configuration restore.













Steps below describe restore process:
1. Set up version to restore in Consul parameter `nifi-restore-version` located in `config/${NAMESPACE}/qubership-nifi`. The parameter must contain name of archived configuration to restore from, e.g. `20250115T120000+0000_flow.json.gz`. The list of archived configuration versions is printed in logs during service startup.
2. Restart qubership-nifi container.

On startup qubership-nifi performs the following:
1. Checks if `nifi-restore-version` parameter is set
2. If parameter is set and the specified archive file does not exist, then prints warning in logs and continues with normal startup using current configuration
3. If parameter is set and the specified archive file exists, then it moves current configuration to archive and replaces current configuration with the specified archived version. Once it's done, the `nifi-restore-version` parameter is automatically cleared in Consul.
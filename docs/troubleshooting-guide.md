# Troubleshooting Guide

This guide describes common startup errors in qubership-nifi, their causes and how to resolve them.

## Summary

| Error type | Exit code | Description | Details |
| --- | --- | --- | --- |
| Invalid JVM arguments | 3 | Incorrect set of JVM arguments in `NIFI_ADDITIONAL_JVM_ARGS`/`X_JAVA_ARGS`. | [Invalid JVM arguments](#1-invalid-jvm-arguments) |
| Invalid Consul URL | - | `CONSUL_URL` is incorrect or Consul is not reachable, Consul integration application terminates prematurely. | [Invalid Consul URL](#2-invalid-consul-url) |
| Invalid NIFI_NEW_SENSITIVE_KEY | 3 | `NIFI_NEW_SENSITIVE_KEY` does not match the key used on previous startup. | [Invalid NIFI_NEW_SENSITIVE_KEY](#3-invalid-nifi_new_sensitive_key) |
| Invalid flow.json.gz | 1 | The persisted NiFi flow configuration (`flow.json.gz`) is corrupted and cannot be parsed. | [Invalid flow.json.gz](#4-invalid-flowjsongz) |
| Invalid Certificate | 1 (or N/A) | Keystore/truststore configuration is incorrect (e.g. wrong password, missing or incomplete truststore). | [Invalid Certificate](#5-invalid-certificate) |

## 1. Invalid JVM arguments

### Symptom

NiFi fails to start and the logs contain a message similar to:

```text
[2026-07-29T12:47:41.000] [ERROR] [request_id=] [tenant_id=] [thread=main] [class=c.n.c.n.extensions.start.sh] ERROR: Invalid JVM arguments in NIFI_ADDITIONAL_JVM_ARGS + X_JAVA_ARGS: -XX:+UseG1GC -XX:+UseParallelGC
[2026-07-29T12:47:41.000] [ERROR] [request_id=] [tenant_id=] [thread=main] [class=c.n.c.n.extensions.start.sh] Picked up JAVA_TOOL_OPTIONS: -XX:+UseParallelGC
Error occurred during initialization of VM
Multiple garbage collectors selected
```

The generic error message pattern is:

```text
ERROR: Invalid JVM arguments in <parameter name>...
```

The container exits with **exit code 3**.

### Cause

Incorrect (conflicting or otherwise invalid) set of JVM arguments in `NIFI_ADDITIONAL_JVM_ARGS` and/or `X_JAVA_ARGS`
(for example, two different garbage collectors selected at once, such as `-XX:+UseG1GC` together with
`-XX:+UseParallelGC`, possibly combined with `JAVA_TOOL_OPTIONS`).

### Resolution

1. Check the values of the `NIFI_ADDITIONAL_JVM_ARGS` and `X_JAVA_ARGS` environment variables and remove conflicting
   or duplicate JVM arguments (for example, keep only one `-XX:+Use...GC` option).
2. Check if `JAVA_TOOL_OPTIONS` is set (in the environment or picked up from a base image) and make sure it does not
   conflict with `NIFI_ADDITIONAL_JVM_ARGS` / `X_JAVA_ARGS`.
3. Restart NiFi after correcting the arguments.

## 2. Invalid Consul URL

### Symptom

The auxiliary Consul integration application fails to start and terminates prematurely. The exact log format depends
on `NIFI_CONSUL_INT_FRAMEWORK`:

- With `NIFI_CONSUL_INT_FRAMEWORK=spring`, the logs contain a Spring Boot failure analysis report similar to:

  ```text
  ***************************
  APPLICATION FAILED TO START
  ***************************

  Description:

  Config data resource '[ConsulConfigDataResource@... context = 'config/local/qubership-nifi,default/', ...]' via location 'consul:consul-asda:8500' does not exist

  Action:

  Check that the value 'consul:consul-asda:8500' at class path resource [application.yaml] from qubership-nifi-consul-application.jar - 66:13 is correct, or prefix it with 'optional:'

  [...] ERROR: Consul app java process has terminated prematurely. See logs for details...
  ```

- With `NIFI_CONSUL_INT_FRAMEWORK=quarkus` (the default), the Quarkus-based application logs a different error
  format, but the same underlying issue applies: it fails to connect to the address configured in `CONSUL_URL`, and
  the process terminates prematurely.

In both cases, the qubership-nifi startup log contains a line similar to:

```text
[...] ERROR: Consul app java process has terminated prematurely. See logs for details...
```

### Cause

The `CONSUL_URL` environment variable (or the hostname/port it resolves to) is incorrect or not reachable, so the
Consul integration application (Spring or Quarkus based, depending on `NIFI_CONSUL_INT_FRAMEWORK`) cannot connect to
Consul and terminates prematurely.

### Resolution

1. Verify that `CONSUL_URL` is set correctly. For `NIFI_CONSUL_INT_FRAMEWORK=spring`, the format must be
   `<hostname>:<port>`; for `quarkus` it must include the protocol, `<protocol>://<hostname>:<port>`.
2. Verify that the Consul host is reachable from the NiFi pod/container (DNS resolution, network policies).
3. Verify that `CONSUL_ACL_TOKEN` (if used) matches the target Consul instance configuration.
4. Restart NiFi after correcting `CONSUL_URL`.

## 3. Invalid NIFI_NEW_SENSITIVE_KEY

### Symptom

NiFi fails to start with the following error message:

```text
oldKeyHash does not match newKeyHash. Probably NIFI_NEW_SENSITIVE_KEY is different from previously used key. Check NIFI_NEW_SENSITIVE_KEY for correctness. Terminating start-up...
```

The container exits with **exit code 3**.

### Cause

Incorrect value of the `NIFI_NEW_SENSITIVE_KEY` environment variable: it does not match the key used on the previous
startup.

### Resolution

1. Set `NIFI_NEW_SENSITIVE_KEY` back to the value that was used on the initial deployment.
2. Restart NiFi after correcting `NIFI_NEW_SENSITIVE_KEY`.

## 4. Invalid flow.json.gz

### Symptom

NiFi fails to start, and the logs contain a startup failure similar to:

```text
[...] [ERROR] [...] [class=org.apache.nifi.web.server.JettyServer] [method=startUpFailure] [...] Failed to start Server
org.apache.nifi.controller.serialization.FlowSerializationException: Could not parse flow as a VersionedDataflow
        at org.apache.nifi.cluster.protocol.StandardDataFlow.parseVersionedDataflow(StandardDataFlow.java:166)
        ...
Caused by: com.fasterxml.jackson.databind.JsonMappingException: Unexpected character ('n' (code 110)): was expecting comma to separate Object entries
 at [Source: REDACTED (...); line: 1, column: 2125] (through reference chain: org.apache.nifi.controller.flow.VersionedDataflow["registries"]->java.util.ArrayList[3])
        ...
Caused by: com.fasterxml.jackson.core.JsonParseException: Unexpected character ('n' (code 110)): was expecting comma to separate Object entries
        ...
```

The exact exception message may vary depending on the kind of corruption in the file.

The container exits with **exit code 1**.

### Cause

The persisted NiFi flow configuration (`flow.json.gz`) is corrupted or contains invalid JSON, so NiFi cannot parse it
as a `VersionedDataflow` on startup.

### Resolution

Restore the flow configuration from a previously archived version using the automated configuration restore feature
described in the [NiFi configuration restore](administrator-guide.md#nifi-configuration-restore) section of the
Administrator's Guide:

1. Set the version to restore in the Consul parameter `nifi-restore-version` located in `config/${NAMESPACE}/qubership-nifi`,
   using the format `<timestamp>_flow.json.gz` (the list of available archived versions is printed in the logs during
   service startup).
2. Restart the qubership-nifi container. On startup, the current (corrupted) configuration is moved to the archive
   and replaced with the specified archived version, after which the `nifi-restore-version` parameter is automatically
   cleared in Consul.

## 5. Invalid Certificate

This section covers two known variants of certificate-related failures.

### Symptom A: NiFi crashes on startup with no clear error message

NiFi fails to start, but the startup log does not contain a clear error message describing the cause. Only NAR
loading messages are printed, followed by a crash dump notice, for example:

```text
[2026-07-30T13:00:17.009][INFO ] [...] [class=org.apache.nifi.nar.NarClassLoaders] [method=createNarClassLoader] [...] Loaded NAR file: /opt/nifi/nifi-current/./work/nar/extensions/nifi-dropbox-processors-nar-2.9.0.nar-unpacked as class loader org.apache.nifi.nar.NarClassLoader[./work/nar/extensions/nifi-dropbox-processors-nar-2.9.0.nar-unpacked]
[2026-07-30T13:00:17.030][INFO ] [...] [class=org.apache.nifi.nar.NarClassLoaders] [method=createNarClassLoader] [...] Loaded NAR file: /opt/nifi/nifi-current/./work/nar/extensions/nifi-dropbox-services-nar-2.9.0.nar-unpacked as class loader org.apache.nifi.nar.NarClassLoader[./work/nar/extensions/nifi-dropbox-services-nar-2.9.0.nar-unpacked]
start to send crash dump
```

The container exits with **exit code 1**.

**Cause:** Incorrect keystore/truststore configuration (for example, a wrong `KEYSTORE_PASSWORD_NIFI` value) makes
NiFi fail while loading the certificate for the web server. Unlike other startup errors, this failure does not
produce a clear Java-level error message in the main startup log, only a crash dump.

### Symptom B: SSLHandshakeException / PKIX path validation failed

NiFi starts successfully, but the logs contain an `SSLHandshakeException` when NiFi tries to establish a TLS
connection to another party (for example, when synchronizing a process group with NiFi Registry), such as:

```text
[...] [ERROR] [...] [class=org.apache.nifi.groups.StandardProcessGroup] [method=synchronizeWithFlowRegistry] [...] Failed to synchronize StandardProcessGroup[...] with Flow Registry because could not retrieve version 4 of flow with identifier ... in bucket ...
javax.net.ssl.SSLHandshakeException: (certificate_unknown) PKIX path validation failed: java.security.cert.CertPathValidatorException: Path does not chain with any of the trust anchors
        ...
Caused by: sun.security.validator.ValidatorException: PKIX path validation failed: java.security.cert.CertPathValidatorException: Path does not chain with any of the trust anchors
        ...
Caused by: java.security.cert.CertPathValidatorException: Path does not chain with any of the trust anchors
        ...
```

**Cause:** The truststore configured via `TRUSTSTORE_PATH` does not contain the certificate (or the CA certificate)
of the remote party (for example, NiFi Registry), so NiFi cannot validate the certificate chain presented by that
party during the TLS handshake.

### Resolution

1. Check the values of `KEYSTORE_PATH`, `KEYSTORE_TYPE`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `TRUSTSTORE_PATH`,
   `TRUSTSTORE_TYPE` and `TRUSTSTORE_PASSWORD` for correctness.
2. For Symptom A, check the generated crash dump and other NiFi logs (`nifi-app.log`, `nifi-bootstrap.log`) for
   additional details on the failure.
3. For Symptom B, verify that the truststore referenced by `TRUSTSTORE_PATH` contains the certificate (or CA
   certificate) of the remote party (for example, NiFi Registry), and update it if necessary.
4. Restart NiFi after correcting the keystore/truststore configuration.

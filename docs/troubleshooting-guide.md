# Troubleshooting Guide

Common qubership-nifi startup failures, with the log output each one produces and the steps that fix it. Match the
container exit code against the summary table to find the relevant section.

## Summary

| Error type | Exit code | Description | Details |
| --- | --- | --- | --- |
| Invalid JVM arguments | 3 | Conflicting garbage collector arguments in `NIFI_ADDITIONAL_JVM_ARGS`/`X_JAVA_ARGS`. | [Invalid JVM arguments](#1-invalid-jvm-arguments) |
| Invalid Consul URL | 1 | `CONSUL_URL` is incorrect or Consul is unreachable, so the Consul integration application terminates prematurely. | [Invalid Consul URL](#2-invalid-consul-url) |
| Invalid NIFI_NEW_SENSITIVE_KEY | 3 | The sensitive key does not match the key used on the previous startup. | [Invalid NIFI_NEW_SENSITIVE_KEY](#3-invalid-nifi_new_sensitive_key) |
| Invalid flow.json.gz | 1 | The persisted NiFi flow configuration (`flow.json.gz`) is corrupted and cannot be parsed. | [Invalid flow.json.gz](#4-invalid-flowjsongz) |
| Invalid certificate | 1 (Symptom A only) | Keystore/truststore configuration is incorrect, for example a wrong password, or a missing or incomplete truststore. | [Invalid certificate](#5-invalid-certificate) |

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
ERROR: Invalid JVM arguments in <parameter-name>...
```

The container exits with **exit code 3**.

### Cause

`NIFI_ADDITIONAL_JVM_ARGS` or `X_JAVA_ARGS` select two different garbage collectors, for example `-XX:+UseG1GC`
together with `-XX:+UseParallelGC`. The same failure occurs when either variable conflicts with a collector already
selected in `JAVA_TOOL_OPTIONS`.

The startup check validates only garbage collector selection arguments (`-XX:+Use...GC`, `-XX:-Use...GC`) and
`-XX:+UnlockExperimentalVMOptions`. Other invalid JVM arguments, such as a malformed `-Xmx` value or an unknown
`-XX:` flag, do not trigger this check. They surface later, with a different message and exit code.

### Resolution

1. Check `NIFI_ADDITIONAL_JVM_ARGS` and `X_JAVA_ARGS`. Remove conflicting or duplicate JVM arguments, keeping only
   one `-XX:+Use...GC` option.
2. Check whether `JAVA_TOOL_OPTIONS` is set, either in the environment or inherited from a base image. Make sure it
   does not conflict with `NIFI_ADDITIONAL_JVM_ARGS` or `X_JAVA_ARGS`.
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
  format. The underlying issue is the same: it cannot connect to the address in `CONSUL_URL`, so the process
  terminates prematurely.

In both cases, the qubership-nifi startup log contains a line similar to:

```text
[...] ERROR: Consul app java process has terminated prematurely. See logs for details...
```

The container exits with **exit code 1**.

### Cause

`CONSUL_URL` is incorrect, or the host and port it resolves to are unreachable. The Consul integration application
cannot connect to Consul, so it terminates prematurely. This applies to both the Spring-based and the Quarkus-based
application, selected by `NIFI_CONSUL_INT_FRAMEWORK`.

### Resolution

1. Verify that `CONSUL_URL` is set correctly. For `NIFI_CONSUL_INT_FRAMEWORK=spring`, the format must be
   `<hostname>:<port>`; for `quarkus`, it must include the protocol: `<protocol>://<hostname>:<port>`.
2. Verify that the Consul host is reachable from the NiFi pod or container, checking DNS resolution and network
   policies.
3. Verify that `CONSUL_ACL_TOKEN`, if used, matches the target Consul instance configuration.
4. Restart NiFi after correcting `CONSUL_URL`.

## 3. Invalid NIFI_NEW_SENSITIVE_KEY

### Symptom

NiFi fails to start with the following error message:

```text
oldKeyHash does not match newKeyHash. Probably NIFI_NEW_SENSITIVE_KEY is different from previously used key. Check NIFI_NEW_SENSITIVE_KEY for correctness. Terminating start-up...
```

The container exits with **exit code 3**.

### Cause

The NiFi sensitive key does not match the key used on the previous startup. The key is read from the
`NIFI_NEW_SENSITIVE_KEY` environment variable or, when that variable is empty, from the file at
`NIFI_SENSITIVE_KEY_PATH`.

### Resolution

1. Determine which source supplies the key in this deployment. `NIFI_NEW_SENSITIVE_KEY` takes precedence; the file at
   `NIFI_SENSITIVE_KEY_PATH` is read only when the variable is empty.
2. Set that source back to the value that was used on the initial deployment.
3. Restart NiFi after correcting the key.

## 4. Invalid flow.json.gz

### Symptom

NiFi fails to start, and the logs contain a stack trace similar to:

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

The exact exception message varies with the kind of corruption in the file.

The container exits with **exit code 1**.

### Cause

The persisted NiFi flow configuration (`flow.json.gz`) is corrupted or contains invalid JSON, so NiFi cannot parse it
as a `VersionedDataflow` on startup.

### Resolution

Restore the flow configuration from an archived version. The
[NiFi configuration restore](administrator-guide.md#nifi-configuration-restore) section of the Administrator's Guide
describes the feature in full.

1. Set the version to restore in the Consul parameter `nifi-restore-version`, located in
   `config/${NAMESPACE}/qubership-nifi`. Use the format `<timestamp>_flow.json.gz`. The available archived versions
   are printed in the logs during service startup.
2. Restart the qubership-nifi container. On startup, the current corrupted configuration is moved to the archive and
   replaced with the specified archived version. The `nifi-restore-version` parameter is then cleared in Consul
   automatically.

## 5. Invalid certificate

Certificate misconfiguration produces two distinct failures.

### Symptom A: NiFi crashes on startup with no clear error message

NiFi fails to start, but the startup log does not contain a clear error message describing the cause. Only NAR
loading messages are printed, followed by a crash dump notice, for example:

```text
[2026-07-30T13:00:17.009][INFO ] [...] [class=org.apache.nifi.nar.NarClassLoaders] [method=createNarClassLoader] [...] Loaded NAR file: /opt/nifi/nifi-current/./work/nar/extensions/nifi-dropbox-processors-nar-2.9.0.nar-unpacked as class loader org.apache.nifi.nar.NarClassLoader[./work/nar/extensions/nifi-dropbox-processors-nar-2.9.0.nar-unpacked]
[2026-07-30T13:00:17.030][INFO ] [...] [class=org.apache.nifi.nar.NarClassLoaders] [method=createNarClassLoader] [...] Loaded NAR file: /opt/nifi/nifi-current/./work/nar/extensions/nifi-dropbox-services-nar-2.9.0.nar-unpacked as class loader org.apache.nifi.nar.NarClassLoader[./work/nar/extensions/nifi-dropbox-services-nar-2.9.0.nar-unpacked]
start to send crash dump
```

The container exits with **exit code 1**.

**Cause:** Incorrect keystore or truststore configuration makes NiFi fail while loading the certificate for the web
server, for example a wrong `KEYSTORE_PASSWORD` value, set through the `KEYSTORE_PASSWORD_NIFI` deployment
parameter. Unlike other startup errors, this failure produces no Java-level error message in the main startup log,
only a crash dump.

### Symptom B: SSLHandshakeException / PKIX path validation failed

NiFi starts successfully, but fails later when it opens a TLS connection to another party, for example when
synchronizing a process group with NiFi Registry. The logs contain an `SSLHandshakeException`:

```text
[...] [ERROR] [...] [class=org.apache.nifi.groups.StandardProcessGroup] [method=synchronizeWithFlowRegistry] [...] Failed to synchronize StandardProcessGroup[...] with Flow Registry because could not retrieve version 4 of flow with identifier ... in bucket ...
javax.net.ssl.SSLHandshakeException: (certificate_unknown) PKIX path validation failed: java.security.cert.CertPathValidatorException: Path does not chain with any of the trust anchors
        ...
Caused by: sun.security.validator.ValidatorException: PKIX path validation failed: java.security.cert.CertPathValidatorException: Path does not chain with any of the trust anchors
        ...
Caused by: java.security.cert.CertPathValidatorException: Path does not chain with any of the trust anchors
        ...
```

**Cause:** The truststore at `TRUSTSTORE_PATH` does not contain the certificate of the remote party, such as NiFi
Registry, or the CA certificate that signed it. NiFi therefore cannot validate the certificate chain presented
during the TLS handshake.

### Resolution

1. Check the values of `KEYSTORE_PATH`, `KEYSTORE_TYPE`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `TRUSTSTORE_PATH`,
   `TRUSTSTORE_TYPE`, and `TRUSTSTORE_PASSWORD` for correctness.
2. Check whether the passwords come from files instead of environment variables. `NIFI_KEYSTORE_PASSWORD_PATH`,
   `NIFI_KEY_PASSWORD_PATH`, and `NIFI_TRUSTSTORE_PASSWORD_PATH` are read only when the corresponding
   `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, and `TRUSTSTORE_PASSWORD` variables are not set. Verify whichever source the
   deployment uses.
3. For Symptom A, check the generated crash dump and the other NiFi logs (`nifi-app.log`, `nifi-bootstrap.log`) for
   details on the failure.
4. For Symptom B, verify that the truststore at `TRUSTSTORE_PATH` contains the certificate, or the CA certificate,
   of the remote party such as NiFi Registry. Update it if necessary.
5. Restart NiFi after correcting the keystore or truststore configuration.

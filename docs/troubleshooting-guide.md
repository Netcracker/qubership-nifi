# Troubleshooting Guide

This guide describes common startup errors in qubership-nifi, their causes and how to resolve them.

## 1. Invalid JVM arguments

### Symptom

NiFi fails to start and the logs contain a message similar to:

```
[2026-07-29T12:47:41.000] [ERROR] [request_id=] [tenant_id=] [thread=main] [class=c.n.c.n.extensions.start.sh] ERROR: Invalid JVM arguments in NIFI_ADDITIONAL_JVM_ARGS + X_JAVA_ARGS: -XX:+UseG1GC -XX:+UseParallelGC
[2026-07-29T12:47:41.000] [ERROR] [request_id=] [tenant_id=] [thread=main] [class=c.n.c.n.extensions.start.sh] Picked up JAVA_TOOL_OPTIONS: -XX:+UseParallelGC
Error occurred during initialization of VM
Multiple garbage collectors selected
```

The generic error message pattern is:

```
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

The Consul integration application (Spring Boot based, `NIFI_CONSUL_INT_FRAMEWORK=spring`) fails to start, and the
logs contain a Spring Boot failure analysis report similar to:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Config data resource '[ConsulConfigDataResource@... context = 'config/local/qubership-nifi,default/', ...]' via location 'consul:consul-asda:8500' does not exist

Action:

Check that the value 'consul:consul-asda:8500' at class path resource [application.yaml] from qubership-nifi-consul-application.jar - 66:13 is correct, or prefix it with 'optional:'

[...] ERROR: Consul app java process has terminated prematurely. See logs for details...
```

### Cause

The `CONSUL_URL` environment variable (or the hostname/port it resolves to) is incorrect or not reachable, so the
Consul configuration data resource cannot be found and the auxiliary Consul application terminates prematurely.

### Resolution

1. Verify that `CONSUL_URL` is set correctly. For `NIFI_CONSUL_INT_FRAMEWORK=spring`, the format must be
   `<hostname>:<port>`; for `quarkus` it must include the protocol, `<protocol>://<hostname>:<port>`.
2. Verify that the Consul host is reachable from the NiFi pod/container (DNS resolution, network policies).
3. Verify that `CONSUL_ENABLED` and `CONSUL_ACL_TOKEN` (if used) match the target Consul instance configuration.
4. Restart NiFi after correcting `CONSUL_URL`.

## 3. Invalid NIFI_NEW_SENSITIVE_KEY

### Symptom

NiFi fails to start with the following error message:

```
oldKeyHash does not match newKeyHash. Probably NIFI_NEW_SENSITIVE_KEY is different from previously used key. Check NIFI_NEW_SENSITIVE_KEY for correctness. Terminating start-up...
```

The container exits with **exit code 3**.

### Cause

`nifi-scripts/re_encrypt_sensitive_keys.sh` stores a hash of the sensitive properties key
(`NIFI_NEW_SENSITIVE_KEY`) used on the previous startup in `${NIFI_HOME}/persistent_conf/old_key_hash`. On every
startup it compares this stored hash with the hash of the current `NIFI_NEW_SENSITIVE_KEY` value. Since
`NIFI_NEW_SENSITIVE_KEY` is used to encrypt sensitive properties in the persisted flow (`flow.json.gz`), it must not
change after the initial deployment. If the values differ, startup is aborted to avoid leaving sensitive properties
encrypted with a key that no longer matches the one supplied to NiFi.

### Resolution

1. Set `NIFI_NEW_SENSITIVE_KEY` back to the value that was used on the initial deployment (the one matching the
   stored `old_key_hash`).
2. If the sensitive key must be changed intentionally, follow the dedicated key rotation/migration procedure instead
   of just changing `NIFI_NEW_SENSITIVE_KEY` (changing it directly is not supported and will always fail this check).
3. Restart NiFi after correcting `NIFI_NEW_SENSITIVE_KEY`.

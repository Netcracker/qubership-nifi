# Startup failures

Problems that stop qubership-nifi before the flow runs. The container exits, restarts, or never becomes ready. In
every case except a corrupted `flow.json.gz` and the certificate crash, NiFi itself never starts: on 2.x the log has
no `Starting NiFi` line, and the last lines come from `start.sh` or the Consul integration application. On 1.x, NiFi
never logs `Starting NiFi`, and invalid JVM arguments fail in the NiFi JVM itself, after the start scripts finish.

| Problem | Exit code | Exit code on 1.x | Last phase in the log |
| --- | --- | --- | --- |
| [Invalid JVM arguments](#invalid-jvm-arguments) | 3 | 0 | Start-script checks; on 1.x the NiFi JVM start |
| [Changed sensitive key](#changed-sensitive-key) | 3 | 3 | Start-script checks |
| [Consul unreachable](#consul-unreachable) | 1, or none when the startup hangs | the same | Consul integration application |
| [Corrupted flow.json.gz](#corrupted-flowjsongz) | 1 | 0 | NiFi web server start |
| [Invalid certificate](#invalid-certificate) | 1 | not reproduced | Start-script checks or NAR loading |

On a 1.x image, exit code 0 means that NiFi itself failed to start, unless the log shows
`Received trapped signal, beginning shutdown...`. RunNiFi logs `NiFi never started. Will not restart NiFi` to
`logs/nifi-app.log`, but that line and the JVM output before it often do not reach the pod log; see the
"NiFi 1.x" subsection of each problem for what the pod log does show.

## Invalid JVM arguments

### Signature

The container exits with code 3 about 10 seconds after the start. The log ends with:

```text
[...] [ERROR] [...] [class=c.n.c.n.extensions.start.sh] ERROR: Invalid JVM arguments in NIFI_ADDITIONAL_JVM_ARGS + X_JAVA_ARGS: -XX:+UseG1GC -XX:+UseParallelGC
[...] [ERROR] [...] [class=c.n.c.n.extensions.start.sh] Picked up JAVA_TOOL_OPTIONS: -XX:+UseParallelGC
Error occurred during initialization of VM
Multiple garbage collectors selected
```

Just before the error, `start.sh` logs one `Add <argument> in bootstrap.conf` line per argument it adds.

### Cause

`start.sh` adds `NIFI_ADDITIONAL_JVM_ARGS` and `X_JAVA_ARGS` to `conf/bootstrap.conf` as `java.arg.<n>` entries, then
starts a trial JVM with the collector arguments and exits with code 3 when that JVM refuses them. The check covers only
arguments that select a garbage collector (`-XX:+Use...GC`, `-XX:-Use...GC`) and `-XX:+UnlockExperimentalVMOptions`.
The image entrypoint also exports `X_JAVA_ARGS` as `JAVA_TOOL_OPTIONS`, so the trial JVM and NiFi pick it up; the
`Picked up JAVA_TOOL_OPTIONS` line comes from the trial JVM. The Consul application picks it up only on images before
2.8.6. From 2.8.6, `start_consul_app.sh` starts it without the variable and logs
`JAVA_TOOL_OPTIONS is set, but the consul app starts without it.`, so `X_JAVA_ARGS` does not explain a failure of the
Consul application there; its JVM options come from `CONSUL_CONFIG_JAVA_OPTIONS`.

Other bad JVM arguments, such as a malformed `-Xmx` or an unknown `-XX:` flag, pass this check and fail later with a
different message.

### Corroborating evidence

- Environment variables: two different `-XX:+Use...GC` options across `NIFI_ADDITIONAL_JVM_ARGS`, `X_JAVA_ARGS`, and
  `JAVA_TOOL_OPTIONS`.
- Recent changes: a change to JVM or GC settings in the deployment, or a new base image that sets
  `JAVA_TOOL_OPTIONS`.

### Fix

1. Keep one `-XX:+Use...GC` option across `NIFI_ADDITIONAL_JVM_ARGS`, `X_JAVA_ARGS`, and `JAVA_TOOL_OPTIONS`; remove
   the other.
2. Restart NiFi.

### NiFi 1.x

The 1.x start script has no trial JVM check. It adds both variables to `bootstrap.conf`, and the NiFi JVM itself
refuses to start. The container exits with code 0 about 6 seconds after the start. The pod log ends with no error,
at the `nifi.sh` header after `NiFi running with PID <n>.`:

```text
[...] [INFO] [...] [class=c.n.c.n.extensions.start.sh] NiFi running with PID 286.

Java home: /usr/lib/jvm/default-jvm
NiFi home: /opt/nifi/nifi-current

Bootstrap Config File: /opt/nifi/nifi-current/conf/bootstrap.conf

Picked up JAVA_TOOL_OPTIONS: -XX:+UseParallelGC
```

`start.sh` copies `logs/nifi-app.log` into the pod log with a background `tail` and exits as soon as NiFi stops, so
the JVM output and the RunNiFi lines stay in `logs/nifi-app.log`, where the stdout and stderr lines can come in
either order:

```text
[...][INFO ] [...] [class=org.apache.nifi.bootstrap.Command] [...] Starting Apache NiFi...
[...][INFO ] [...] [class=org.apache.nifi.StdOut] [...] Error occurred during initialization of VM
[...][INFO ] [...] [class=org.apache.nifi.StdOut] [...] Multiple garbage collectors selected
[...][ERROR] [...] [class=org.apache.nifi.StdErr] [...] Picked up JAVA_TOOL_OPTIONS: -XX:+UseParallelGC
[...][INFO ] [...] [class=org.apache.nifi.bootstrap.Command] [...] Launched Apache NiFi with Process ID 328
[...][INFO ] [...] [class=org.apache.nifi.bootstrap.RunNiFi] [...] NiFi never started. Will not restart NiFi
```

The pod log does not say which argument the JVM refused, so ask for the JVM environment variables:
`NIFI_ADDITIONAL_JVM_ARGS`, `X_JAVA_ARGS`, and `JAVA_TOOL_OPTIONS`. Two `-XX:+Use...GC` options there confirm the
conflict. Other bad JVM arguments end the pod log in the same way. In `logs/nifi-app.log` their message comes from
`org.apache.nifi.StdErr` at ERROR, for example `Invalid maximum heap size: -Xmxabc` or
`Unrecognized VM option 'Foo'`, followed by `Error: Could not create the Java Virtual Machine.`. The fix is the
same: correct the argument and restart.

## Changed sensitive key

### Signature

The container exits with code 3 about 10 seconds after the start, with:

```text
[...] [ERROR] [...] [class=c.n.c.n.extensions.re_encrypt_sensitive_keys.sh] oldKeyHash does not match newKeyHash. Probably NIFI_NEW_SENSITIVE_KEY is different from previously used key. Check NIFI_NEW_SENSITIVE_KEY for correctness. Terminating start-up...
```

The same script exits with code 3 for related key problems:

| Message | Meaning |
| --- | --- |
| `Either NIFI_NEW_SENSITIVE_KEY variable or file under path set in NIFI_SENSITIVE_KEY_PATH must be non-empty.` | No key is set at all |
| `OLD_SENSITIVE_KEY is not set, NIFI_NEW_SENSITIVE_KEY does not equal SENSITIVE_KEY. Set NIFI_NEW_SENSITIVE_KEY = SENSITIVE_KEY.` | Upgrade from a release that used the deprecated `SENSITIVE_KEY`, with a different new key |
| `OLD_SENSITIVE_KEY=SENSITIVE_KEY, NIFI_NEW_SENSITIVE_KEY does not equal SENSITIVE_KEY. Set NIFI_NEW_SENSITIVE_KEY = SENSITIVE_KEY.` | Same, with `OLD_SENSITIVE_KEY` set |
| `OLD_SENSITIVE_KEY != SENSITIVE_KEY != NIFI_NEW_SENSITIVE_KEY. Set correct NIFI_NEW_SENSITIVE_KEY, SENSITIVE_KEY and OLD_SENSITIVE_KEY parameters.` | The three keys disagree |

Only the `oldKeyHash` message was reproduced; the messages in the table are taken from the start script.

Before the check, the script logs `Neither SENSITIVE_KEY nor SENSITIVE_KEY_PATH is set` and
`Neither OLD_SENSITIVE_KEY nor OLD_SENSITIVE_KEY_PATH is set` on most starts. These lines are about the deprecated
upgrade variables and are normal. They do not mean that `NIFI_NEW_SENSITIVE_KEY` is missing; a missing key gives the
`must be non-empty` message from the table.

### Cause

NiFi encrypts the sensitive properties in `flow.json.gz` (passwords, tokens) with this key. On the first start the
start script saves the SHA-256 hash of the key in `persistent_conf/old_key_hash`. While `flow.json.gz` exists, it
refuses to start with a key whose hash differs. The key comes from `NIFI_NEW_SENSITIVE_KEY` or, when that variable is
empty, from the file at `NIFI_SENSITIVE_KEY_PATH`.

Typical triggers: a secret regenerated by a pipeline, a redeploy into a new namespace that kept the persistent volume,
or a switch between the variable and the file.

### Corroborating evidence

- Recent changes: a redeploy, a secret rotation, or a move of the deployment that kept the persistent volume.
- Environment variables: which of `NIFI_NEW_SENSITIVE_KEY` and `NIFI_SENSITIVE_KEY_PATH` is set. Ask whether the
  value changed, never for the value.

### Fix

1. Find which source supplies the key: `NIFI_NEW_SENSITIVE_KEY`, or the file at `NIFI_SENSITIVE_KEY_PATH` when the
   variable is empty.
2. Set that source back to the key of the first deployment, then restart NiFi.

qubership-nifi 2.x has no supported way to change the key in place; a 1.x image has one, described in the
"NiFi 1.x" subsection of this problem. Without
`flow.json.gz` the start script accepts any key and writes a new `old_key_hash`, so deleting `flow.json.gz` lets NiFi
start, but it loses the flow. Warn before suggesting it, and only as a last resort when the old key is lost and the
flow can be imported again.

### NiFi 1.x

The container exits with code 3 as well, and the messages differ:

```text
[...] [ERROR] [...] [class=c.n.c.n.extensions.re_encrypt_sensitive_keys.sh] oldKeyHash file does not match with newKeyHash and NIFI_PREVIOUS_SENSITIVE_KEY is empty. Terminating start-up...
```

The 1.x script can re-encrypt the flow with a new key. When the hash of `NIFI_NEW_SENSITIVE_KEY` differs from
`old_key_hash` and the hash of `NIFI_PREVIOUS_SENSITIVE_KEY` matches it, the script re-encrypts `flow.json.gz` from
the previous key to the new one with the NiFi toolkit, writes the hash of the new key to `old_key_hash`, and
continues. `NIFI_PREVIOUS_SENSITIVE_KEY_PATH` names a file to read the previous key from when the variable is empty.
A previous key whose hash does not match stops the start with exit code 3 and this message:

```text
oldKeyHash file does not match with newKeyHash or with NIFI_PREVIOUS_SENSITIVE_KEY_HASH. NIFI_PREVIOUS_SENSITIVE_KEY must be old sensitive key, if NIFI_NEW_SENSITIVE_KEY is changed. Terminating start-up...
```

The line `Neither NIFI_PREVIOUS_SENSITIVE_KEY nor  is set`, with two spaces, appears on most 1.x starts and is normal.

Fix on 1.x, by what the user wants:

- **Keep the new key**, for example after a planned rotation: keep it in `NIFI_NEW_SENSITIVE_KEY` (or the file at
  `NIFI_SENSITIVE_KEY_PATH`), set `NIFI_PREVIOUS_SENSITIVE_KEY` (or the file at `NIFI_PREVIOUS_SENSITIVE_KEY_PATH`)
  to the key of the last successful start, and restart. Copy `persistent_conf/conf/flow.json.gz` first, since the
  script rewrites it. After a successful start the previous key is no longer needed: remove it from the deployment
  so the secret does not stay around. A later start with it still set does no harm.
- **Return to the old key**: as on 2.x, set the key source back and restart.

This path was read from the 1.0.9 start script; the scenario did not run it.

## Consul unreachable

### Signature

With a host that does not resolve, the container exits with code 1 about 10 seconds after the start, before NiFi
starts. With `NIFI_CONSUL_INT_FRAMEWORK=spring`:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

Config data resource '[ConsulConfigDataResource@... context = 'config/local/qubership-nifi,default/', ...]' via location 'consul:consul-wrong:8500' does not exist

Action:

Check that the value 'consul:consul-wrong:8500' at class path resource [application.yaml] from qubership-nifi-consul-application.jar - 66:13 is correct, or prefix it with 'optional:'

[...] ERROR: Consul app java process has terminated prematurely. See logs for details...
```

With `NIFI_CONSUL_INT_FRAMEWORK=quarkus`:

```text
ERROR: Failed to start application
java.lang.RuntimeException: Failed to start quarkus
...
Caused by: java.net.ConnectException
...
Caused by: java.nio.channels.UnresolvedAddressException

[...] ERROR: Consul app java process has terminated prematurely. See logs for details...
```

When Consul accepts the connection and does not respond, the startup hangs instead, with either framework. There is
no error and no exit; the container never becomes ready, and after the health checks run out it is reported unhealthy
or restarted. The log repeats this line once a second:

```text
[...] [INFO] [...] [class=c.n.c.n.extensions.start.sh] Checking if consul app main execution completed
```

No timeout ends the wait: in testing, the startup still waited after 2 minutes with Spring and 9 minutes with Quarkus.

### Cause

`start.sh` runs the Consul integration application first and starts NiFi only after that application has written its
configuration. Consul is unreachable when `CONSUL_URL` is wrong, when DNS or a network policy blocks it, or when Consul
itself is stalled.

The two frameworks take `CONSUL_URL` in different forms: `spring` as `<host>:<port>`, `quarkus` with the protocol, as
`<protocol>://<host>:<port>`. A value in the wrong form for the framework is a common cause after switching frameworks.

### Corroborating evidence

- Environment variables: `CONSUL_URL`, `NIFI_CONSUL_INT_FRAMEWORK`, `CONSUL_ENABLED`, and whether `CONSUL_ACL_TOKEN` is
  set.
- Thread dump of a hung startup: the `main` thread of the Consul integration application waits for the HTTP response,
  in `org.springframework.cloud.consul.config.ConsulPropertySource.init` with Spring, or in
  `com.netcracker.cloud.quarkus.consul.client.ConsulClient.getKVValues` with Quarkus. Take it with `jcmd <pid> Thread.print` on the
  Consul application process, since NiFi is not running yet.
- Kubernetes events and the state of the Consul pods.

### Fix

1. Check `CONSUL_URL` against the framework: `<host>:<port>` for `spring`, `<protocol>://<host>:<port>` for `quarkus`.
2. Check that the Consul host resolves and accepts requests from the NiFi pod: DNS, network policies, and the state
   of Consul.
3. Check that `CONSUL_ACL_TOKEN` matches the Consul instance.
4. Restart NiFi after a failed start. A hung startup continues by itself once Consul answers the pending request;
   restart NiFi if it keeps waiting after Consul is available again.

### NiFi 1.x

1.x images have only the Spring-based application, `qubership-consul-application.jar`, and ignore
`NIFI_CONSUL_INT_FRAMEWORK`. Switching to `quarkus` is not available on 1.x, so `CONSUL_URL` always takes the
`<host>:<port>` form. Both failures look as they do with Spring on 2.x. The failure report names the 1.x jar:

```text
Check that the value 'consul:consul-wrong:8500' at class path resource [application.yaml] from qubership-consul-application.jar - 69:13 is correct, or prefix it with 'optional:'
```

Do not follow the `prefix it with 'optional:'` advice of the report: it would start NiFi without the Consul
configuration.

The 1.x failure report prints the Consul ACL token in plain text, as `aclToken = '<token>'` in its `Description`;
the 2.x report masks it as `******`. Tell the user that the token is in the pod log, to redact it before sharing the
log, and to rotate it if the log has reached a log store or a ticket. Do not repeat the token in the answer.

A hung startup waits in `ConsulPropertySource.init` as on 2.x. The 1.x image has no `jcmd`, so take the thread dump
with `kill -3` on the application process; the JVM prints it to the pod log. See
`evidence-collection.md#thread-dump`.

## Corrupted flow.json.gz

### Signature

The container exits with code 1 about 20 seconds after the start, once NiFi has loaded its extensions. The log has:

```text
[...][ERROR] [...] [class=org.apache.nifi.web.server.JettyServer] [method=startUpFailure] [...] Failed to start Server
org.apache.nifi.controller.serialization.FlowSerializationException: Could not parse flow as a VersionedDataflow
...
Caused by: com.fasterxml.jackson.core.JsonParseException: Unexpected character ('"' (code 34)): was expecting comma to separate Object entries
```

For a gzip file cut short, for example by a full disk or an interrupted write, the error names another exception:

```text
[...][ERROR] [...] [class=org.apache.nifi.web.server.JettyServer] [method=startUpFailure] [...] Failed to start Server
org.apache.nifi.web.NiFiCoreException: Unable to start Flow Controller.
...
Caused by: java.io.EOFException: Unexpected end of ZLIB input stream
```

The exact parser message varies with the damage. When the container exits quickly, the last lines of its log may
show only NiFi startup lines and not the error itself; ask for the log of the previous container after a restart, or
search the whole log for `Failed to start Server`.

### Cause

NiFi reads `persistent_conf/conf/flow.json.gz` on every start. Invalid JSON or a truncated gzip file stops the start.

A related case: the first start of a newer image migrates NAR coordinates in `flow.json.gz` with
`update_flow_json.sh`. When that migration reads invalid JSON, it logs
`Error while replacing artifact and type in flow.json.gz`, and NiFi then starts with an empty flow instead of failing.
A flow that is suddenly empty after an upgrade, with that line in the log, is the same damage.

### Corroborating evidence

- Recent changes: a full disk on the persistent volume, a node failure, or a manual edit of the flow.
- The list of archived flow versions, which `start.sh` prints during startup.

### Fix

Restore an archived version of the flow:

1. Set the Consul key `nifi-restore-version` in `config/<NAMESPACE>/<MICROSERVICE_NAME>` (`MICROSERVICE_NAME` defaults to `qubership-nifi`) to one of the archived files, in
   the form `<timestamp>_flow.json.gz`. The archived files are in `persistent_conf/conf/archive`.
2. Restart NiFi. On startup, the damaged configuration moves to the archive, the chosen version replaces it, and
   `nifi-restore-version` is cleared in Consul.

Changes made after the archived version was written are lost; pick the newest archive that predates the damage.

### NiFi 1.x

The failure reads differently, and the container exits with code 0 about 25 seconds after the start. The errors
below reach the pod log, which ends at `Shutting down...` and `Application Server shutdown started`; RunNiFi's
`NiFi never started. Will not restart NiFi` stays in `logs/nifi-app.log`. For invalid JSON, NiFi 1.x already reads
the flow when it creates the authorizer, so the error comes through the Spring context:

```text
[...][ERROR] [...] [class=org.springframework.web.context.ContextLoader] [method=initWebApplicationContext] [...] Context initialization failed
org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'niFiWebApiConfiguration': ...
...
Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'authorizer': ...
Caused by: org.apache.nifi.controller.serialization.FlowSerializationException: Could not parse flow as a VersionedDataflow
...
Caused by: com.fasterxml.jackson.core.JsonParseException: Unexpected character ('"' (code 34)): was expecting comma to separate Object entries
[...][ERROR] [...] [class=org.apache.nifi.StdErr] [...] Failed to start web server: Error creating bean with name 'niFiWebApiConfiguration': ...
```

For a truncated file:

```text
[...][ERROR] [...] [class=org.apache.nifi.util.FlowParser] [method=parse] [...] Unable to parse flow /opt/nifi/nifi-current/./persistent_conf/conf/flow.json.gz
java.io.EOFException: Unexpected end of ZLIB input stream
...
[...][ERROR] [...] [class=org.apache.nifi.web.server.JettyServer] [method=startUpFailure] [...] Failed to start web server... shutting down.
org.apache.nifi.web.NiFiCoreException: Unable to start Flow Controller.
```

Earlier in the same log, `org.apache.nifi.StdErr` logs JVM warnings at ERROR level (`sun.misc.Unsafe`,
`restricted method`). They appear on every 1.x start; the flow error is the first real one.

NiFi 1.x also writes `flow.xml.gz` beside `flow.json.gz`. It reads `flow.json.gz` when that file exists and does not
fall back to `flow.xml.gz` when `flow.json.gz` cannot be read. 1.x has no `update_flow_json.sh` migration. The restore
through `nifi-restore-version` works the same way on 1.x.

### Prevention

NiFi writes an archived copy of the flow on every change and removes the oldest copies once the archive exceeds
`nifi.flow.configuration.archive.max.storage` (`300 MB` by default in qubership-nifi), `max.time` (`30 days`), or
`max.count`. The `persistent_conf` volume holds this archive and other configuration, so it must stay larger than
`max.storage` with room to spare; 1 GB is the recommended size with the default limit. A volume smaller than that,
or a limit raised without growing the volume, lets the disk fill and cut `flow.json.gz` short. When the volume
cannot grow, lower `nifi.flow.configuration.archive.max.storage` in Consul.

## Invalid certificate

### Signature

Three forms:

- **Crash with no clear error.** NiFi fails to start, and the log has only NAR loading lines followed by
  `start to send crash dump`. The container exits with code 1.
- **Missing keystore or truststore file.** The start scripts log `Keystore file specified (<path>) does not exist.`,
  or `Cannot find truststore.p12 for node = <n> under path <path>`, or `cannot find keystore.p12 ...`, and exit with
  code 1.
- **Handshake failure at runtime.** NiFi runs, and fails when it opens a TLS connection, for example to NiFi Registry:

  ```text
  javax.net.ssl.SSLHandshakeException: (certificate_unknown) PKIX path validation failed: java.security.cert.CertPathValidatorException: Path does not chain with any of the trust anchors
  ```

These forms are taken from the troubleshooting guide and the start scripts; no scenario reproduces them yet.

### Cause

- Crash: a wrong keystore or truststore configuration, such as a wrong `KEYSTORE_PASSWORD`, makes NiFi fail while it
  loads the web server certificate.
- Missing file: the TLS secret is not mounted. qubership-nifi always reads `keystore.p12` and `truststore.p12` from
  `/tmp/tls-certs/`, or from `/tmp/tls-certs-<n>/` for node `<n>` of a cluster, and overwrites `KEYSTORE_PATH` and
  `TRUSTSTORE_PATH` with these paths, so changing the variables does not help. `secure.sh` prints `Keystore file
  specified` for a missing truststore too.
- Handshake: the truststore lacks the certificate of the remote party or of the CA that signed it.

### Fix

1. For a missing file, mount the TLS secret with `keystore.p12` and `truststore.p12` at `/tmp/tls-certs/` (or
   `/tmp/tls-certs-<n>/` in a cluster).
2. Otherwise check `KEYSTORE_TYPE`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `TRUSTSTORE_TYPE`, and
   `TRUSTSTORE_PASSWORD`. The password files `NIFI_KEYSTORE_PASSWORD_PATH`, `NIFI_KEY_PASSWORD_PATH`, and
   `NIFI_TRUSTSTORE_PASSWORD_PATH` are read only when the matching variable is not set.
3. For a handshake failure, add the remote party's certificate or CA to the truststore.
4. Restart NiFi.

# Corrupted flow.json.gz

The flow configuration NiFi saved in `persistent_conf/conf/flow.json.gz` cannot be read. NiFi fails to start the web
server and exits with code 1.

```bash
./run.sh flow-json-corrupted
```

## Symptom

- The container exits with code 1 about 20 seconds after the start, once NiFi has loaded its extensions.
- `nifi-app.log` has an error with a stack trace. With the default `CORRUPTION=json`:

  ```text
  [ERROR] ... [class=org.apache.nifi.web.server.JettyServer] [method=startUpFailure] ... Failed to start Server
  org.apache.nifi.controller.serialization.FlowSerializationException: Could not parse flow as a VersionedDataflow
  ...
  Caused by: com.fasterxml.jackson.core.JsonParseException: Unexpected character ('"' (code 34)): was expecting comma to separate Object entries
  ```

  With `CORRUPTION=truncated` the error names another exception:

  ```text
  [ERROR] ... [class=org.apache.nifi.web.server.JettyServer] [method=startUpFailure] ... Failed to start Server
  org.apache.nifi.web.NiFiCoreException: Unable to start Flow Controller.
  ...
  Caused by: java.io.EOFException: Unexpected end of ZLIB input stream
  ```

- The container output ends with the startup log of NiFi; its last lines do not name the failure.

## Cause

NiFi reads `flow.json.gz` on every start. A file with invalid JSON, or a gzip file cut short by a full disk or an
interrupted write, stops the start. See
[Invalid flow.json.gz](../../../../docs/troubleshooting-guide.md#4-invalid-flowjsongz) in the troubleshooting guide.

## How the scenario reproduces it

NiFi starts once, then `scenario.sh` restarts it, stops it, damages `flow.json.gz` from a helper container, and
creates the NiFi container again.

The restart before the damage matters. On the first start that finds a `flow.json.gz`,
`nifi-scripts/update_flow_json.sh` migrates NAR coordinates in the file once and marks the migration done in
`update_flow_json_2.applied`. When that migration reads a file with invalid JSON (`CORRUPTION=json`), it fails with
`Error while replacing artifact and type in flow.json.gz`, leaves the flow unpacked as `flow.json`, and NiFi then
starts with an empty flow instead of failing. The restart completes the migration, as on any instance that has
started more than once.

| Variable     | Default | Description                                                                                                               |
|--------------|---------|---------------------------------------------------------------------------------------------------------------------------|
| `CORRUPTION` | `json`  | `json`: the file stays a valid gzip file, with one comma removed from the JSON. `truncated`: the gzip file is cut in half |

## Expected evidence

| File                     | What to look for                                                                                                                                                                                                        |
|--------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `manifest.txt`           | `nifi_exit_code=1`                                                                                                                                                                                                      |
| `nifi-app.log.gz`        | `Failed to start Server` from `JettyServer`, with the exception and its causes shown above                                                                                                                              |
| `nifi-app.filtered.log`  | The same `ERROR` line                                                                                                                                                                                                   |
| `scenario-flow-json.txt` | The files of `persistent_conf/conf` and its `archive/` directory, the `gzip -t` result (`OK` for `json`, `damaged` for `truncated`), the errors of the start scripts, and the NiFi exception with its `Caused by` lines |

## NiFi 1.x

NiFi 1.x fails on both corruptions too, but the container exits with code 0, not 1, about 25 seconds after the start.
RunNiFi ends `nifi-app.log` with `NiFi never started. Will not restart NiFi`, and the failure reads differently:

- `CORRUPTION=json`: NiFi 1.x reads the flow already when it creates the authorizer, so the error goes through the
  Spring context:

  ```text
  [ERROR] ... [class=org.apache.nifi.web.server.JettyServer] [method=startUpFailure] ... Failed to start web server... shutting down.
  org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'niFiWebApiConfiguration': ...
  ...
  Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'authorizer': ...
  Caused by: org.apache.nifi.controller.serialization.FlowSerializationException: Could not parse flow as a VersionedDataflow
  ...
  Caused by: com.fasterxml.jackson.core.JsonParseException: Unexpected character ('"' (code 34)): was expecting comma to separate Object entries
  ```

- `CORRUPTION=truncated`:

  ```text
  [ERROR] ... [class=org.apache.nifi.util.FlowParser] [method=parse] ... Unable to parse flow /opt/nifi/nifi-current/./persistent_conf/conf/flow.json.gz
  [ERROR] ... [class=org.apache.nifi.web.server.JettyServer] [method=startUpFailure] ... Failed to start web server... shutting down.
  org.apache.nifi.web.NiFiCoreException: Unable to start Flow Controller.
  ...
  Caused by: java.io.EOFException: Unexpected end of ZLIB input stream
  ```

NiFi 1.x has no `update_flow_json.sh` migration, so `scenario.sh` does not restart NiFi before the damage.
`persistent_conf/conf` also holds `flow.xml.gz`, which NiFi 1.x writes beside `flow.json.gz`; NiFi does not fall
back to it when `flow.json.gz` cannot be read. NiFi 1.x logs a `DeprecationException` stack trace on every start, so
`scenario-flow-json.txt` leaves it out. Measured on qubership-nifi 1.0.9 (Apache NiFi 1.28.1).

## Remediation

Restore an archived version of the flow: set `nifi-restore-version` in Consul to one of the files in
`persistent_conf/conf/archive`, then restart NiFi. The
[NiFi configuration restore](../../../../docs/administrator-guide.md#nifi-configuration-restore) section of the
Administrator's Guide describes the procedure.

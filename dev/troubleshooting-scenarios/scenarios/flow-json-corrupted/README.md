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

The restart before the damage matters. On the first start that finds a `flow.json.gz`, two start-script migrations
rewrite the file once each: `nifi-scripts/update_flow_json.sh` migrates NAR coordinates and marks the migration done
in `update_flow_json_2.applied`, and `update_flow_json_ojdbc_location.sh` sets the Oracle driver location and marks
it done in `update_ojdbc_location.applied`. The restart completes both, as on any instance that has started more than
once, so that NiFi itself reads the damaged file. `PENDING_MIGRATION` leaves one migration to meet the damaged file
instead.

| Variable            | Default | Description                                                                                                                                                                                                                                                               |
|---------------------|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CORRUPTION`        | `json`  | `json`: the file stays a valid gzip file, with one comma removed from the JSON. `truncated`: the gzip file is cut in half                                                                                                                                                 |
| `PENDING_MIGRATION` | `none`  | NiFi 2.x only. `none`: both migrations are done before the damage. `flow-json`: no restart, so `update_flow_json.sh` runs on the damaged file, as on the first start after an upgrade. `ojdbc-location`: the Oracle marker is removed, as a restore from the archive does |

### Pending migrations

From qubership-nifi 2.9.0, a migration that fails stops the start with exit code 1 about 20 seconds after the start,
and NiFi does not run. Measured on qubership-nifi 2.9.0 (Apache NiFi 2.12.0):

| `CORRUPTION` | `PENDING_MIGRATION` | Start-script errors                                                                                                                                            | Next start                       |
|--------------|---------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------|
| `json`       | `flow-json`         | `Error while replacing artifact and type in flow.json.gz`, `ERROR: failed to adapt components ...`                                                             | NiFi runs with a new, empty flow |
| `truncated`  | `flow-json`         | The same two lines, after `gzip: unexpected end of file`                                                                                                       | Fails the same way               |
| `json`       | `ojdbc-location`    | `Error while searching for Oracle connection pools without driver location in flow.json.gz`, `ERROR: failed to set the Oracle driver location in flow.json.gz` | Fails the same way               |
| `truncated`  | `ojdbc-location`    | None: the migration reads the truncated file without an error, and NiFi fails with `Unexpected end of ZLIB input stream`                                       | Fails the same way               |

With `CORRUPTION=json PENDING_MIGRATION=flow-json`, `update_flow_json.sh` unpacks the damaged file to `flow.json` and
leaves no `flow.json.gz`. The next start skips both migrations, and NiFi writes a new, empty flow. `flow.json` stays,
and the migration of a flow restored from the archive then fails on every start with
`gzip: can't open '/opt/nifi/nifi-current/persistent_conf/conf/flow.json': File exists`, until `flow.json` is
deleted. On images before 2.9.0, the failed migration did not stop the start, and NiFi started with the empty flow at
once. `run.sh` expects no exit code for `PENDING_MIGRATION=flow-json` for that reason.

## Expected evidence

| File                     | What to look for                                                                                                                                                                                                                                                        |
|--------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `manifest.txt`           | `nifi_exit_code=1`                                                                                                                                                                                                                                                      |
| `nifi-app.log.gz`        | `Failed to start Server` from `JettyServer`, with the exception and its causes shown above                                                                                                                                                                              |
| `nifi-app.filtered.log`  | The same `ERROR` line                                                                                                                                                                                                                                                   |
| `scenario-flow-json.txt` | The files of `persistent_conf/conf` and its `archive/` directory, the `gzip -t` result (`OK` for `json`, `damaged` for `truncated`, `missing` after a failed `update_flow_json.sh`), the errors of the start scripts, and the NiFi exception with its `Caused by` lines |

When a pending migration stops the start, NiFi does not run: `nifi-app.log.gz` and `nifi-app.filtered.log` hold no
error, and the start-script errors in `scenario-flow-json.txt` show the failure.

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

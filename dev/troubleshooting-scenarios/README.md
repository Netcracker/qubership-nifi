# Troubleshooting scenarios

These scripts reproduce known Apache NiFi problems on a local qubership-nifi instance and collect an evidence bundle
from each one: logs, repository usage, REST API snapshots, and a thread dump. The bundles are the input for the
qubership-nifi troubleshooting skill, so every scenario produces the same set of files.

| Scenario                                                | Problem                                                               | Content repository storage       |
|---------------------------------------------------------|-----------------------------------------------------------------------|----------------------------------|
| [content-archive-full](scenarios/content-archive-full/) | Archive cannot be cleaned: live content takes more space than allowed | 1 GB tmpfs                       |
| [content-archive-slow](scenarios/content-archive-slow/) | Archive cleanup is too slow: many archived claim files on NFS         | NFS export you provide, or local |

## Prerequisites

- Docker with Docker Compose v2.
- `curl` and `jq` on the host.
- Free local ports 8080 (NiFi) and 8500 (Consul).
- For `content-archive-slow` with NFS storage: an NFS export, see the [scenario page](scenarios/content-archive-slow/).

## Running a scenario

```bash
./run.sh content-archive-full
```

`run.sh` does the following:

1. Generates passwords and `docker.env` in `.work/`, with the helpers from `.github/workflows/sh/nifi-lib.sh` that
   the autotests use.
2. Starts Consul and the `nifi-toolkit` container, which generates the certificates. Then it sets DEBUG level for
   `org.apache.nifi.controller.repository.FileSystemRepository` and writes the scenario's `nifi.properties` overrides
   to Consul.
3. Starts NiFi with the scenario's content repository storage and grants the admin user access to the root process
   group.
4. Uploads the scenario's `flow.json` as a process group and drives it into the broken state.
5. Collects an evidence bundle.

NiFi keeps running afterwards. Open `https://localhost:8080/nifi` with the client certificate
`.work/temp-vol/tls-cert/nifi/CN=admin_OU=NIFI.p12`; its password is in the `.password` file beside it.

Only one scenario runs at a time. `run.sh` refuses to start while the NiFi container of a previous run exists.

The environment is based on `.github/docker/tls/docker-compose.yaml` without NiFi Registry. These variables apply
to every scenario:

| Variable         | Default                             | Description     |
|------------------|-------------------------------------|-----------------|
| `NIFI_IMAGE`     | `ghcr.io/netcracker/qubership-nifi` | NiFi image name |
| `NIFI_IMAGE_TAG` | `latest`                            | NiFi image tag  |

Each scenario page lists its own variables.

## Evidence bundle

`run.sh` collects a bundle once the issue is reproduced. Run `./collect-evidence.sh` to take another snapshot of the
running instance. Each bundle is written to `.work/evidence/<scenario>/<timestamp>/`:

| File                          | Content                                                                                    |
|-------------------------------|--------------------------------------------------------------------------------------------|
| `manifest.txt`                | Scenario, NiFi image and version, content repository mount, scenario parameters            |
| `storage.txt`                 | `df` and `du` of the content repository and its archive directories, claim file counts     |
| `nifi.properties.content.txt` | Effective `nifi.content.*` properties                                                      |
| `nifi-app.log.gz`             | Full NiFi application log with the hourly rolled-over files, compressed                    |
| `nifi-app.filtered.log`       | Archive, content repository, warning, and error lines, without per-claim DEBUG lines       |
| `container.log`               | `docker logs` of the NiFi container without the application log lines: startup scripts     |
| `system-diagnostics.json`     | `GET /nifi-api/system-diagnostics`: heap, repository storage usage as NiFi reports it      |
| `root-status.json`            | `GET /nifi-api/flow/process-groups/root/status?recursive=true`: queues and processor stats |
| `bulletins.json`              | `GET /nifi-api/flow/bulletin-board`                                                        |
| `diagnostics.txt`             | Output of `nifi.sh diagnostics --verbose`, including a thread dump                         |
| `scenario-*`                  | Scenario-specific files, described on the scenario page                                    |
| `collection-errors.txt`       | Present only when a step failed; names the files that could not be collected               |

## Cleanup

```bash
./cleanup.sh
```

The script removes the containers, their volumes, and the contents of `.work/` except the evidence bundles.
Run `./cleanup.sh --all` to remove the evidence bundles too.

## Adding a scenario

Create a directory under `scenarios/` with these files:

| File                  | Required | Content                                                                                        |
|-----------------------|----------|------------------------------------------------------------------------------------------------|
| `README.md`           | Yes      | Symptom, cause, how the scenario reproduces it, parameters, expected evidence, remediation     |
| `flow.json`           | Yes      | Flow definition that `run.sh` uploads, as downloaded from NiFi with "Download flow definition" |
| `scenario.sh`         | Yes      | Hook functions, see below                                                                      |
| `docker-compose.yaml` | No       | Compose override applied on top of `docker-compose.base.yaml`                                  |
| `scenario.env`        | No       | Parameter defaults in the form `NAME="${NAME:-default}"`; recorded in `manifest.txt`           |

`scenario.sh` may set `SCENARIO_COMPOSE_FILES` to use other override files, and may define these hooks, which
`run.sh` calls in this order:

| Hook                     | Called                                                                      |
|--------------------------|-----------------------------------------------------------------------------|
| `scenario_check_prereqs` | Before anything starts; fail here when the host is not ready                |
| `scenario_prepare`       | After Consul starts and before NiFi starts                                  |
| `scenario_configure`     | Before NiFi starts; write `nifi.properties` overrides with `consul_put`     |
| `scenario_load`          | After the flow is uploaded; drive the flow into the broken state            |
| `scenario_evidence`      | By `collect-evidence.sh`, with the bundle directory; add `scenario-*` files |

`lib.sh` has helpers for the hooks: `nifi_api`, `processor_id`, `connection_id`, `start_processor`,
`stop_processor`, `connection_queued_bytes`, `content_repo_usage`, and `consul_put`.

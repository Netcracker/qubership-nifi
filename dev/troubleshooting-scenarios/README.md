# Troubleshooting scenarios

These scripts reproduce known Apache NiFi problems on a local qubership-nifi instance and collect an evidence bundle
from each one: logs, repository usage, REST API snapshots, and a thread dump. The bundles are the input for the
qubership-nifi troubleshooting skill, so every scenario produces the same set of files, less the ones that need a
NiFi that has come up.

A runtime scenario leaves NiFi running in the broken state. A startup failure scenario starts NiFi once, changes one
thing, and starts it again; NiFi then exits or its startup hangs, and the bundle holds what the failed start left
behind.

| Scenario                                                  | Kind            | Problem                                                               |
|-----------------------------------------------------------|-----------------|-----------------------------------------------------------------------|
| [content-archive-full](scenarios/content-archive-full/)   | Runtime         | Archive cannot be cleaned: live content takes more space than allowed |
| [content-archive-slow](scenarios/content-archive-slow/)   | Runtime         | Archive cleanup is too slow: many archived claim files on NFS         |
| [flowfile-repo-full](scenarios/flowfile-repo-full/)       | Runtime         | FlowFile repository file system is full                               |
| [provenance-repo-full](scenarios/provenance-repo-full/)   | Runtime         | Provenance repository file system is full                             |
| [consul-unreachable](scenarios/consul-unreachable/)       | Startup failure | Consul cannot be reached: wrong `CONSUL_URL` or no response           |
| [flow-json-corrupted](scenarios/flow-json-corrupted/)     | Startup failure | `flow.json.gz` cannot be read                                         |
| [sensitive-key-changed](scenarios/sensitive-key-changed/) | Startup failure | `NIFI_NEW_SENSITIVE_KEY` differs from the key of earlier starts       |
| [jvm-args-conflict](scenarios/jvm-args-conflict/)         | Startup failure | `NIFI_ADDITIONAL_JVM_ARGS` and `X_JAVA_ARGS` select two collectors    |

## Prerequisites

- Docker with Docker Compose v2, and a Docker CLI that supports `docker cp -q`.
- A Linux host or WSL. The scripts need `/bin/bash` 4.4 or later, GNU coreutils (`timeout`, `sha256sum`, `shuf`,
  and `date` with `%N`), and GNU findutils (`xargs -r`).
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
3. Starts NiFi with the scenario's repository storage and grants the admin user access to the root process
   group.
4. Uploads the scenario's `flow.json`, if it has one, as a process group and drives it into the broken state.
5. For a startup failure scenario, changes the setup, starts NiFi again, and waits up to `NIFI_EXIT_TIMEOUT`
   seconds for the NiFi container to exit. It warns when the exit code differs from the one the scenario expects.
   A scenario whose startup hangs waits a fixed time instead. When NiFi does not exit in time, `run.sh` warns and
   goes on to the next step.
6. Collects an evidence bundle.

After a runtime scenario NiFi keeps running. Open `https://localhost:8080/nifi` with the client certificate
`.work/temp-vol/tls-cert/nifi/CN=admin_OU=NIFI.p12`; its password is in the `.password` file beside it.

Only one scenario runs at a time. `run.sh` refuses to start while the NiFi container of a previous run exists.

The environment is based on `.github/docker/tls/docker-compose.yaml` without NiFi Registry. These variables apply
to every scenario:

| Variable             | Default                             | Description                                                                  |
|----------------------|-------------------------------------|------------------------------------------------------------------------------|
| `NIFI_IMAGE`         | `ghcr.io/netcracker/qubership-nifi` | NiFi image name                                                              |
| `NIFI_IMAGE_TAG`     | `latest`                            | NiFi image tag                                                               |
| `NIFI_EXIT_TIMEOUT`  | `600`                               | Seconds a startup failure scenario waits for the NiFi container to exit      |
| `NIFI_MAJOR_VERSION` | From the image                      | Apache NiFi major version of the image, `1` or `2`; see [NiFi 1.x](#nifi-1x) |

Each scenario page lists its own variables.

## NiFi 1.x

The scenarios run on qubership-nifi 1.x images too, which are built on Apache NiFi 1.x:

```bash
NIFI_IMAGE_TAG=1.0.9 ./run.sh content-archive-full
```

`run.sh` reads the major version from the `org.opencontainers.image.version` label of the image: `1.*` selects
NiFi 1.x, any other value or no label selects NiFi 2.x. Set `NIFI_MAJOR_VERSION` for an image whose label does not
match its Apache NiFi version. A scenario page lists what differs on NiFi 1.x, in its messages, exit codes, or
parameters.

On NiFi 1.x with `AUTH=tls`, NiFi keeps `users.xml` and `authorizations.xml` in the `conf` volume, and their copies
in `persistent_conf/conf-restore`. A NiFi container started again with a new `conf` volume then fails with
`Primary file '/opt/nifi/nifi-current/./conf/users.xml' is different than restore file
'/opt/nifi/nifi-current/./persistent_conf/conf-restore/users.xml'`. So that a startup failure scenario reproduces
its own failure, `recreate_nifi` removes the copies before it starts NiFi 1.x again, and NiFi creates both files
anew for the initial admin.

## Evidence bundle

`run.sh` collects a bundle once the issue is reproduced. Run `./collect-evidence.sh` to take another snapshot of the
instance. Each bundle is written to `.work/evidence/<scenario>/<timestamp>/`:

| File                          | Content                                                                                              |
|-------------------------------|------------------------------------------------------------------------------------------------------|
| `manifest.txt`                | Scenario, NiFi image, version, and major version, container state and exit code, scenario parameters |
| `storage.txt`                 | `df` and `du` of the content repository and its archive directories, claim file counts               |
| `nifi.properties.content.txt` | Effective `nifi.content.*` properties                                                                |
| `nifi-app.log.gz`             | Full NiFi application log with the hourly rolled-over files, compressed                              |
| `nifi-bootstrap.log`          | One line: qubership-nifi has no bootstrap log; NiFi 1.x writes the bootstrap lines to `nifi-app.log` |
| `nifi-app.filtered.log`       | Archive, content repository, warning, and error lines, without per-claim DEBUG lines                 |
| `container.log`               | `docker logs` of the NiFi container; for a running NiFi, without the application log lines           |
| `system-diagnostics.json`     | `GET /nifi-api/system-diagnostics`: heap, repository storage usage as NiFi reports it                |
| `root-status.json`            | `GET /nifi-api/flow/process-groups/root/status?recursive=true`: queues and processor stats           |
| `bulletins.json`              | `GET /nifi-api/flow/bulletin-board`                                                                  |
| `diagnostics.txt`             | Output of `nifi.sh diagnostics --verbose`, including a thread dump                                   |
| `scenario-*`                  | Scenario-specific files, described on the scenario page                                              |
| `collection-errors.txt`       | Present only when a step failed; names the files that could not be collected                         |

When NiFi has not come up in the current container, the bundle has no `storage.txt`, `nifi.properties.content.txt`,
`system-diagnostics.json`, `root-status.json`, `bulletins.json`, or `diagnostics.txt`: they need the REST API or the
repositories of a running NiFi. That is the case after a startup failure, whether the container has exited or its
startup hangs. `manifest.txt` lists them in `skipped=`. A log that NiFi did not start far enough to write holds one
line that says so. The container of a startup failure is created anew for the failed start, so its logs cover that
start only.

## Cleanup

```bash
./cleanup.sh
```

The script removes the containers, their volumes, and the contents of `.work/` except the evidence bundles.
Run `./cleanup.sh --all` to remove the evidence bundles too.

## Adding a scenario

Create a directory under `scenarios/` with these files:

| File                   | Required | Content                                                                                        |
|------------------------|----------|------------------------------------------------------------------------------------------------|
| `README.md`            | Yes      | Symptom, cause, how the scenario reproduces it, parameters, expected evidence, remediation     |
| `flow.json`            | No       | Flow definition that `run.sh` uploads, as downloaded from NiFi with "Download flow definition" |
| `scenario.sh`          | Yes      | Hook functions, see below                                                                      |
| `docker-compose.yaml`  | No       | Compose override applied on top of `docker-compose.base.yaml`                                  |
| `scenario-defaults.sh` | No       | Parameter defaults in the form `NAME="${NAME:-default}"`; recorded in `manifest.txt`           |

`scenario.sh` may set `SCENARIO_COMPOSE_FILES` to use other override files, and may define these hooks, which
`run.sh` calls in this order:

| Hook                     | Called                                                                                                                                  |
|--------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `scenario_check_prereqs` | Before anything starts; fail here when the host is not ready                                                                            |
| `scenario_prepare`       | After Consul starts and before NiFi starts                                                                                              |
| `scenario_configure`     | Before NiFi starts; write `nifi.properties` overrides with `consul_put`                                                                 |
| `scenario_load`          | After the flow is uploaded; drive the flow into the broken state                                                                        |
| `scenario_break`         | After `scenario_load`, in a startup failure scenario; start NiFi again with the change that makes it fail, usually with `recreate_nifi` |
| `scenario_evidence`      | By `collect-evidence.sh`, with the bundle directory; add `scenario-*` files                                                             |

When a scenario defines `scenario_break`, `run.sh` waits for the NiFi container to exit after the hook returns. Set
`SCENARIO_EXPECTED_EXIT_CODE` in `scenario.sh` to the exit code the failure produces; `run.sh` warns on another one,
and `manifest.txt` records both. For a startup that hangs, set `SCENARIO_WAIT_FOR_EXIT=false` and wait in the hook
instead. The scenario's `docker-compose.yaml` sets `restart: "no"`, so that Docker does not
restart the failed container.

`lib.sh` has helpers for the hooks: `nifi_api`, `processor_id`, `connection_id`, `update_processor`,
`start_processor`, `stop_processor`, `connection_queued_bytes`, `connection_queued_count`, `content_repo_usage`,
`consul_put`, `recreate_nifi`, which starts NiFi again with the variables the caller exports, and `helper_run`, which
runs a command as root with `.work/` mounted at `/work`, to change files NiFi wrote. A hook that differs on NiFi 1.x
checks `NIFI_MAJOR_VERSION`, which is `1` or `2`.

## Keeping the skill in sync

The `nifi-troubleshooting` skill in `agent-packages/qubership-nifi-troubleshooting` quotes the log lines and exit
codes these scenarios produce. A change to a message or an exit code in `nifi-scripts/` can make a quoted line wrong.
After such a change, rerun the scenarios that show the changed output. Then update the scenario page, the skill
reference, the verified version the skill names, and `docs/troubleshooting-guide.md`, which quotes the same lines.
Run `apm install` to deploy the changed skill to `.claude/skills/` and `.agents/skills/`.

# Evidence collection

What to ask the user for, when each item helps, and how they get it. Ask only for items in this file, and ask for the
fewest that settle the question. With each request, say what the item would confirm or rule out, so the user can
judge whether it is worth the effort.

Commands assume Kubernetes with the pod name in `<pod>` and namespace in `<ns>`, or Docker with the container name in
`<container>`. NiFi's home in the container is `/opt/nifi/nifi-current`.

## Items

| Item | Ask for it when | What it settles |
|---|---|---|
| [Pod log](#pod-log) | Always, unless the user pasted the relevant part | Phase, first error, root cause exception |
| [Container state and exit code](#container-state-and-exit-code) | Startup failures, restarts | Exit code 1 or 3, or a hang; restart count |
| [Environment variables](#environment-variables) | Startup failures; any setting the fix depends on | `CONSUL_URL` form, JVM arguments, repository paths |
| [Kubernetes events](#kubernetes-events) | Restarts, never ready | Probe failures, volume mount errors, evictions |
| [system-diagnostics](#system-diagnostics) | NiFi is up | Heap use, usage of each repository as NiFi sees it |
| [Thread dump](#thread-dump) | Hangs, stalled processors, slow cleanup | Where threads wait |
| [Repository storage](#repository-storage) | Repository errors, `No space left on device`, archive questions | Which volume is full, mount type, archived file count |
| [Monitoring metrics](#monitoring-metrics) | Slow or recurring problems | Trend: slow growth or a sudden spike, and when it began |
| [Deployment config](#deployment-config) | A volume or a limit looks too small | Volume sizes and types, resource limits |
| [Recent changes](#recent-changes) | Almost always, in one line | What changed before the problem began |

Do not ask for the NiFi UI, the bulletin board as JSON, process group status, `nifi.properties`, the content of
`persistent_conf`, or the Consul key-value store. Use them when the user offers them.

## Pod log

The pod or container log is one log. It holds the start scripts, the Consul integration application, the NiFi
bootstrap, and the NiFi application output, in that order. Ask for it as one item.

```bash
kubectl logs <pod> -n <ns> > pod.log
kubectl logs <pod> -n <ns> --previous > pod-previous.log   # after a restart or CrashLoopBackOff
docker logs <container> > pod.log 2>&1
```

The log of a running NiFi grows large. When the user can only paste part of it, ask for the lines around the first
`ERROR` with its full stack trace, or the output of:

```bash
grep -n -m 20 -A 30 'ERROR' pod.log
```

### Raise a logger to DEBUG

Some problems show up only at DEBUG level, for example archive cleanup timings. Set a Consul key
`logger.<package>` to `DEBUG` in `config/<NAMESPACE>/<MICROSERVICE_NAME>` (`MICROSERVICE_NAME` defaults to `qubership-nifi`); NiFi applies it within about a minute, without a
restart. For archive cleanup the key is `logger.org.apache.nifi.controller.repository.FileSystemRepository`. Ask the
user to set it back to `INFO` once the log has a few cleanup runs, since DEBUG on a busy logger is costly.

## Container state and exit code

```bash
kubectl get pod <pod> -n <ns> -o wide
kubectl describe pod <pod> -n <ns>     # Last State, Exit Code, Restart Count, Ready
docker inspect <container> --format '{{.State.Status}} exit={{.State.ExitCode}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restarts={{.RestartCount}}'
```

Exit code 3 comes from the start-script checks of JVM arguments and the sensitive key. Exit code 1 comes from a
failed Consul start, a missing keystore, or a failed NiFi start. No exit with a never-ready pod is a hang. Exit code
137 is a kill, usually by the memory limit: `kubectl describe pod` shows `Reason: OOMKilled` under Last State.

## Environment variables

Ask for the variables that matter for the suspected problem, with secrets redacted:

```bash
kubectl exec <pod> -n <ns> -- env | grep -E '^(NIFI_|CONSUL_|X_JAVA_ARGS|JAVA_TOOL_OPTIONS|KEYSTORE_|TRUSTSTORE_|KEY_PASSWORD|AUTH|NAMESPACE|MICROSERVICE_NAME)' | sed -E 's/((KEY|PASSWORD|TOKEN|SECRET)[^=]*)=.*/\1=<redacted>/'
```

For a pod that keeps exiting, take them from the deployment instead: `kubectl get pod <pod> -n <ns> -o yaml`, the
`env` section, or the Helm values. Never ask for the value of `NIFI_NEW_SENSITIVE_KEY`, a password, or a token. Ask
whether it changed, or for its SHA-256 hash.

## Kubernetes events

```bash
kubectl get events -n <ns> --field-selector involvedObject.name=<pod> --sort-by=.lastTimestamp
```

Look for `Liveness probe failed`, `Readiness probe failed`, `FailedMount`, and `Evicted` (disk pressure
on the node).

## system-diagnostics

Available only while NiFi is up. The user can open System Diagnostics in the NiFi UI, or call the REST API with their
usual client certificate or token:

```bash
curl -sS --cert <client-cert.p12>:<password> --cert-type P12 --cacert <ca.pem> https://<nifi-host>:<port>/nifi-api/system-diagnostics
```

Read `heapUtilization`, `garbageCollection`, and `utilization` in `flowFileRepositoryStorageUsage`,
`contentRepositoryStorageUsage`, and `provenanceRepositoryStorageUsage`.

## Thread dump

For a running NiFi:

```bash
kubectl exec <pod> -n <ns> -- /opt/nifi/nifi-current/bin/nifi.sh diagnostics --verbose /tmp/diagnostics.txt
kubectl cp <ns>/<pod>:/tmp/diagnostics.txt diagnostics.txt
```

With Docker, use `docker exec <container> ...` and `docker cp <container>:/tmp/diagnostics.txt .`. The file holds a
thread dump and more. For a stall, ask for two dumps about 30 seconds apart, so a thread that is truly stuck can be
told from one that is busy.

For a startup that hangs before NiFi starts, dump the Consul integration application instead. Its process runs
`quarkus-run.jar` with Quarkus or `qubership-nifi-consul-application.jar` with Spring:

```bash
kubectl exec <pod> -n <ns> -- sh -c 'ps -eo pid,args | grep -E "quarkus-run.jar|consul-application.jar" | grep -v grep'
kubectl exec <pod> -n <ns> -- sh -c '$JAVA_HOME/bin/jcmd <pid> Thread.print'
```

## Repository storage

```bash
kubectl exec <pod> -n <ns> -- sh -c 'cd /opt/nifi/nifi-current && df -h persistent_data/content_repository flowfile_repository provenance_repository && du -sh persistent_data/content_repository flowfile_repository/* provenance_repository'
kubectl exec <pod> -n <ns> -- sh -c 'grep -E "content_repository|flowfile_repository|provenance_repository" /proc/mounts'
kubectl exec <pod> -n <ns> -- sh -c 'find /opt/nifi/nifi-current/persistent_data/content_repository -path "*/archive/*" -type f | wc -l'
```

Adjust the paths when `NIFI_CONTENT_REPO_DIR`, `NIFI_FLOW_FILE_REPO_DIR`, or `NIFI_PROVENANCE_REPO_DIR` move a
repository. The `/proc/mounts` line gives the file system type, which shows NFS. On NFS the `find` itself can take a
while, which is evidence too.

## Monitoring metrics

Metrics show a trend that a single snapshot cannot. Accept a Grafana panel screenshot (with the time axis, units, and
legend visible), a CSV export of the panel (Inspect, Data, Download CSV), or a plain-text summary for a first look,
such as "content repository went from 40% to 95% over two hours".

Ask for these, from shortly before the problem began until now:

- usage of each repository;
- heap use and GC time;
- queued FlowFile count and queued bytes;
- active threads.

Slow steady growth points at retention or sizing, such as a provenance limit larger than its volume. A sudden jump
points at a burst, such as many FlowFiles with large attributes.

## Deployment config

Helm values or the compose file of the NiFi deployment, with secrets removed. Read the volume of each repository (size,
storage class, NFS or block), memory requests and limits against `-Xmx`, and the environment variables set there.

## Recent changes

Ask in one line what changed before the problem began: an image upgrade, a changed variable or secret, a new or changed
flow, a storage migration, or a load increase. Many startup failures follow a change the user has not connected to
them, such as a regenerated secret.

---
name: nifi-troubleshooting
description: >-
  Diagnoses a broken qubership-nifi (Apache NiFi 2.x) instance from what the user pastes: pod or container log
  lines, stack traces, exit codes, bulletins, or a description of the symptom. Names the cause, quotes the lines that
  prove it, gives the remediation in qubership-nifi terms (environment variables, Consul properties), and asks for
  specific further evidence when the input is not enough. Use it whenever NiFi or qubership-nifi does not start,
  exits with code 1 or 3, hangs at startup, sits in CrashLoopBackOff, or never becomes ready; whenever processors
  stall, queues grow, or writes wait; and whenever a log shows "No space left on device", archive cleanup, content,
  FlowFile or provenance repository errors, a Consul error, an invalid JVM argument, a sensitive key mismatch, a
  flow.json.gz parse error, an OutOfMemoryError, or a TLS failure. Use it even when the user only pastes a log
  excerpt and asks "what is wrong", "why won't it start", or "how do I fix this".
---

# qubership-nifi troubleshooting

Diagnose a qubership-nifi 2.x instance from the evidence the user pastes, ask for what is missing, and give a fix the
user can apply. The catalog in `references/` covers the problems reproduced and verified so far; for anything else,
work from first principles and say that the problem is outside the catalog.

## How qubership-nifi starts

The phase where the log stops tells you which problems are possible, so place the evidence in this sequence first.

1. `entrypoint.sh`, then `start.sh`. Their lines look like
   `[<time>] [INFO] [request_id=] [tenant_id=] [thread=main] [class=c.n.c.n.extensions.start.sh] <message>`.
2. The Consul integration application reads its configuration from Consul and writes `nifi.properties` and
   `logback.xml`. `NIFI_CONSUL_INT_FRAMEWORK` selects the implementation: `quarkus` (the default) or `spring`. While
   it runs, `start.sh` prints `Checking if consul app main execution completed` once a second.
3. Start-script checks: JVM arguments, the sensitive key, flow migrations, and a configuration restore from Consul.
   These exit with code 3 (JVM arguments, sensitive key) or code 1.
4. NiFi itself: `Starting NiFi <version>`, NAR loading, then the flow controller and the web server. NiFi lines look
   like `[<time>][INFO ] ... [class=<logger>] [method=<method>] ... [nifiPgPath=<group path>] <message>`.
   `nifiPgPath` names the process group of the component that logged the line.
5. Runtime: the flow runs. Repository, heap, and flow problems appear here; the container stays up and healthy.

The pod or container log is one log that holds the output of every phase: start scripts, the Consul application, the
NiFi bootstrap, and the NiFi application. Ask for it as one thing, for the current container and, after a restart,
for the previous one.

## Workflow

1. **Place the evidence.** Decide whether this is a startup failure (exit code, CrashLoopBackOff, never ready, the log
   stops before or during phase 4) or a runtime problem (NiFi is up and the flow misbehaves). Note the exit code if
   there is one: 3 and 1 point at different checks.
2. **Match signatures.** Search the pasted text for the signatures in the table below. In a long log, find the first
   `ERROR`, then read its `Caused by:` chain down to the root exception; the first error usually names the cause and
   the later ones are consequences.
3. **Read the reference** for the matched problem. It lists the corroborating evidence, the look-alikes to rule out,
   and the remediation. Do not answer from the signature table alone when the reference names a look-alike that the
   evidence does not exclude.
4. **Check the values in effect, not the defaults.** A deployment often overrides a default through Consul or an
   environment variable. Before a fix names a number, find the value the instance runs with: the `NiFi Properties`
   section of the thread dump file (`nifi.sh diagnostics` writes it), the environment variables, or the deployment
   config. Say when you could not find it and are assuming the default.
5. **Ask for evidence when the match is not certain.** Ask for the fewest items that would settle it, and say for
   each one what it would confirm or rule out. `references/evidence-collection.md` lists what you may ask for and
   the commands for Kubernetes and Docker. Ask only for items on that list.
6. **Check the timing and the numbers.** Compare the time of each snapshot (system-diagnostics, thread dump, metrics)
   with the time of the errors; a snapshot taken before the problem shows the state before it, and the answer must
   say so and ask for a fresh one. Where the evidence gives rates and durations, work out whether they explain the
   symptom: for example, archive cleanup time per file against the number of files archived per minute.
7. **Answer** in the format below.

When files are available to read, as in an attachment or a directory the user names, still say which item you are
reading and why before you read it, as if you had asked the user for it.

## Signatures

| Signature in the log or the symptom | Phase | Problem | Reference |
|---|---|---|---|
| `ERROR: Invalid JVM arguments in NIFI_ADDITIONAL_JVM_ARGS + X_JAVA_ARGS`, `Multiple garbage collectors selected`, exit code 3 | 3 | Conflicting garbage collector arguments | `references/startup-failures.md#invalid-jvm-arguments` |
| `oldKeyHash does not match newKeyHash`, exit code 3 | 3 | Sensitive key differs from the key of earlier starts | `references/startup-failures.md#changed-sensitive-key` |
| `Either NIFI_NEW_SENSITIVE_KEY variable or file under path set in NIFI_SENSITIVE_KEY_PATH must be non-empty`, exit code 3 | 3 | Sensitive key missing | `references/startup-failures.md#changed-sensitive-key` |
| `APPLICATION FAILED TO START` with a `consul:` location, or `Failed to start quarkus` with `UnresolvedAddressException` or `ConnectException`; then `Consul app java process has terminated prematurely`, exit code 1 | 2 | Consul cannot be reached | `references/startup-failures.md#consul-unreachable` |
| `Checking if consul app main execution completed` repeated for minutes, no error, never ready | 2 | Consul accepts the connection and does not respond | `references/startup-failures.md#consul-unreachable` |
| `Failed to start Server` from `JettyServer` with `Could not parse flow as a VersionedDataflow`, `JsonParseException`, or `Unexpected end of ZLIB input stream`, exit code 1 | 4 | Corrupted `flow.json.gz` | `references/startup-failures.md#corrupted-flowjsongz` |
| NAR loading lines, then `start to send crash dump`, exit code 1 | 4 | Wrong keystore or truststore configuration | `references/startup-failures.md#invalid-certificate` |
| `Keystore file specified (...) does not exist.`, `Cannot find truststore.p12`, exit code 1 | 3 | Keystore or truststore file missing | `references/startup-failures.md#invalid-certificate` |
| `SSLHandshakeException`, `PKIX path validation failed` | 5 | Truststore lacks the remote party's CA | `references/startup-failures.md#invalid-certificate` |
| `Unable to write flowfile content to content repository container default due to archive file size constraints; waiting for archive cleanup` | 5 | Content archive cannot be cleaned | `references/repositories.md#content-archive-cannot-be-cleaned` |
| Archive grows past `archive.max.retention.period`, archived claim files in the thousands, `Successfully archived <n> Resource Claims ... in <n> millis` with a high time per claim, content repository on NFS | 5 | Archive cleanup too slow | `references/repositories.md#archive-cleanup-too-slow` |
| `Marking Write-Ahead journal file ... as poisoned`, `FlowFile Repository failed to update`, `Unable to checkpoint FlowFile Repository`, `Cannot update journal file ... because no header has been written yet` or `... because this journal has already encountered a failure` | 5 | FlowFile repository out of space | `references/repositories.md#flowfile-repository-out-of-space` |
| `Failed to write events to the Event Store`, `Failed to compress ... on rollover`, bulletin `Failed to write Provenance Events to the repository` | 5 | Provenance repository out of space | `references/repositories.md#provenance-repository-out-of-space` |
| `java.lang.OutOfMemoryError: Java heap space` | 5 | Heap too small for the queued FlowFiles and their attributes; outside the catalog | [Settings fixes often need](#settings-fixes-often-need), [Rules for the fix](#rules-for-the-fix) |
| `java.io.IOException: Failed to write to FileSystemRepository Stream` with `No space left on device` | 5 | Content repository itself full | `references/repositories.md#content-repository-full` |

`No space left on device` alone does not say which volume is full: the stack trace and the logger do. A trace through
`org.apache.nifi.wali` or `WriteAheadFlowFileRepository` is the FlowFile repository, `org.apache.nifi.provenance` is the
provenance repository, and `FileSystemRepository` is the content repository.

## Answer format

Use these sections, in this order. Keep each one short; the user is in the middle of an incident.

```markdown
**Diagnosis:** <the problem in one sentence, and how sure you are: confirmed, likely, or possible>

**Evidence:**
- `<a log line or value quoted exactly>` - <what it shows>

**Cause:** <the mechanism in two to four sentences>

**Fix:**
1. <a step the user can apply now: the variable, Consul property, or volume to change, and to what>
2. <restart or verify step>

**Prevention:** <what keeps it from coming back, if anything beyond the fix>

**If this does not fit:** <what you would check next, and which evidence would show it>
```

When the evidence is not enough for a diagnosis, replace the template with the candidate problems, ordered by
likelihood, and the evidence requests that tell them apart.

## When nothing in the catalog matches

Say so in the first line, then still help:

- Name the phase from the log, the first `ERROR`, and the root `Caused by:` exception.
- Give the most likely explanation from general NiFi and JVM knowledge, marked as outside the verified catalog.
- Ask for the evidence that would confirm it, from `references/evidence-collection.md`.

Do not stretch a catalog entry over evidence it does not explain. One `No space left on device` does not make every
volume full, and a slow processor is not an archive problem unless the archive warning is in the log.

## Settings fixes often need

| Setting | Consul key (preferred) | Environment variable | Default |
|---|---|---|---|
| Heap size | none | `NIFI_JVM_HEAP_INIT`, `NIFI_JVM_HEAP_MAX` | `512m` each |
| Content archive threshold | `nifi.content.repository.archive.max.usage.percentage` | `NIFI_CONTENT_ARCHIVE_MAX_USAGE` | `50%` |
| Content archive on or off | `nifi.content.repository.archive.enabled` | `NIFI_CONTENT_ARCHIVE_DISABLED` (any value turns it off) | `true` |
| Content archive retention | `nifi.content.repository.archive.max.retention.period` | none | `7 days` |
| Content archive cleanup interval | `nifi.content.repository.archive.cleanup.frequency` | none | `1 minute` |
| Provenance size limit | `nifi.provenance.repository.max.storage.size` | none | `1 GB` |
| Provenance time limit | `nifi.provenance.repository.max.storage.time` | none | `30 days` |
| Flow configuration archive size | `nifi.flow.configuration.archive.max.storage` | none | `300 MB` |
| Repository locations | none | `NIFI_CONTENT_REPO_DIR`, `NIFI_FLOW_FILE_REPO_DIR`, `NIFI_PROVENANCE_REPO_DIR` | see `references/repositories.md` |

Heap is set in the deployment; also check that the container memory limit leaves room above the new heap for
non-heap memory.

## Rules for the fix

- Prefer Consul for NiFi settings. A `nifi.*` property is a Consul key in `config/<NAMESPACE>/<MICROSERVICE_NAME>` (`MICROSERVICE_NAME` defaults to `qubership-nifi`) or
  `config/<NAMESPACE>/application` and takes effect after a restart. Logger levels are Consul keys
  `logger.<package>` in the same locations and apply within about a minute, without a restart.
- Mention an environment variable as the alternative where qubership-nifi has one, set in the deployment (Helm values
  or the compose file). `start.sh` applies these variables after the Consul application has written
  `nifi.properties`, so a variable that is set overrides the Consul key for the same property. When the user changes
  a Consul key and nothing happens, check whether such a variable is set.
- Remove attributes the flow does not need. Some processors write many attributes, InvokeHTTP among them, and each
  attribute of a queued FlowFile takes heap, FlowFile repository space, and a copy in every provenance event. Where
  heap, the FlowFile repository, or the provenance repository runs short, recommend deleting unneeded attributes
  early in the flow, for example with UpdateAttribute's `Delete Attributes Expression`.
- Warn before any step that loses data: deleting a repository, the flow configuration, or archived content. Prefer
  the step that keeps data, such as restoring an archived flow or growing a volume.
- Never ask the user to paste a secret. Ask for the hash or for whether a value changed.
- This catalog was built on qubership-nifi 2.x. On a 1.x image, say that messages and defaults may differ.

# Repository problems

Runtime problems with the three NiFi repositories. NiFi stays up and its health check passes; the flow slows down,
stalls, or loses provenance. Each repository is a directory that can sit on its own volume:

| Repository | Holds | Default path in qubership-nifi | Variable that moves it |
| --- | --- | --- | --- |
| Content | FlowFile content, in claim files, and the archive of content no FlowFile references | `./persistent_data/content_repository` | `NIFI_CONTENT_REPO_DIR` |
| FlowFile | Attributes and state of every queued FlowFile: a checkpoint snapshot plus journals | `./flowfile_repository` | `NIFI_FLOW_FILE_REPO_DIR` |
| Provenance | One event per change to a FlowFile, with all its attributes | `./provenance_repository/provenance_repository` | `NIFI_PROVENANCE_REPO_DIR` |

Paths are relative to `/opt/nifi/nifi-current`.

Every problem on this page except the full content repository was reproduced on 1.x images too; the full content
repository was not reproduced on either version. NiFi 1.x logs the content archive messages unchanged; where other
messages or behavior differ, the section has a "NiFi 1.x" subsection.

Tell the problems apart by the logger in the error and by which volume is full:

| Evidence | Content archive cannot be cleaned | Archive cleanup too slow | FlowFile repository full | Provenance repository full | Content repository full |
| --- | --- | --- | --- | --- | --- |
| Key log line | `Unable to write flowfile content ... waiting for archive cleanup` | slow `Successfully archived <n> Resource Claims ... in <n> millis`; DEBUG cleanup timings | `Marking Write-Ahead journal file ... as poisoned` | `Failed to write events to the Event Store` | `Failed to write to FileSystemRepository Stream` |
| Full volume | none; content at 50-100% | none | FlowFile repository at 100% | provenance repository at 100% | content repository at 100% |
| Flow | writers stall up to a minute at a time | normal until usage reaches the threshold | processors that create or change FlowFiles fail | keeps running | writes fail |

## Content archive cannot be cleaned

### Signature

Processors that write content stall for up to a minute at a time, although the content repository still has free
space. Queues upstream grow, and back pressure spreads. The log repeats, once for each blocked write:

```text
[...][WARN ] [...] [class=org.apache.nifi.controller.repository.FileSystemRepository] [method=waitForArchiveExpiration] [...] Unable to write flowfile content to content repository container default due to archive file size constraints; waiting for archive cleanup. Total number of files currently archived = 14
```

The same warning appears as a bulletin of the processor that waits.

### Cause

NiFi moves a content claim to the archive once no FlowFile references it. Archive cleanup runs every
`nifi.content.repository.archive.cleanup.frequency` (1 minute by default) and removes archived claims when the
repository usage is above `nifi.content.repository.archive.max.usage.percentage` (50% by default), or when a claim is
older than `nifi.content.repository.archive.max.retention.period` (7 days by default).

Writes wait for cleanup while the archive is not empty and the usage is above
`nifi.content.repository.archive.backpressure.percentage`, which defaults to the usage threshold plus 2%. When live
content alone, the content of queued FlowFiles, is above the threshold, every claim a processor drops goes to the
archive and pushes the usage further over. The next write waits until the next cleanup run. Cleanup succeeds each time
and still cannot bring the usage below the threshold, so the stall repeats every cycle.

### Corroborating evidence

- Repository storage: content repository usage above 50%, while the archive directories hold little.
- `system-diagnostics`: `contentRepositoryStorageUsage` utilization above the threshold.
- Thread dump: processor threads parked in `FileSystemRepository$ContainerState.waitForArchiveExpiration`.
- Monitoring metrics: content repository usage flat above the threshold, with a small dip once a minute.
- Log with DEBUG for `org.apache.nifi.controller.repository.FileSystemRepository`: once a minute,
  `need to free <n> bytes`, `Successfully deleted <n> files`, `Archive cleanup completed ... will now allow writing`,
  and within seconds the `Unable to write flowfile content` warning again.

Look-alike: on a slow volume, cleanup that takes minutes produces the same warning. Check the cleanup duration
(see the next section) when the usage is only just above the threshold.

### Fix

- Make the content repository volume larger, so that the content the flow keeps queued stays below the threshold.
- Limit queued content with back pressure on the connections that hold the most data.
- Raise the threshold with the Consul key `nifi.content.repository.archive.max.usage.percentage` (for example `70%`),
  or turn the archive off with `nifi.content.repository.archive.enabled=false`, then restart. The environment
  variables `NIFI_CONTENT_ARCHIVE_MAX_USAGE` and `NIFI_CONTENT_ARCHIVE_DISABLED` do the same and override the Consul
  keys when set. Without the archive, provenance cannot replay or show the content of dropped FlowFiles.

## Archive cleanup too slow

### Signature

There is no error. Archived claim files stay longer than `nifi.content.repository.archive.max.retention.period`, and
the archive directories hold thousands of files. When usage reaches the archive threshold, writes wait for the slow
cleanup with the warning from the previous section.

With DEBUG for `org.apache.nifi.controller.repository.FileSystemRepository`, each run logs its phases, and the
`delete expired` phase takes most of the time:

```text
[...][DEBUG] [thread=Cleanup Archive for default] [...] [method=destroyExpiredArchives] [...] Oldest Archive Date for Container default is <date>; delete expired = <n> ms, sort remaining = <n> ms, delete oldest = <n> ms, cleanup = <n> ms
```

At INFO level, without DEBUG, the archiving step shows the same slowness. NiFi moves released claims into the archive
in batches and logs each batch, for example:

```text
[...][INFO ] [thread=FileSystemRepository Workers Thread-1] [...] Successfully archived 1030 Resource Claims for Container default in 5146 millis
```

Divide the time by the claim count. Moving a file into the archive is one rename, so the time per claim reflects
the latency of the storage; compare it with the rate of new claims, as described below.

To find which volume each repository is on, read the `<repository> File Store:` lines in the thread dump file, the
mounts in the repository storage, or the deployment config. Do not rely on the line
`The following Repositories share the same File Store: [...]` near the top of the thread dump file: in testing it
listed all three repositories while the content repository was on its own volume.

### Cause

The content repository splits claims into 1024 partition directories, each with an `archive` subdirectory. Cleanup
walks them with `Files.walkFileTree`, reads the attributes of every archived file, and deletes each expired one, one
file at a time on one thread. A claim gets a file of its own once it is larger than
`nifi.content.claim.max.appendable.size` (100 KB by default), so a flow that writes many such FlowFiles leaves many
archived files. On NFS each attribute read and each deletion is a synchronous round trip to the server, so the cost
of a run grows with the file count times the network latency.

A file can stay in the archive for the retention period, plus the wait for the next run, plus the run itself. The
next run starts `nifi.content.repository.archive.cleanup.frequency` after the previous one ends.

### Corroborating evidence

- Repository storage: the mount type of the content repository (`nfs` or `nfs4`), and `claim_files_archived` in the
  thousands.
- Deployment config: an NFS volume or an NFS-backed storage class for the content repository.
- Thread dump during a run: the `Cleanup Archive for default` thread in `FileSystemRepository.destroyExpiredArchives`
  under `Files.walkFileTree`, in `lstat` or `unlink`.
- Pod log at INFO: the time per claim in the `Successfully archived <n> Resource Claims ... in <n> millis` lines.
- Log with DEBUG enabled: `delete expired` durations, and the number of files each run deletes.

### Work out whether cleanup keeps up

Timings depend on the storage and the network, so measure them on the instance rather than compare with a reference
figure. From the DEBUG log:

1. Cost per file: `delete expired` time divided by the number of files the run deleted.
2. Cleanup capacity: files per second that one thread can delete at that cost.
3. Load: claim files archived per second, from the `Successfully archived <n> Resource Claims` lines or from the
   growth of `claim_files_archived`.

When the load is well below the capacity, cleanup keeps up, and files only outlive the retention by the interval and
the duration of a cleanup run; say so, and say how much headroom is left. When the load approaches the capacity,
the archive grows until usage reaches the threshold, and writes then wait as in the previous section.

### Fix

Fix the slowness first, then reduce the work:

- Put the content repository on local or block storage, for example a `ReadWriteOnce` volume, not NFS. If the
  `File Store:` lines or the mounts put the FlowFile or provenance repository on NFS as well, move it too: every
  session commit writes to them.
- If the repository must stay on NFS, reduce the latency of each call: an NFS server close to the node, a hard mount
  over a fast network, and no extra synchronous options on the export or the mount.
- Shorten the wait between runs with the Consul key `nifi.content.repository.archive.cleanup.frequency` (for example
  `10 secs`), so each run has fewer files to delete.
- Keep fewer archived files: shorten `nifi.content.repository.archive.max.retention.period`, lower
  `nifi.content.repository.archive.max.usage.percentage`, or turn the archive off with
  `nifi.content.repository.archive.enabled=false`, all as Consul keys. `NIFI_CONTENT_ARCHIVE_MAX_USAGE` and
  `NIFI_CONTENT_ARCHIVE_DISABLED` are the environment variable alternatives.
- Upgrade to a newer qubership-nifi release; older Apache NiFi versions are affected more.

### NiFi 1.x

The cleanup messages, the DEBUG timings, and the `Cleanup Archive for default` thread are the same. NiFi 1.x writes all
FlowFiles of one session into one claim file, even past `nifi.content.claim.max.appendable.size`. A flow that commits
many FlowFiles per session therefore leaves fewer archived files on 1.x than on 2.x, and a flow that commits one
FlowFile per session leaves as many. The method in [Work out whether cleanup keeps
up](#work-out-whether-cleanup-keeps-up) applies unchanged. In testing on NFS, 1.x cleanup runs took about 40 times
longer than on a local disk, as on 2.x.

## FlowFile repository out of space

### Signature

Processors that create or change FlowFiles fail and yield, with bulletins like:

```text
GenerateFlowFile[id=...] Processing halted: yielding [1 sec]: java.lang.IllegalStateException: Cannot update journal file ./flowfile_repository/journals/35.journal because no header has been written yet.
```

The log has errors from the write-ahead log, first when the disk fills:

```text
[...][ERROR] [...] [class=org.apache.nifi.wali.LengthDelimitedJournal] [method=poison] [...] Marking Write-Ahead journal file ./flowfile_repository/journals/16.journal as poisoned due to java.io.IOException: No space left on device
[...][ERROR] [...] [class=o.apache.nifi.controller.repository.StandardProcessSession] [method=commit] [...] Failed to commit session StandardProcessSession[id=32]. Will roll back.
org.apache.nifi.processor.exception.ProcessException: FlowFile Repository failed to update
Caused by: java.io.IOException: No space left on device
```

and then on every checkpoint, every 20 seconds by default:

```text
Failed to synch Write-Ahead Log's journal to disk at ./flowfile_repository
Failed to create new journal file ./flowfile_repository/journals/34.journal
[...][ERROR] [...] [class=o.a.nifi.controller.repository.WriteAheadFlowFileRepository] [...] Unable to checkpoint FlowFile Repository
java.io.IOException: No space left on device
```

Between checkpoints the processor errors say
`Cannot update journal file ./flowfile_repository/journals/<n>.journal because no header has been written yet` or
`... because this journal has already encountered a failure when attempting to write to the file`. NiFi stays healthy
and answers REST requests; the content repository has free space.

### Cause

The FlowFile repository keeps the state of every queued FlowFile: its attributes and the location of its content.
Each change is appended to a journal, and a checkpoint writes a snapshot of all FlowFiles and starts new journals.
Large attributes on many queued FlowFiles make both large, and a small volume fills up.

A journal that failed a write is marked poisoned, and NiFi refuses further updates to it until a checkpoint succeeds.
A checkpoint needs room for the snapshot and a new journal, so it fails for as long as the volume is full.

### Corroborating evidence

- Repository storage: the FlowFile repository volume at 100%, split between `checkpoint` and `journals`.
- Deployment config: the size of the FlowFile repository volume.
- `system-diagnostics`: `flowFileRepositoryStorageUsage` at or near 100%.
- Monitoring metrics: queued FlowFile count rising up to the failure, with little queued content.

### Fix

- Give the FlowFile repository a larger volume: at least twice the size of the attributes the flow can have queued,
  for the snapshot and the journals.
- Keep large data in FlowFile content, not in attributes, and delete attributes the flow does not need, such as the
  response headers InvokeHTTP adds.
- Limit how many FlowFiles can queue with back pressure on the connections that hold the most.

Once space is available, the next checkpoint succeeds and processors recover; restart NiFi if they do not.

### NiFi 1.x

The bulletins are the same. The log messages differ in their text:

```text
Marking Write-Ahead journal file ./flowfile_repository/journals/15.journal as poisoned due to {}
java.io.IOException: No space left on device
Failed to create new journal file ./flowfile_repository/journals/33.journal due to java.io.IOException: No space left on device
Unable to checkpoint FlowFile Repository due to java.io.IOException: No space left on device
Failed to asynchronously commit session StandardProcessSession[id=<n>] for GenerateFlowFile[id=<id>]
```

The literal `{}` in the poisoned journal line is a NiFi 1.x logging defect; the exception follows on the next line.

## Provenance repository out of space

### Signature

The flow keeps running and no processor reports an error, but the log has an error for each session that commits
while the volume is full:

```text
[...][ERROR] [...] [class=org.apache.nifi.provenance.WriteAheadProvenanceRepository] [method=registerEvents] [...] Failed to write events to the Event Store
java.io.IOException: No space left on device
```

The first one can name another exception:
`Cannot update Provenance Repository because this Record Writer has already failed to write to the Repository`.
Event files that roll over cannot be compressed, and their events are lost:

```text
[...][ERROR] [...] [class=o.apache.nifi.provenance.serialization.EventFileCompressor] [method=run] [...] Failed to compress ./provenance_repository/provenance_repository/20149.prov on rollover
```

The bulletin board shows `Failed to write Provenance Events to the repository. See logs for more details.` in
category `Provenance Repository`, with no source component.

### Cause

Each provenance event holds all attributes of the FlowFile, so FlowFiles with large attributes fill the repository
fast. NiFi deletes the oldest event files when `nifi.provenance.repository.max.storage.time` (30 days by default) or
`nifi.provenance.repository.max.storage.size` (1 GB by default) is reached. The size limit counts the whole repository
directory, and the cleanup that enforces it runs every `nifi.provenance.repository.maintenance.frequency` (1 minute by
default). At rollover, at `nifi.provenance.repository.rollover.size` (100 MB by default), NiFi writes a compressed copy
next to the event file before it deletes the original, so both take space for a while.

The volume fills in two ways:

- `max.storage.size` is larger than the volume. The limit is never reached, and writes fail until events age out.
  `system-diagnostics` shows the provenance repository at 100%.
- `max.storage.size` leaves too little room for the events written between two cleanup runs plus a rollover.
  Writes fail in bursts until the next cleanup run, and the repository then sits below the limit.

### Work out the room the repository needs

The room above `max.storage.size` depends on the event rate, so work it out from the log of the instance. Each
`Successfully rolled over Event Writer ... due to MAX_BYTES_REACHED` line marks one full event file of
`rollover.size`, so the time between these lines gives the write rate. The room needed is:

1. The bytes written in one `maintenance.frequency` interval: `rollover.size` times the rollovers per interval.
2. Plus one active event file and its compressed copy: about 1.5 times `rollover.size`.

For example, a 100 MB rollover every 17 seconds is about 350 MB per one-minute interval, plus 150 MB, so about
500 MB of room. When the room alone is larger than the volume, no `max.storage.size` fits: grow the volume, or cut
the room with a smaller `rollover.size` and a shorter `maintenance.frequency`, and say which one you chose. Do not
recommend a limit without this calculation; a fixed headroom figure fails on a busy flow.

### Corroborating evidence

- Repository storage: the provenance repository volume at or near 100%.
- Deployment config: the provenance volume size, and `nifi.provenance.repository.max.storage.size` if it is set.
- `system-diagnostics`: `provenanceRepositoryStorageUsage` at 100% in the first case.
- Monitoring metrics: provenance repository usage climbing to the volume size.

### Fix

- Check the value in effect first: the `NiFi Properties` section of the thread dump file shows it, and a deployment
  often sets it in Consul. Then set `nifi.provenance.repository.max.storage.size` in Consul to the volume size minus
  the room worked out above, or grow the volume when the room does not fit.
- When the volume cannot grow, lower `nifi.provenance.repository.rollover.size` and shorten
  `nifi.provenance.repository.maintenance.frequency` in Consul until the room fits, or lower `max.storage.time`.
- Keep large data in FlowFile content, not in attributes, and delete attributes the flow does not need: every event
  stores all attributes. Remove processors that change FlowFiles without need; each change adds an event.

### NiFi 1.x

The errors and the bulletin are the same. With `max.storage.size` larger than the volume, NiFi 1.x still fails in
bursts, as in the case where `max.storage.size` leaves too little room, rather than staying at 100%. After each burst
the writer rolls over, the compression of the rolled-over file fails, and NiFi deletes the uncompressed file. In testing
the usage reached 100% only for moments: samples every 5 seconds swung between 62% and 86%, and `df` and
`system-diagnostics` showed 87% at the end. A snapshot below 100% therefore does not rule this problem out on 1.x; the
`No space left on device` lines do. The fix is the same: set `max.storage.size` to the volume size minus the room worked
out in [Work out the room the repository needs](#work-out-the-room-the-repository-needs), or grow the volume.

## Content repository full

### Signature

Writes fail outright rather than wait:

```text
java.io.IOException: Failed to write to FileSystemRepository Stream
Caused by: java.io.IOException: No space left on device
```

This case has not been reproduced on its own; it appears when the content volume fills between two archive cleanup
runs, or when the archive is disabled and live content fills the volume.

### Corroborating evidence

- Repository storage: the content repository volume at 100%, and how much of it the archive holds.
- `system-diagnostics`: `contentRepositoryStorageUsage` at 100%.

### Fix

Grow the content repository volume, or reduce queued content with back pressure. If the archive holds much of the
volume, lower `nifi.content.repository.archive.max.usage.percentage` in Consul so cleanup starts earlier, or set
`NIFI_CONTENT_ARCHIVE_MAX_USAGE`, which overrides the Consul key.

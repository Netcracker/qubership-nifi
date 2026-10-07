# FlowFile repository out of space

The file system of the FlowFile repository is full. NiFi cannot write the journal that records FlowFile changes, so
processors that create or change FlowFiles fail, and every checkpoint of the repository fails too.

```bash
./run.sh flowfile-repo-full
```

## Symptom

- Processors that create FlowFiles fail and yield, with bulletins like this one:

  ```text
  GenerateFlowFile[id=...] Processing halted: yielding [1 sec]: java.lang.IllegalStateException: Cannot update journal file ./flowfile_repository/journals/35.journal because no header has been written yet.
  ```

- `nifi-app.log` has errors from the write-ahead log, first when the disk fills:

  ```text
  Marking Write-Ahead journal file ./flowfile_repository/journals/16.journal as poisoned due to java.io.IOException: No space left on device
  org.apache.nifi.processor.exception.ProcessException: FlowFile Repository failed to update
  Caused by: java.io.IOException: No space left on device
  ```

  and then on every checkpoint, every 20 seconds by default:

  ```text
  Failed to synch Write-Ahead Log's journal to disk at ./flowfile_repository
  Failed to create new journal file ./flowfile_repository/journals/34.journal
  Unable to checkpoint FlowFile Repository
  java.io.IOException: No space left on device
  ```

- The content repository has free space, and NiFi stays healthy and answers REST requests.

## Cause

The FlowFile repository keeps the state of every FlowFile that is queued or being processed: its attributes and the
location of its content. Each change is appended to a journal, and a checkpoint writes a snapshot of all FlowFiles
and starts new journals. Large attributes on many queued FlowFiles make both the journals and the snapshot large, and a
small volume, such as a memory-backed one, runs out of space.

A journal that failed a write is marked as poisoned, and NiFi refuses further updates to it until a checkpoint
succeeds. A checkpoint needs space for the snapshot and a new journal, so it fails for as long as the volume is full.

## How the scenario reproduces it

The FlowFile repository (`./flowfile_repository`) is a tmpfs of `FLOWFILE_REPO_SIZE`. `Generate large attributes`
creates 100 empty FlowFiles per second, each with two attributes of 10240 characters, into the `queued flowfiles`
queue. The `Hold` processor stays stopped, and the queue has back pressure set to 10000000 FlowFiles, so the FlowFiles
stay queued.

`scenario.sh` runs the generator until `No space left on device` appears in `nifi-app.log`, then for
`AFTER_FULL_SECONDS` more, and stops it. With the defaults the repository fills at about 3200 queued FlowFiles, 40
seconds after the start, and the snapshot and the journals take about half of it each.

| Variable             | Default | Description                                                                  |
|----------------------|---------|------------------------------------------------------------------------------|
| `FLOWFILE_REPO_SIZE` | `64m`   | Size of the tmpfs that holds the FlowFile repository                         |
| `AFTER_FULL_SECONDS` | `60`    | Seconds the generator keeps running after the first `No space left on device` |

## Expected evidence

| File                         | What to look for                                                                                     |
|------------------------------|------------------------------------------------------------------------------------------------------|
| `scenario-flowfile-repo.txt` | `df` at 100%; `du` split between `checkpoint` and `journals`; the write-ahead log errors; `no_space_left` above 0 |
| `nifi-app.filtered.log`      | `Marking Write-Ahead journal file ... as poisoned`, `FlowFile Repository failed to update`, and `Unable to checkpoint FlowFile Repository` every 20 seconds |
| `bulletins.json`             | `Cannot update journal file ... because no header has been written yet` from `Generate large attributes` |
| `root-status.json`           | About 3200 FlowFiles in `queued flowfiles` and no content bytes                                       |
| `storage.txt`                | The content repository far from full                                                                 |
| `manifest.txt`               | `nifi_state=running`, `nifi_health=healthy`                                                          |

## NiFi 1.x

NiFi 1.x reproduces the issue with the same bulletin, and the messages differ in their text:

- The poisoned journal message has a literal `{}` in place of the exception, which follows on the next line:

  ```text
  Marking Write-Ahead journal file ./flowfile_repository/journals/15.journal as poisoned due to {}
  java.io.IOException: No space left on device
  ```

- The checkpoint errors name the exception in the message:

  ```text
  Failed to create new journal file ./flowfile_repository/journals/33.journal due to java.io.IOException: No space left on device
  Unable to checkpoint FlowFile Repository due to java.io.IOException: No space left on device
  ```

- Each failed session commit also logs `Failed to asynchronously commit session StandardProcessSession[id=<n>] for
  GenerateFlowFile[id=<id>]`.

Measured on qubership-nifi 1.0.9 (Apache NiFi 1.28.1).

## Remediation

- Give the FlowFile repository a larger volume. The volume holds a snapshot of all queued FlowFiles and the journals
  written since the last checkpoint, so size it for at least twice the attributes the flow can have queued.
- Keep large data in the content of FlowFiles, not in their attributes.
- Limit how many FlowFiles the flow can queue with back pressure on the connections that hold the most.

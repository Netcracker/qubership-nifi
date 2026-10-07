# Archive cannot be cleaned

Queued FlowFile content takes more of the content repository than
`nifi.content.repository.archive.max.usage.percentage` allows (50% by default). Archive cleanup then runs on every
cycle and cannot bring the usage below the threshold, and processors that write content wait for it.

```bash
./run.sh content-archive-full
```

## Symptom

- Processors that write content stall for up to a minute at a time, although the content repository still has free
  space.
- `nifi-app.log` has warnings like this one, repeated for each blocked write:

  ```text
  Unable to write flowfile content to content repository container default due to archive file size constraints; waiting for archive cleanup. Total number of files currently archived = 14
  ```

- Queues upstream of the stalled processors grow, and back pressure spreads through the flow.

## Cause

NiFi moves a content claim to the archive once no FlowFile references it. Archive cleanup runs every
`nifi.content.repository.archive.cleanup.frequency` (1 minute by default) and removes archived claims in two cases:

1. Content repository usage is above `nifi.content.repository.archive.max.usage.percentage`.
2. An archived claim is older than `nifi.content.repository.archive.max.retention.period` (7 days by default).

Writes wait for archive cleanup while the archive is not empty and the usage is above
`nifi.content.repository.archive.backpressure.percentage`, which defaults to the usage threshold plus 2%. When live
content alone is above the threshold, every claim a processor drops goes to the archive and pushes the usage further
over. The next write waits until the next cleanup run removes that claim. Cleanup succeeds each time and still cannot
bring the usage below the threshold, so the pattern repeats on every cycle.

## How the scenario reproduces it

The content repository is a tmpfs of `CONTENT_REPO_SIZE`. The flow has two branches:

- `Archive filler` generates a 10 MB FlowFile per second and drops it, so each one goes to the archive.
- `Generate held content` generates a 50 MB FlowFile per second into the `held content` queue. The `Hold` processor
  stays stopped, so this content stays live.

`scenario.sh` runs the archive filler for `ARCHIVE_FILLER_SECONDS`, then queues held content until it takes at least
`TARGET_PERCENT` of the repository, then runs the archive filler again for `AFTER_TARGET_SECONDS`. The queue check
runs every 2 seconds, so the held content usually ends up 50 MB to 150 MB above the target.

| Variable                 | Default | Description                                                                         |
|--------------------------|---------|-------------------------------------------------------------------------------------|
| `CONTENT_REPO_SIZE`      | `1g`    | Size of the tmpfs that holds the content repository                                 |
| `TARGET_PERCENT`         | `65`    | Share of the repository, in percent, that held content takes at the end of the load |
| `ARCHIVE_FILLER_SECONDS` | `10`    | Seconds the archive filler runs before the held content is queued                   |
| `AFTER_TARGET_SECONDS`   | `180`   | Seconds the archive filler runs after the target is reached                         |

Keep `TARGET_PERCENT` plus the archive filler output well below 100%. Archive cleanup runs only once a minute, and a
repository that fills up between two runs fails writes with a different error:

```text
java.io.IOException: Failed to write to FileSystemRepository Stream
```

## Expected evidence

Each minute, one archive cleanup run deletes the archived claims and lets writers continue. A few seconds later the
next dropped claim pushes the usage over the limit again, and the writers wait until the next run.

| File                           | What to look for                                                                                                                                                                                                          |
|--------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `scenario-archive-cleanup.txt` | Once a minute: `need to free <n> bytes`, `Successfully deleted <n> files`, `Archive cleanup completed ... will now allow writing`, then within seconds `Unable to write flowfile content ... waiting for archive cleanup` |
| `storage.txt`                  | `used_percent` above 50, while the archive directories hold little                                                                                                                                                        |
| `system-diagnostics.json`      | `contentRepositoryStorageUsage` utilization above 50%                                                                                                                                                                     |
| `nifi.properties.content.txt`  | `nifi.content.repository.archive.max.usage.percentage=50%`                                                                                                                                                                |
| `root-status.json`             | The `held content` queue holding more than half of the repository                                                                                                                                                         |
| `bulletins.json`               | The `Unable to write flowfile content` warning as a bulletin                                                                                                                                                              |
| `diagnostics.txt`              | A processor thread parked in `FileSystemRepository$ContainerState.waitForArchiveExpiration`                                                                                                                               |

## NiFi 1.x

NiFi 1.x reproduces the issue with the same messages, the same once-a-minute cycle, the same bulletin, and the same
`waitForArchiveExpiration` thread. The flow definition, exported from NiFi 2.x, loads into NiFi 1.x unchanged.
Measured on qubership-nifi 1.0.9 (Apache NiFi 1.28.1).

## Remediation

- Make the content repository larger, so that the content the flow keeps queued stays below the threshold.
- Limit the queued content with back pressure on the connections that hold the most data.
- Raise the threshold with the `NIFI_CONTENT_ARCHIVE_MAX_USAGE` environment variable, or turn the archive off with
  `NIFI_CONTENT_ARCHIVE_DISABLED`. Without the archive, provenance cannot replay or show the content of dropped
  FlowFiles.

# Provenance repository out of space

The file system of the provenance repository is full. NiFi cannot write provenance events, so the events of FlowFiles
processed while the volume is full are lost. Event files that fail to compress at rollover lose the events they already
held. The flow itself keeps running.

```bash
./run.sh provenance-repo-full
```

## Symptom

- `nifi-app.log` has an error for each session that commits while the volume is full, with a stack trace that goes
  through `StandardProcessSession.commit`. The first one has a different cause:

  ```text
  Failed to write events to the Event Store
  java.io.IOException: Cannot update Provenance Repository because this Record Writer has already failed to write to the Repository
  ```

  ```text
  Failed to write events to the Event Store
  java.io.IOException: No space left on device
  ```

- Event files that roll over cannot be compressed. NiFi still deletes the uncompressed file and keeps a truncated
  `.gz`, so the events in that file are lost:

  ```text
  Failed to compress ./provenance_repository/provenance_repository/20149.prov on rollover
  java.io.IOException: No space left on device
  ```

- The bulletin board has an error with category `Provenance Repository` and no source component:

  ```text
  Failed to write Provenance Events to the repository. See logs for more details.
  ```

- Processors keep processing FlowFiles, and no processor reports an error.
- When `max.storage.size` is larger than the volume, `GET /nifi-api/system-diagnostics` reports the provenance
  repository storage at 100%. When it only leaves too little room, the 100% lasts until the next cleanup run, and the
  repository then sits below the limit with `Failed to compress` errors in the log.

## Cause

The provenance repository records an event for every change to a FlowFile: its creation, a change to its attributes or
content, and its drop. Each event holds all attributes of the FlowFile, so FlowFiles with large attributes fill the
repository faster.

NiFi deletes the oldest event files when either limit is reached:

- `nifi.provenance.repository.max.storage.time`, 30 days by default;
- `nifi.provenance.repository.max.storage.size`, 1 GB by default.

The size limit counts the whole repository directory: event files, the index, and the table of contents. The cleanup
that enforces both limits runs every `nifi.provenance.repository.maintenance.frequency`, 1 minute by default, so the
repository grows past `max.storage.size` by the events written between two runs. NiFi writes events to an
uncompressed event file until it reaches `nifi.provenance.repository.rollover.size` (100 MB by default). At rollover
it writes a compressed copy next to that file and then deletes the original, so for a while both take space.

The file system fills up when the limits do not fit the volume:

- `max.storage.size` is larger than the volume. The size limit is never reached, and the repository grows until the
  volume is full. Writes keep failing until the events age out after `max.storage.time`.
- `max.storage.size` leaves too little room on the volume for the events of one cleanup interval and a rollover. With
  the default rollover size that is typically 100-200 MiB, more for a flow with a high event rate. Writes fail in bursts
  until the next cleanup run deletes the oldest event file, and the failures repeat with later rollovers.

## How the scenario reproduces it

The provenance repository (`./provenance_repository`) is a tmpfs of `PROVENANCE_REPO_SIZE`, and `scenario.sh` sets
`nifi.provenance.repository.max.storage.size` to `PROVENANCE_MAX_STORAGE_SIZE` in Consul. The flow writes four events
per FlowFile:

1. `Generate random content` creates 100 FlowFiles per second, each with 10 KB of random text.
2. `Copy content to attribute` copies the content into the `random.content` attribute.
3. `Modify attribute` sets one more attribute and drops the FlowFile.

The attribute is random, so compression shrinks an event file only from 100 MB to about 41 MB. Nothing queues, so the
FlowFile and content repositories stay small.

`scenario.sh` runs the flow until `No space left on device` appears in `nifi-app.log`, then for `AFTER_FULL_SECONDS`
more, and stops it. With the defaults the first error appears about 70 seconds after the start.

| Variable                      | Default  | Description                                                                    |
|-------------------------------|----------|--------------------------------------------------------------------------------|
| `PROVENANCE_REPO_SIZE`        | `256m`   | Size of the tmpfs that holds the provenance repository                         |
| `PROVENANCE_MAX_STORAGE_SIZE` | `512 MB` | Value of `nifi.provenance.repository.max.storage.size`                         |
| `AFTER_FULL_SECONDS`          | `60`     | Seconds the flow keeps running after the first `No space left on device`       |

The defaults reproduce a limit larger than the volume: the size limit is twice the volume. To reproduce a limit that
leaves too little room, leave 16 MiB above it:

```bash
PROVENANCE_MAX_STORAGE_SIZE="240 MB" ./run.sh provenance-repo-full
```

In that run the volume fills at the fourth rollover. Writes fail in two bursts over 20 seconds, until cleanup deletes
the oldest event file, and later compressions fail again. The repository ends below the limit, at about 210 MiB.

With 200 MiB of room, `PROVENANCE_MAX_STORAGE_SIZE="56 MB"`, the flow runs for 600 seconds without an error, and
`run.sh` stops at its deadline. The repository still reaches 234 MiB, 178 MiB above the limit, before each cleanup run.

## Expected evidence

| File                           | What to look for                                                                                       |
|--------------------------------|--------------------------------------------------------------------------------------------------------|
| `scenario-provenance-repo.txt` | `df` at 100%; `max.storage.size=512 MB` above the file system size; counts of `Failed to write events to the Event Store` and `Failed to compress ... on rollover`; `no_space_left` above 0 |
| `system-diagnostics.json`      | `provenanceRepositoryStorageUsage` with `utilization` at `100.0%`                                      |
| `bulletins.json`               | `Failed to write Provenance Events to the repository. See logs for more details.` in category `Provenance Repository` |
| `root-status.json`             | As many FlowFiles into `Copy content to attribute` and `Modify attribute` as out of `Generate random content`: the flow keeps running |
| `nifi-app.filtered.log`        | `Failed to write events to the Event Store` and `Failed to compress ... on rollover`                   |
| `nifi-app.log.gz`              | The same errors with their stack traces and `java.io.IOException: No space left on device`             |
| `storage.txt`                  | The content repository far from full                                                                   |
| `manifest.txt`                 | `nifi_state=running`, `nifi_health=healthy`                                                            |

After the run with too little room, `df` and `system-diagnostics.json` show about 82%, and the `.gz` files of failed
compressions are smaller than the others in the `du` listing.

## NiFi 1.x

NiFi 1.x logs the same errors and the same bulletin. With the default `PROVENANCE_MAX_STORAGE_SIZE=512 MB` it
behaves like the run with too little room: after each burst of failed writes the writer rolls over with `WRITER_IS_DIRTY`, the
compression of the rolled-over file fails, and NiFi deletes the uncompressed file. The usage reaches 100% only for
moments: samples taken every 5 seconds swung between 62% and 86%, and `df` and `system-diagnostics.json` showed 87%
at the end of the run. The run counted 150 `No space left on device` lines, where the NiFi 2.10.0 runs counted 3323 to
124259. Measured on qubership-nifi 1.0.9 (Apache NiFi 1.28.1).

## Remediation

- Set `nifi.provenance.repository.max.storage.size` below the volume size, with room for the events written in one
  `nifi.provenance.repository.maintenance.frequency` interval, the active event file, and its compressed copy. That is
  typically 100-200 MiB with the default 100 MB `rollover.size`; measure the growth between cleanup runs for a flow
  with a high event rate.
- Lower `max.storage.size` or `max.storage.time` when the volume cannot grow, or shorten `maintenance.frequency`.
- Keep large data in the content of FlowFiles, not in their attributes.
- Remove processors that change FlowFiles without need: each one adds events.

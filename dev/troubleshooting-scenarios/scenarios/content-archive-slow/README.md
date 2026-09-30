# Archive cleanup is too slow

The content repository is on an NFS volume and holds many archived claim files, so each archive cleanup run takes a
long time. The run reads the attributes of every archived file and deletes the expired ones, one network call at a
time.

```bash
NFS_ADDR=<nfs server> NFS_EXPORT=<exported path> ./run.sh content-archive-slow
STORAGE=local ./run.sh content-archive-slow
```

The second command runs the same load on a local Docker volume, as a baseline to compare the NFS run with.

## Symptom

- Archive cleanup runs take much longer than on local storage. With DEBUG level for
  `org.apache.nifi.controller.repository.FileSystemRepository`, `nifi-app.log` reports the phases of each run. The
  `delete expired` phase walks all archive directories and takes most of the time:

  ```text
  Oldest Archive Date for Container default is <date>; delete expired = <n> ms, sort remaining = <n> ms, delete oldest = <n> ms, cleanup = <n> ms
  ```

- The archive grows beyond `nifi.content.repository.archive.max.retention.period`, because cleanup cannot keep up.
- When usage reaches the archive threshold, writes wait for the slow cleanup, as in
  [content-archive-full](../content-archive-full/).

## Cause

The content repository splits claims into 1024 partition directories, each with an `archive` subdirectory. Archive
cleanup walks these directories with `Files.walkFileTree`: it reads the attributes of every archived file, and deletes
each file older than the retention period. Each claim is a separate file once it is larger than
`nifi.content.claim.max.appendable.size` (100 KB by default), so a flow that writes many such FlowFiles leaves many
archived files. On NFS each attribute read and each deletion is a synchronous call to the server, so a run takes far
longer than on a local disk. Listing the same files with `find` stays fast, because it does not read their
attributes or delete them. Older Apache NiFi versions are affected more.

## How the scenario reproduces it

The flow writes 100 FlowFiles of 110 KB per second, each into a claim file of its own, and drops them, so every claim
goes to the archive. `nifi.content.repository.archive.max.retention.period` is set to `RETENTION`, so each cleanup run
has expired claims to delete. The load runs for `LOAD_DURATION` seconds; `scenario.sh` then stops the generator.
With the defaults, the archive holds 7000 to 15000 files (up to about 1.6 GB) at a time.

| Variable        | Default        | Description                                                                 |
|-----------------|----------------|-----------------------------------------------------------------------------|
| `STORAGE`       | `nfs`          | `nfs` for an NFS export, `local` for a Docker volume                        |
| `NFS_ADDR`      | none           | NFS server address; required when `STORAGE=nfs`                             |
| `NFS_EXPORT`    | none           | Exported path, for example `/srv/nifi-content`; required when `STORAGE=nfs` |
| `NFS_OPTS`      | `nfsvers=4,rw` | Mount options added after `addr=<NFS_ADDR>`                                 |
| `RETENTION`     | `2 mins`       | Value of `nifi.content.repository.archive.max.retention.period`             |
| `LOAD_DURATION` | `600`          | Seconds the flow writes and drops content                                   |

### Setting up an NFS export

No NFS server is started by the scenario; provide one. The Docker daemon mounts the export, so the host needs NFS
client support, for example the `nfs-common` package on Debian and Ubuntu. For a local server on the same host:

1. Install the server, for example `sudo apt install nfs-kernel-server`.
2. Create the directory, give it to uid 10001, and export it to the Docker networks. NiFi runs as uid 10001, and
   the export maps every client user to it:

   ```bash
   sudo mkdir -p /srv/nifi-content
   sudo chown 10001:0 /srv/nifi-content
   echo '/srv/nifi-content 127.0.0.1(rw,sync,all_squash,anonuid=10001,anongid=0) 172.16.0.0/12(rw,sync,all_squash,anonuid=10001,anongid=0)' |
       sudo tee -a /etc/exports
   sudo exportfs -ra
   ```

3. Run the scenario with `NFS_ADDR=127.0.0.1 NFS_EXPORT=/srv/nifi-content`.

A server on the same host has almost no network latency, so it shows less of the slowdown than a remote server. Use
an export on another host, or add delay on the host interface with `tc qdisc ... netem delay`, to get closer to a
production NFS volume.

Before NiFi starts, `scenario.sh` checks that uid 10001 can write to the export and stops with an error if it cannot.

`cleanup.sh` removes the Docker volume, but not the files on the export. Empty the exported directory before the next
run, for example with `sudo find /srv/nifi-content -mindepth 1 -delete` on the NFS server; otherwise NiFi starts with
the claims of the previous run.

## Expected evidence

| File                           | What to look for                                                                                                                                |
|--------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `manifest.txt`                 | `content_repository_mount` with type `nfs` or `nfs4`, and the `param.STORAGE` value                                                             |
| `scenario-cleanup-summary.txt` | `cleanup_*_ms` and `delete_expired_*_ms` compared with a `STORAGE=local` run; `list_all_files_ms`                                               |
| `scenario-cleanup-timings.log` | The phase durations of each cleanup run                                                                                                         |
| `storage.txt`                  | `claim_files_archived` in the thousands                                                                                                         |
| `scenario-cleanup-thread.txt`  | Stack of the `Cleanup Archive for default` thread during a run: `Files.walkFileTree` in `destroyExpiredArchives`, on NFS in `lstat` or `unlink` |

Measured with the default parameters on NiFi 2.10.0. The NFS server ran on the same host with a `sync` export.

| Storage    | Archived files | Cleanup run, average | Cleanup run, max | `find` over all files |
|------------|----------------|----------------------|------------------|-----------------------|
| Local NVMe | 11000 to 15000 | 401 ms               | 530 ms           | 141 ms                |
| NFS        | 6400 to 9500   | 14756 ms             | 20891 ms         | 488 ms                |

Nearly all of each run is in the `delete expired` phase. The NFS run held fewer archived files at a time than the local
run, and its runs were still 37 times longer on average.

## Remediation

- Put the content repository on local or block storage, not NFS.
- Keep fewer archived files: shorten `nifi.content.repository.archive.max.retention.period`, lower
  `nifi.content.repository.archive.max.usage.percentage` with `NIFI_CONTENT_ARCHIVE_MAX_USAGE`, or turn the archive
  off with `NIFI_CONTENT_ARCHIVE_DISABLED`.
- Upgrade to a newer Apache NiFi version.

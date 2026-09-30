# Changed NIFI_NEW_SENSITIVE_KEY

NiFi starts with a `NIFI_NEW_SENSITIVE_KEY` other than the key of its earlier starts. The start script compares the
hash of the key with the hash it saved on the first start, and exits with code 3 when they differ.

```bash
./run.sh sensitive-key-changed
```

## Symptom

- The container exits with code 3 about 10 seconds after the start, before NiFi writes `nifi-app.log`.
- The container output has:

  ```text
  oldKeyHash does not match newKeyHash. Probably NIFI_NEW_SENSITIVE_KEY is different from previously used key. Check NIFI_NEW_SENSITIVE_KEY for correctness. Terminating start-up...
  ```

## Cause

NiFi encrypts the sensitive properties in `flow.json.gz` with this key. The start script saves the SHA-256 hash of the
key in `persistent_conf/old_key_hash` and refuses to start with a different key while `flow.json.gz` exists. See
[Invalid NIFI_NEW_SENSITIVE_KEY](../../../../docs/troubleshooting-guide.md#3-invalid-nifi_new_sensitive_key) in the
troubleshooting guide.

## How the scenario reproduces it

NiFi starts once with the key `run.sh` generates, which saves `flow.json.gz` and `old_key_hash` in
`.work/temp-vol/nifi/per-conf`. `scenario.sh` then creates the NiFi container again with a new random key. The
scenario has no parameters.

## Expected evidence

| File                         | What to look for                                                                         |
|------------------------------|------------------------------------------------------------------------------------------|
| `manifest.txt`               | `nifi_exit_code=3`                                                                       |
| `container.log`              | `oldKeyHash does not match newKeyHash`                                                   |
| `nifi-app.log.gz`            | A single line saying that `nifi-app.log` does not exist: NiFi itself never started       |
| `scenario-sensitive-key.txt` | `old_key_hash` equal to the hash of the first key and different from the hash of the second |

The bundle has the hashes of the keys, never the keys.

## Remediation

Set `NIFI_NEW_SENSITIVE_KEY`, or the file at `NIFI_SENSITIVE_KEY_PATH` when the variable is empty, back to the key of
the first deployment, then restart NiFi.

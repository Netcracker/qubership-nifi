---
name: nifi-upgrade-skill
description: Upgrades Apache NiFi to a target version. Updates scripts, configs, Dockerfile, pom.xml, and checks migration guidance.
---

# NiFi Upgrade Skill

## 1. Get versions
- Ask the user for **target NiFi version** and **SHA256 hash** if not provided. Don't proceed without both.
- Get current version from Dockerfile:
```bash
  grep -oP 'NIFI_VERSION=\K[0-9.]+' ./Dockerfile
```

## 2. Fetch reference files
```bash
bash .claude/skills/nifi-upgrade-skill/scripts/getFilesFromDocker.sh <CURRENT> <TARGET>
```

## 3. Sync scripts
For each file in `nifi-scripts/` (`secure.sh`, `start.sh`, `common.sh`, `update_cluster_state_management.sh`):
- Diff `upgrade-temp-data/nifi-files-to-compare/scripts/<CURRENT>/<FILE>` vs `<TARGET>/<FILE>`
- Apply differences directly. If file missing in target, keep current and note it.
- Summarize changes at the end (files touched, lines +/-).

## 4. Sync configs
Same diff-and-apply process for `bootstrap.conf` in `nifi-config/` and for `logback.xml` in `qubership-nifi-consul-templates`.

## 5. Sync nifi.properties
Diff `nifi.properties` between versions. Apply relevant changes to these files in `qubership-nifi-consul-templates`:
- `nifi_default.properties`
- `nifi_internal.properties`
- `nifi_internal_comments.properties`

## 6. Update Dockerfile
Replace `NIFI_VERSION` and `NIFI_VERSION_SHA256` with target values.

## 7. Update pom.xml versions
1. Set `<nifi.version>` to target.
2. Create `upgrade-temp-data/nifi-helper-pom.xml` with parent `nifi-redis-bundle:<TARGET>`.
3. Use `mvn help:evaluate -f upgrade-temp-data/nifi-helper-pom.xml -Dexpression=<PROP> -q -DforceStdout` to extract and update in `./pom.xml`:
   - `nifi-api.version` becomes `<nifi.api.version>`
   - `jedis.version` becomes `<jedis.version>`
   - `spring.data.redis.version` becomes `<spring.data.redis.version>`

## 8. Migration guidance
Fetch <https://cwiki.apache.org/confluence/display/NIFI/Migration+Guidance> **directly with WebFetch** (no subagents, they return false negatives).
- Get verbatim sections for every version between current (exclusive) and target (inclusive).
- Re-fetch by name if any version section is missing. Don't conclude "no guidance" without a targeted re-fetch.
- `Grep` the whole repository (incl. `qubership-consul/**`, `nifi-config/**`, `nifi-scripts/**`) for removed or renamed properties and processor/service names. Apply changes where matched.
- Final summary must classify every guidance item as: **applied** (with paths), **not applicable** (with grep evidence), or **user action required**.

## 9. Build
```bash
mvn clean install -DskipTests -q
mvn clean install -q
```

## 10. Export & compare APIs
Export both versions:
```bash
mvn exec:java -q -f <PROJECT_ROOT>/pom.xml \
  -pl qubership-nifi-tools/qubership-nifi-api-export-tool \
  -DROOT_LOG_LEVEL=ERROR \
  -Dexec.args="--version <VERSION> --output-dir ./upgrade-temp-data/nifi-property-exports/<VERSION>"
```
Compare:
```bash
mvn exec:java -q -f <PROJECT_ROOT>/pom.xml \
  -pl qubership-nifi-tools/qubership-nifi-component-comparator-tool \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=ERROR \
  -Dexec.args="--sourceDir ./upgrade-temp-data/nifi-property-exports/<CURRENT> \
               --targetDir ./upgrade-temp-data/nifi-property-exports/<TARGET> \
               --outputPath ./upgrade-temp-data/nifi-property-comparison"
```

## 11. Resolve display-name renames
Inspect `NiFiComponentsDelta.csv`. If "added/deleted" pairs are actually just `Display Name` renames, build `dictionary.yaml`:
```yaml
displayNameMapping:
  - ComponentName:
      Old_Display_Name: New_Display_Name
```
Then re-run the comparator with `--dictionaryPath ./upgrade-temp-data/nifi-property-comparison/dictionary.yaml`.

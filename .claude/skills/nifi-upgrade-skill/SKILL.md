---
name: nifi-upgrade-skill
description: Upgrades Apache NiFi to a target version. Updates scripts, configs, Dockerfile, pom.xml, and checks migration guidance.
---

# NiFi Upgrade Skill

## 1. Get versions
- Ask the user for **target NiFi version** and **SHA256 hash** if not provided. Don't proceed without both.
- Get current version from Dockerfile:
```bash
sed -n "s/.*NIFI_VERSION='\([0-9.]*\)'.*/\1/p" ./Dockerfile | head -1
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

## 4. Sync bootstrap.conf
Same diff-and-apply process for `bootstrap.conf` in `nifi-config/`.

## 5. Sync logback-template.xml
Diff `logback.xml` between versions. Apply changes to `qubership-nifi-consul-templates/src/main/resources/logback-template.xml`:
- **New loggers** that suppress extra log noise (level WARN/ERROR/OFF): add them to our template.
- All other changes (level tweaks on loggers not present in our template): note in the final report only, do not apply.

## 6. Sync nifi.properties
Diff `nifi.properties` between versions. Apply relevant changes to these files in `qubership-nifi-consul-templates`:
- `nifi_default.properties`
- `nifi_internal.properties`
- `nifi_internal_comments.properties`

## 7. Update Dockerfile
Replace `NIFI_VERSION` and `NIFI_VERSION_SHA256` with target values.

## 8. Update pom.xml versions
1. Set `<nifi.version>` to target.
2. Create `upgrade-temp-data/nifi-helper-pom.xml` with parent `nifi-redis-bundle:<TARGET>`.
3. Use `mvn help:evaluate -f upgrade-temp-data/nifi-helper-pom.xml -Dexpression=<PROP> -q -DforceStdout` to extract and update in `./pom.xml`:
   - `nifi-api.version` becomes `<nifi-api.version>`
   - `jedis.version` becomes `<jedis.version>`
   - `spring.data.redis.version` becomes `<spring.data.redis.version>`

## 9. Migration guidance
Fetch <https://cwiki.apache.org/confluence/display/NIFI/Migration+Guidance> **directly with WebFetch** (no subagents, they return false negatives).
- Get verbatim sections for every version between current (exclusive) and target (inclusive).
- Re-fetch by name if any version section is missing. Don't conclude "no guidance" without a targeted re-fetch.
- `Grep` the whole repository for removed or renamed properties and processor/service names. Apply changes where matched.
```bash
grep -rn '<term>' \
  qubership-bundle/ qubership-nifi-db-bundle/ qubership-nifi-bulk-redis-service/ \
  qubership-nifi-consul-templates/ nifi-config/ nifi-scripts/ \
  qubership-nifi-common/ qubership-nifi-bundle-common/ \
  qubership-consul/ qubership-nifi-lookup-services/ \
  qubership-nifi-quarkus-consul/ qubership-services/ \
  --include='*.xml' --include='*.json' --include='*.yaml' --include='*.yml' \
  --include='*.properties' --include='*.sh' --include='*.java' 2>/dev/null | grep -v '/target/'
```
- Final summary must classify every guidance item as: **applied** (with paths), **not applicable** (with grep evidence), or **user action required**.

## 10. Build and test
```bash
mvn clean install -DskipUnitTests=true -Dgpg.skip=true -q
mvn clean install 2>&1 | grep -E "BUILD|ERROR|FAIL|Tests run" | tail -20
```

## 11. Export & compare nifi component properties
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

## 12. Resolve display-name renames
Inspect `./upgrade-temp-data/nifi-property-comparison/NiFiComponentsDelta.csv`.
If "added/deleted" pairs are actually just `Display Name` renames, build `dictionary.yaml`:
```yaml
displayNameMapping:
  - ComponentName:
      Old_Display_Name: New_Display_Name
```
Put `dictionary.yaml` into `qubership-nifi-tools/qubership-nifi-component-comparator-tool/dictionaries/<TARGET_VER_WITH_UNDERSCORE>`,
where `<TARGET_VER_WITH_UNDERSCORE>` is `<TARGET>` version, where `.` is replaced with `_`.
Then re-run the comparator with `--dictionaryPath qubership-nifi-tools/qubership-nifi-component-comparator-tool/dictionaries/<TARGET_VER_WITH_UNDERSCORE>/dictionary.yaml`.

## 13. Update openapi specification
```bash
mvn exec:java \
  -pl qubership-nifi-tools/qubership-nifi-openapi-enricher
```

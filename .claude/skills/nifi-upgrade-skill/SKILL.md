---
name: nifi-upgrade-skill
description: Upgrades Apache NiFi to a target version. Updates scripts, configs, Dockerfile, pom.xml, and checks migration guidance.
---

# NiFi Upgrade Skill

Run every `mvn` command in this skill with `--batch-mode --no-transfer-progress -Dgpg.skip=true`. The build signs
artifacts with GPG in the verify phase, so `mvn install` fails on a machine without a GPG key unless
`-Dgpg.skip=true` is set.

## 1. Get versions

- Take the **target NiFi version** and its **SHA256 hash** from the `NIFI_VERSION` and `NIFI_DIGEST` environment
  variables. The `check` job of `.github/workflows/nifi-version-update.yml` sets them, and the hash has the
  `sha256:<hex>` form that `NIFI_VERSION_SHA256` in the Dockerfile uses.

  ```bash
  echo "$NIFI_VERSION $NIFI_DIGEST"
  ```

- When either variable is empty, as in a local run, ask the user for the missing value. Don't proceed without both.
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
3. Use `mvn help:evaluate -f upgrade-temp-data/nifi-helper-pom.xml -Dexpression=<PROP> -q -DforceStdout`
   to extract and update in `./pom.xml`:
   - `nifi-api.version` becomes `<nifi-api.version>`
   - `jedis.version` becomes `<jedis.version>`
   - `spring.data.redis.version` becomes `<spring.data.redis.version>`

## 9. Migration guidance

Fetch <https://cwiki.apache.org/confluence/display/NIFI/Migration+Guidance>
**directly with WebFetch** (no subagents, they return false negatives).

- Get verbatim sections for every version between current (exclusive) and target (inclusive).
- Re-fetch by name if any version section is missing. Don't conclude "no guidance" without a targeted re-fetch.
- `Grep` the whole repository for removed or renamed properties and processor/service names. Apply changes where matched.

```bash
grep -rn '<term>' \
  qubership-bundle/ qubership-nifi-db-bundle/ qubership-nifi-bulk-redis-service/ \
  qubership-nifi-consul-templates/ nifi-config/ nifi-scripts/ \
  qubership-nifi-common/ qubership-nifi-bundle-common/ \
  qubership-consul/ qubership-nifi-lookup-services/ qubership-nifi-http-bundle/ \
  qubership-nifi-quarkus-consul/ qubership-services/ \
  --include='*.xml' --include='*.json' --include='*.yaml' --include='*.yml' \
  --include='*.properties' --include='*.sh' --include='*.java' 2>/dev/null | grep -v '/target/'
```

- Final summary must classify every guidance item as: **applied** (with paths),
  **not applicable** (with grep evidence), or **user action required**.

## 10. Build and test

```bash
mvn clean install -DskipUnitTests=true -Dgpg.skip=true -q
mvn clean install -Dgpg.skip=true 2>&1 | grep -E "BUILD|ERROR|FAIL|Tests run" | tail -20
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

Then put every property marked as `deleted` that is not part of a rename into one of two sections:

- `propertiesToRemoveWhenEmpty` - the property is sensitive. Apache NiFi migrates a sensitive property only when it
  has a value. An empty one is absent from the export, but its descriptor remains, and the orphaned sensitive
  descriptor breaks import, so the descriptor is removed only when the value is empty.
- `propertiesAllowedToDelete` - every other deleted property, removed unconditionally.

Decide by the `sensitive` field of the property in the `<CURRENT>` export:
`upgrade-temp-data/nifi-property-exports/<CURRENT>/<Component Type>/<Component Name>.json`, under
`propertyDescriptors."<Old Api Name>"`, with `Component Type`, `Component Name`, and `Old Api Name` taken from the CSV
row. `true` means `propertiesToRemoveWhenEmpty`; `false` or a missing field means `propertiesAllowedToDelete`.

```bash
jq '.propertyDescriptors["<Old Api Name>"].sensitive' \
  "upgrade-temp-data/nifi-property-exports/<CURRENT>/<Component Type>/<Component Name>.json"
```

A property must be listed in exactly one of the two sections, never both. Don't ask the user to sort them. In the
final report, list the properties of each section by component, so the reviewer can check the choice.

```yaml
propertiesAllowedToDelete:
  - ComponentName:
      - Old_Display_Name 1
      - Old_Display_Name 2

propertiesToRemoveWhenEmpty:
  - ComponentName:
      - Old_Display_Name 3
```

Put `dictionary.yaml` into `qubership-nifi-tools/qubership-nifi-component-comparator-tool/dictionaries/<TARGET_VER_WITH_UNDERSCORE>`,
where `<TARGET_VER_WITH_UNDERSCORE>` is `<TARGET>` version, where `.` is replaced with `_`.
Then re-run the comparator with `--dictionaryPath qubership-nifi-tools/qubership-nifi-component-comparator-tool/dictionaries/<TARGET_VER_WITH_UNDERSCORE>/dictionary.yaml \
--version <TARGET>`.

## 13. Update OpenAPI specification

```bash
mvn exec:java \
  -pl qubership-nifi-tools/qubership-nifi-openapi-enricher
```

## 14. Generate releases notes

Fill in the wiki templates from `docs/template/wiki/` with the versions used in
this upgrade and write the results to `upgrade-temp-data/releases-notes/`.

Versions to substitute:

- `<qubership-nifi.version>` - the **planned qubership-nifi release version** (e.g. `2.9.0`), taken from the
  `QUBERSHIP_NIFI_VERSION` environment variable, which `.github/workflows/sh/nifi-upgrade-agent.sh` sets. When the
  variable is empty, as in a local run, ask the user for it. Don't proceed without it.
- `<nifi.version>` - the target Apache NiFi version (`<TARGET>` from step 1).
- `<previous-nifi.version>` - the current Apache NiFi version (`<CURRENT>` from step 1).
- `<nifi-api.version>` - the `nifi-api.version` property set in `./pom.xml` in step 8.

Steps:

1. Copy `docs/template/wiki/qubership-nifi-Upgrade-Notes-template.md` to
   `upgrade-temp-data/releases-notes/qubership-nifi-<qubership-nifi.version>-Upgrade-Notes.md`.
2. Copy `upgrade-temp-data/nifi-property-comparison/NiFiComponentsDelta.md` (produced in
   steps 11-12) to
   `upgrade-temp-data/releases-notes/Apache-NiFi-<nifi.version>-Component-Properties-Delta.md`.
3. In both files, replace `<qubership-nifi.version>`, `<previous-nifi.version>`,
   `<nifi-api.version>` and `<nifi.version>` with their values.
4. Verify no placeholder is left: `grep -rn '<[a-z-]*\.version>' upgrade-temp-data/releases-notes/`
   must return nothing.

In the final report, list the two generated files and remind the user to publish
them to the [project wiki](https://github.com/Netcracker/qubership-nifi/wiki) - they
are not committed to the repo.
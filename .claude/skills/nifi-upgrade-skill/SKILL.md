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
               --outputPath ./upgrade-temp-data/nifi-property-comparison \
               --version <TARGET>"
```

`--version` adds the `_2_<MINOR>` suffix to the names of the mapping files that step 13 copies into the dev scripts.

## 12. Resolve display-name renames

Inspect `./upgrade-temp-data/nifi-property-comparison/NiFiComponentsDelta.csv`.
If "added/deleted" pairs are actually just `Display Name` renames, build `dictionary.yaml`:

```yaml
displayNameMapping:
  - ComponentName:
      Old_Display_Name: New_Display_Name
```

Additionally, ask the user to review all properties marked as `deleted` before continuing. For each
deleted property, ask the user whether it can be removed unconditionally, or whether it is a sensitive
property with complex migration logic that Apache NiFi only handles when the property has a value (if
left empty, its value is absent from the export but its descriptor remains, and the orphaned sensitive
descriptor breaks import).

- Properties that can be removed unconditionally go under `propertiesAllowedToDelete`.
- Sensitive properties that must be removed only when their value is empty go under
  `propertiesToRemoveWhenEmpty`.

A property must be listed in exactly one of the two sections, never both.

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

## 13. Update dev scripts

The scripts in `dev/update-scripts-flow-2.x` and `dev/update-scripts-prop-2.x` rename and remove properties in
exports made on older NiFi versions. Each upgrade adds one mapping step for the target version, built from the
comparator results of steps 11-12.

`<TARGET_MINOR>` is the minor part of `<TARGET>` (`12` for `2.12.0`). Create files and steps only for `<TARGET>`,
never for the versions skipped between `<CURRENT>` and `<TARGET>`: an upgrade from 2.10.0 to 2.12.0 adds step `12`
and nothing for 2.11. The scripts apply a step to every export older than the step, so the one step covers the whole
range. Leave the files and steps that already exist for other versions unchanged, even for a version between
`<CURRENT>` and `<TARGET>`.

### Patch upgrade

The scripts choose steps by the minor version alone, so all patch versions of a minor version share one set of
`_2_<MINOR>` files, with the mappings up to the latest patch version upgraded to. A target of an earlier patch version
gets those mappings too. If `<CURRENT>` and `<TARGET>` have the same major and minor parts, as in 2.7.0 to 2.7.2:

- If all four `*_2_<TARGET_MINOR>.json` files in `./upgrade-temp-data/nifi-property-comparison` hold only `{}`, the
  patch version renamed and removed nothing: change nothing in `dev/` and say so in the final report.
- Otherwise, follow the rest of this step, with two differences:
  - Where a `_2_<TARGET_MINOR>` file already exists in `dev/`, add the new entries to it under their components and
    keep its existing entries, instead of replacing the file. A step renames each property once, so chain the
    entries where an existing entry renames `A` to `B` and a new entry changes `B`, and keep the new entry as well:
    - a new `B` to `C` rename in the same file: change the existing entry to rename `A` to `C`;
    - a new `null` for `B` in the same file: change the existing entry to `null` for `A`. In
      `upgradeConfig_2_<TARGET_MINOR>.json` this covers the approved `csRemoveWhenEmptyConfig` entries too, which
      that file holds as `null`;
    - a new `B` in a `RemoveWhenEmptyConfig` file: leave the flow script entry as it is, because the flow script
      renames before it removes empty descriptors.

    List every entry changed this way in the final report.
  - Do not add the `steps` entry, the script blocks, or the README rows again where they already exist for
    `<TARGET_MINOR>`.

  In the final report, say that the new entries do not reach exports made on an earlier patch version of the same minor
  version: an export made on 2.7.0 is not older than step `7`, so the scripts skip that step for it.

### Flow exports script

A flow script entry that removes a property runs before the import, so NiFi's own migration never sees that
property. A property that NiFi converts into a new property on import must stay in the flow, or its value is lost.

1. Show the user, in one message, the list of every entry that removes a property, grouped by component, and ask
   once which of them the flow script must leave for NiFi to migrate. Do not ask a separate question for each entry.
   The list covers:
   - every `null` value in `csPropConfig_2_<TARGET_MINOR>.json` and `procPropConfig_2_<TARGET_MINOR>.json`, which
     removes the property even when it carries a value;
   - every entry in `csRemoveWhenEmptyConfig_2_<TARGET_MINOR>.json` and
     `procRemoveWhenEmptyConfig_2_<TARGET_MINOR>.json`, which removes the descriptor when the property has no value.

   Entries that rename a property need no question.
2. Copy all four files from `./upgrade-temp-data/nifi-property-comparison` to `dev/update-scripts-flow-2.x`, without
   the entries the user declined. Copy a file even when it holds only `{}`, and end every file with a newline:
   - `csPropConfig_2_<TARGET_MINOR>.json`
   - `procPropConfig_2_<TARGET_MINOR>.json`
   - `csRemoveWhenEmptyConfig_2_<TARGET_MINOR>.json`
   - `procRemoveWhenEmptyConfig_2_<TARGET_MINOR>.json`

   `updateFlowExports.sh` fails on a step whose `csPropConfig` or `procPropConfig` file is missing.
3. Append `<TARGET_MINOR>` to `local steps=(...)` in `update_properties()` of `updateFlowExports.sh`.
4. Add a `<TARGET>` row to both mapping tables in `dev/update-scripts-flow-2.x/README.md`.

### Controller services script

`upgradeConfig_2_<TARGET_MINOR>.json` holds controller service and reporting task mappings only, and the file exists
only when the target version renamed or removed a controller service or reporting task property.

1. Merge `csPropConfig_2_<TARGET_MINOR>.json` with the `csRemoveWhenEmptyConfig_2_<TARGET_MINOR>.json` entries the
   user approves. Take both files from `./upgrade-temp-data/nifi-property-comparison`, with the entries declined for
   the flow script still in them: the controller service import decides its removals separately. Show the user, in
   one message, the list of every `csRemoveWhenEmptyConfig` entry, grouped by component, and ask once which of them
   the controller service import must remove.
   In this script a `null` value removes the property even when it carries a value.
2. If the merged mapping is `{}`, skip the rest of this section, README included, and say so in the final report.
   The README version range ends at the latest version that has an `upgradeConfig` file.
3. Write the merged mapping to `dev/update-scripts-prop-2.x/upgradeConfig_2_<TARGET_MINOR>.json`, ending the file
   with a newline.
4. In `analyzeAndUpdateNiFiExports.sh`, copy the blocks of the latest existing version and change them to
   `<TARGET_MINOR>`:
   - the `declare -a listForUpdate_2_<MINOR>` line;
   - the export version check that fills `listForUpdate_2_<MINOR>`;
   - the `echo "Flow for update 2.<MINOR>: "` line;
   - the block that runs `upgradeExports_2_x.sh` with `upgradeConfig_2_<MINOR>.json`.
5. In `dev/update-scripts-prop-2.x/README.md`, update the supported version range in the opening sentence and the
   list of mapping files under "Mapping configuration".

### Check the scripts

```bash
bash -n dev/update-scripts-flow-2.x/updateFlowExports.sh
bash -n dev/update-scripts-prop-2.x/analyzeAndUpdateNiFiExports.sh
shellcheck dev/update-scripts-flow-2.x/updateFlowExports.sh dev/update-scripts-prop-2.x/analyzeAndUpdateNiFiExports.sh
```

In the final report, list the files added and changed in both directories, and the removal entries the user
approved or declined, separately for the flow script and for `upgradeConfig_2_<TARGET_MINOR>.json`.

## 14. Update OpenAPI specification

```bash
mvn exec:java \
  -pl qubership-nifi-tools/qubership-nifi-openapi-enricher
```

## 15. Generate releases notes

Fill in the wiki templates from `docs/template/wiki/` with the versions used in
this upgrade and write the results to `upgrade-temp-data/releases-notes/`.

Versions to substitute:

- `<qubership-nifi.version>` - ask the user for the **planned qubership-nifi
  release version** (e.g. `2.9.0`). Don't proceed without it.
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
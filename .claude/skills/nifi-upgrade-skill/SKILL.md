---
name: nifi-upgrade-skill
description: Use this skill when the user wants to upgrade Apache NiFi to a newer version. Handles the full upgrade pipeline: updating scripts, configs, Dockerfile, pom.xml, checking migration guidance.
---

# Nifi Upgrade Skill

## Step 1: Determine the target NiFi version

Extract the target NiFi version and its corresponding SHA256 hash from the user's message.
If the user has not specified a version, ask them which version they need before proceeding.
Do not proceed to the next step until you have received the target nifi version and SHA256 hash for that version.

## Step 2: Get the current NiFi version from Dockerfile

Find the Dockerfile in the user's project directory and extract
the current NiFi version from it:

```bash
grep -oP 'NIFI_VERSION=\K[0-9.]+' ./Dockerfile
```

## Step 3: Run the script to receive files

Pass both versions to the script — current first, target second and run scripts without asking the user for confirmation:

```bash
bash .claude/skills/nifi-upgrade-skill/scripts/getFilesFromDocker.sh
```

## Step 4: Compare and apply script changes

Compare the scripts between the current and target NiFi versions
and apply the differences to the project's working copies without asking the user for confirmation.

Source scripts (from Docker image or release archive):
- secure.sh
- start.sh
- common.sh
- update_cluster_state_management.sh

For each file:

1. Generate a diff between the current and target version:
```bash
diff .claude/skills/nifi-upgrade-skill/scripts/current/<FILE> \
     .claude/skills/nifi-upgrade-skill/scripts/target/<FILE>
```

2. If there are differences — apply them directly to the
   corresponding file in the project's nifi-scripts/ directory.

3. After all files are processed, show a brief summary of what
   was changed (which files, how many lines added/removed).

If a file doesn't exist in the target version, keep the current
version and note it in the summary.

## Step 5: Compare and apply config changes

Compare the configs between the current and target NiFi versions and apply the differences to the project's working copies without asking the user for confirmation.

Source configs (from Docker image or release archive):
- `logback.xml`
- `bootstrap.conf`

For each file:

1. Generate a diff between the current and target version:
```bash
   diff .claude/skills/nifi-upgrade-skill/config/current/<FILE> \
        .claude/skills/nifi-upgrade-skill/config/target/<FILE>
```

2. If there are differences, apply them directly to the corresponding file in the project's `nifi-config/` directory.

3. Changes for `logback.xml` must also be applied to the resource file in the `qubership-nifi-consul-templates` module.

4. After all files are processed, show a brief summary of what was changed (which files, how many lines added/removed).

If a file doesn't exist in the target version, keep the current version and note it in the summary.

## Step 6: Compare and apply changes in nifi.properties

Compare the `nifi.properties` file located at `/config/nifi.properties` between the current and target NiFi versions.

1. Generate a diff between the current and target version:
```bash
   diff .claude/skills/nifi-upgrade-skill/config/current/nifi.properties \
        .claude/skills/nifi-upgrade-skill/config/target/nifi.properties
```
2. If there are differences, apply the relevant changes to the following files in the `qubership-nifi-consul-templates` module:
   - `nifi_default.properties`
   - `nifi_internal.properties`
   - `nifi_internal_comments.properties`

3. After all, show a brief summary of what was changed (which files, how many lines added/removed).

## Step 7: Updating the NiFi version in Dockerfile

Take the target NiFi version and its SHA256 hash, then replace the `NIFI_VERSION` and `NIFI_VERSION_SHA256` values in `./Dockerfile`.

## Step 8: Updating the NiFi version in pom.xml

In the file `./pom.xml`, locate the `<nifi.version>` property inside the `<properties>` section and replace its value with the target NiFi version.

## Step 9: Updating the NiFi API version in pom.xml

On the page https://cwiki.apache.org/confluence/display/NIFI/Release+Notes, find the NiFi API release that was published before the target NiFi version.
Take that NiFi API version and update the `<nifi.api.version>` property in the `<properties>` section of `./pom.xml`.

## Step 10: Updating the Jedis version in pom.xml

1. Create a minimal `pom.xml` file with the following dependency:
```xml
   <dependency>
       <groupId>org.apache.nifi</groupId>
       <artifactId>nifi-redis-service-api</artifactId>
       <version>${nifi.version}</version>
   </dependency>
```
   Set the `<version>` to the target NiFi version.

2. Run the command:
```bash
   mvn dependency:tree
```

3. From the command output, extract the Jedis version.

4. Update the `<jedis.version>` property in the project's `./pom.xml` with the extracted version.

## Step 11: Add the filesCompare and nifi-api-output directory in gitIgnore

Add the `filesCompare` and `nifi-api-output` directory to the `.gitignore` file.

## Step 12: Check Migration Guidance

Fetch https://cwiki.apache.org/confluence/display/NIFI/Migration+Guidance **directly with the WebFetch tool** — do NOT delegate to a subagent (they have returned false "no guidance found" results).

1. Ask WebFetch for the verbatim sections of every version between current (exclusive) and target (inclusive) — e.g. 2.6.0 → 2.7.2 means 2.7.0, 2.7.1, 2.7.2. Request removed features, deprecations, breaking changes, property renames, action items.
2. If a version section is missing from the response, re-fetch with a prompt naming that specific version. Do not conclude "no guidance" without a targeted re-fetch.
3. For each item, `Grep` the whole repo (including `qubership-consul/**`, `nifi-config/**`, `nifi-scripts/**`) for removed/renamed properties or processor/service names, and apply changes where matches exist.
4. In the final summary, list every guidance item as **applied** (with paths), **not applicable** (with grep evidence), or **user action required**. Silent omission is not acceptable.


## Step 13: Compile the project

Compile the project in two stages:

1. First, compile without tests:
```bash
   mvn clean compile -DskipTests
```

2. Then, compile with tests:
```bash
   mvn clean compile
```

## Step 14: Run qubership-nifi-api-export-tool

Run the following commands, replacing `<TARGET_NIFI_VERSION>` with the target NiFi version:

1. Export API for the target NiFi version:
```bash
   mvn exec:java \
     -pl qubership-nifi-tools/qubership-nifi-api-export-tool \
     -Dexec.args="--version <TARGET_NIFI_VERSION> --output-dir ./nifi-api-output/<TARGET_NIFI_VERSION>"
```

2. Export API for the current NiFi version:
```bash
   mvn exec:java \
     -pl qubership-nifi-tools/qubership-nifi-api-export-tool \
     -Dexec.args="--version <CURRENT_NIFI_VERSION> --output-dir ./nifi-api-output/<CURRENT_NIFI_VERSION>"
```

## Step 15: Run qubership-nifi-component-comparator-tool

Run the following command, substituting the actual current and target NiFi versions:
```bash
mvn exec:java \
  -pl qubership-nifi-tools/qubership-nifi-component-comparator-tool \
  -Dexec.args="--sourceDir ./nifi-api-output/${CURRENT_NIFI_VERSION} --targetDir ./nifi-api-output/${TARGET_NIFI_VERSION} --outputPath ./nifi-api-output/comparatorResult"
```

- `${CURRENT_NIFI_VERSION}` — the current NiFi version from the project
- `${TARGET_NIFI_VERSION}` — the target NiFi version specified by the user
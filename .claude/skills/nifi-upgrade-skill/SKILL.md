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

Pass both versions to the script — current first, target second and run scripts:

```bash
bash .claude/skills/nifi-upgrade-skill/scripts/getFilesFromDocker.sh <current_version> <target_version>
```

## Step 4: Compare and apply script changes

Compare the scripts between the current and target NiFi versions
and apply the differences to the project's working copies.

Source scripts (from Docker image):
- secure.sh
- start.sh
- common.sh
- update_cluster_state_management.sh

For each file:

1. Generate a diff between the current and target version:
```bash
diff .upgrade-temp-data/nifi-files-to-compare/scripts/current/<FILE> \
     .upgrade-temp-data/nifi-files-to-compare/scripts/target/<FILE>
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

3. Changes for `logback.xml` must be applied to the resource file in the `qubership-nifi-consul-templates` module.

4. After all files are processed, show a brief summary of what was changed (which files, how many lines added/removed).

If a file doesn't exist in the target version, keep the current version and note it in the summary.

## Step 6: Compare and apply changes in nifi.properties

Compare the `nifi.properties` file from Docker image between the current and target NiFi versions.

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

## Step 8: Updating versions in pom.xml

1. In the file `./pom.xml`, locate the `<nifi.version>` property inside the `<properties>` section and replace its value with the target NiFi version.

2. Create a temporary minimal `pom.xml` file in a separate directory:
```xml
   <project xmlns="http://maven.apache.org/POM/4.0.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
       <modelVersion>4.0.0</modelVersion>
       <parent>
           <groupId>org.apache.nifi</groupId>
           <artifactId>nifi-redis-bundle</artifactId>
           <version>${TARGET_NIFI_VERSION}</version>
       </parent>
       <artifactId>qubership-nifi-tmp-test</artifactId>
       <packaging>pom</packaging>
   </project>
```
   Set the `<version>` to the target NiFi version.

3. Extract the NiFi API version and update it in `./pom.xml`:
```bash
   mvn help:evaluate -Dexpression=nifi-api.version -q -DforceStdout
```
   Take the output and update the `<nifi.api.version>` property in the `<properties>` section of `./pom.xml`.

4. Extract the Jedis version and update it in `./pom.xml`:
```bash
   mvn help:evaluate -Dexpression=jedis.version -q -DforceStdout
```
   Take the output and update the `<jedis.version>` property in the `<properties>` section of `./pom.xml`.

5. Delete the temporary `pom.xml` file created in step 2.

## Step 11: Check Migration Guidance

Fetch <https://cwiki.apache.org/confluence/display/NIFI/Migration+Guidance> **directly with the WebFetch tool** — do NOT delegate to a subagent (they have returned false "no guidance found" results).

1. Ask WebFetch for the verbatim sections of every version between current (exclusive) and target (inclusive) — e.g. 2.6.0 → 2.7.2 means 2.7.0, 2.7.1, 2.7.2. Request removed features, deprecations, breaking changes, property renames, action items.
2. If a version section is missing from the response, re-fetch with a prompt naming that specific version. Do not conclude "no guidance" without a targeted re-fetch.
3. For each item, `Grep` the whole repository (including `qubership-consul/**`, `nifi-config/**`, `nifi-scripts/**`) for removed/renamed properties or processor/service names, and apply changes where matches exist.
4. In the final summary, list every guidance item as **applied** (with paths), **not applicable** (with grep evidence), or **user action required**. Silent omission is not acceptable.


## Step 12: Compile the project

Compile the project in two stages:

1. First, compile without tests:
```bash
   mvn clean install -DskipTests
```

2. Then, compile with tests:
```bash
   mvn clean install
```

## Step 13: Run qubership-nifi-api-export-tool

Run the following commands, replacing `<TARGET_NIFI_VERSION>` with the target NiFi version:

1. Export API for the target NiFi version:
```bash
   mvn exec:java \
     -pl qubership-nifi-tools/qubership-nifi-api-export-tool \
     -Dexec.args="--version <TARGET_NIFI_VERSION> --output-dir ./upgrade-temp-data/nifi-property-exports/<TARGET_NIFI_VERSION>"
```

2. Export API for the current NiFi version:
```bash
   mvn exec:java \
     -pl qubership-nifi-tools/qubership-nifi-api-export-tool \
     -Dexec.args="--version <CURRENT_NIFI_VERSION> --output-dir ./upgrade-temp-data/nifi-property-exports/<CURRENT_NIFI_VERSION>"
```

## Step 14: Run qubership-nifi-component-comparator-tool

Run the following command, substituting the actual current and target NiFi versions:
```bash
mvn exec:java \
  -pl qubership-nifi-tools/qubership-nifi-component-comparator-tool \
  -Dexec.args="--sourceDir ./upgrade-temp-data/nifi-property-exports/${CURRENT_NIFI_VERSION} --targetDir ./upgrade-temp-data/nifi-property-exports/${TARGET_NIFI_VERSION} --outputPath ./upgrade-temp-data/nifi-property-comparison"
```

- `${CURRENT_NIFI_VERSION}` — the current NiFi version from the project
- `${TARGET_NIFI_VERSION}` — the target NiFi version specified by the user

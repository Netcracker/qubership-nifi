# Update script for Apache NiFi 2.x versioned flow exports

## Scripts overview

`updateFlowExports.sh` prepares NiFi versioned flow exports for import into a target NiFi
instance. It supports three independent updates, each selectable with a flag:

| Flag             | Update                                                                                   |
|------------------|------------------------------------------------------------------------------------------|
| `--external-cs`  | Resolve `externalControllerServices` references by name against the target NiFi.         |
| `--versions`     | Bump every component bundle version to the version installed in the target NiFi.         |
| `--properties`   | Rename or remove component properties that NiFi 2.x renamed, using the bundled mappings. |

When no flag is supplied, all three updates run. The script walks each export recursively, so it
updates components at any nesting depth inside `flowContents`.

The script processes only versioned flow exports: files whose top-level JSON has a `flowContents`
attribute. It skips single-component controller service and reporting task exports, which are
handled by `update-scripts-2.0` and `update-scripts-prop-2.x`.

Example of running the script:

```bash
# Run all three updates:
bash updateFlowExports.sh <pathToFlow>

# Run only selected updates:
bash updateFlowExports.sh <pathToFlow> --versions --properties
```

Input arguments used in the script:

| Argument   | Required | Default  | Description                                                 |
|------------|----------|----------|-------------------------------------------------------------|
| pathToFlow | Y        | ./export | Path to the directory where the exported flows are located. |

### External controller services (`--external-cs`)

Apache NiFi resolves an external controller service reference **by ID first, and only falls back to
matching by name** when no ID matches. Starting from Apache NiFi 2.x, the match by name does not
happen when the referencing component property was renamed by automatic property migration, so NiFi
sets the reference incorrectly.

For each export that declares a non-empty `externalControllerServices` map, the script looks up the
same-named controller service in the target NiFi root process group and replaces the ID everywhere
it appears:

- the `externalControllerServices` map key,
- its `identifier` field,
- and every component `properties` value that referenced that ID.

External controller services are looked up only at the root process group level. When a target
controller service with a matching name is not found, the script logs a warning and leaves that
reference unchanged. This update runs only when the target major version is `2`; otherwise it is
skipped.

### Component versions (`--versions`)

The script reads the available component types from the target NiFi
(`/nifi-api/flow/processor-types`, `/nifi-api/flow/controller-service-types`, and
`/nifi-api/flow/reporting-task-types`) and builds a `group/artifact` to version map. It then sets
the version on every bundle whose `group/artifact` the target NiFi provides - the component bundle
and any nested bundle such as `controllerServiceApis` or `identifiesControllerServiceBundle`. For
each bundle the target does not provide, the script logs a warning and leaves the version unchanged.

### Properties (`--properties`)

NiFi 2.x renamed and removed many component properties. The script applies the bundled mapping
configs to rename or delete properties keyed by component type. The same mappings also update each
component's `propertyDescriptors` map: a renamed property's descriptor key and its inner `name`
field are renamed (the `displayName` is left intact), and a removed property's descriptor is
deleted. It detects the oldest `org.apache.nifi` component version in each export and applies the
mappings for every version step between that version and the target NiFi version. This update runs
only when the target version is `2.5` or later; otherwise it is skipped.

For each step the script then applies the remove-when-empty configs. Some sensitive properties have
complicated 2.x migration logic that NiFi handles itself when the property carries a value, so the
rename and delete configs deliberately omit them. When such a property is left unset, its value is
absent from the export but its descriptor remains, and the orphaned sensitive descriptor breaks the
import. The remove-when-empty configs remove that descriptor only when its property value is absent
or null; a property that still carries a value is left for NiFi's own migration to handle.

## Mapping configuration

The script ships with mapping configs for each upgrade step, with controller service and processor
mappings kept in separate files so each is easy to maintain:

| Step  | Controller services      | Processors                 |
|-------|--------------------------|----------------------------|
| 2.5.0 | `csPropConfig_2_5.json`  | `procPropConfig_2_5.json`  |
| 2.6.0 | `csPropConfig_2_6.json`  | `procPropConfig_2_6.json`  |
| 2.7.2 | `csPropConfig_2_7.json`  | `procPropConfig_2_7.json`  |
| 2.9.0 | `csPropConfig_2_9.json`  | `procPropConfig_2_9.json`  |

Each config maps a component type to a map of old-to-new property names. To remove a property, set
its value to `null`.

```json
{
    "org.apache.nifi.processors.jolt.JoltTransformJSON": {
        "jolt-spec": "Jolt Specification",
        "jolt-transform": "Jolt Transform",
        "pretty_print": "Pretty Print"
    },
    "org.apache.nifi.xml.XMLRecordSetWriter": {
        "schema-protocol-version": null
    }
}
```

The remove-when-empty configs follow the same split and naming scheme, keyed by upgrade step:

| Step  | Controller services                 | Processors                            |
|-------|-------------------------------------|---------------------------------------|
| 2.5.0 | `csRemoveWhenEmptyConfig_2_5.json`  | `procRemoveWhenEmptyConfig_2_5.json`  |
| 2.6.0 | `csRemoveWhenEmptyConfig_2_6.json`  | `procRemoveWhenEmptyConfig_2_6.json`  |
| 2.7.2 | `csRemoveWhenEmptyConfig_2_7.json`  | `procRemoveWhenEmptyConfig_2_7.json`  |
| 2.9.0 | `csRemoveWhenEmptyConfig_2_9.json`  | `procRemoveWhenEmptyConfig_2_9.json`  |

Each config maps a component type to a map of property names with `null` values. A `null` entry marks
a descriptor to remove only when the matching property is empty.

```json
{
    "org.apache.nifi.processors.aws.s3.PutS3Object": {
        "Access Key": null,
        "Secret Key": null,
        "proxy-user-password": null
    }
}
```

These configs are generated by the qubership-nifi-component-comparator-tool
(`NiFiTypeMappingRemoveWhenEmpty.json` and `NiFiProcessorTypeMappingRemoveWhenEmpty.json`) and copied
here, mirroring the `csPropConfig` / `procPropConfig` files.

## Environment variables

The table below lists the environment variables used in the script.

| Parameter       | Required | Default                  | Description                                                                                                                                                                                                                                                                                                                                       |
|-----------------|----------|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| NIFI_TARGET_URL | Y        | `https://localhost:8443` | URL for target NiFi.                                                                                                                                                                                                                                                                                                                              |
| DEBUG_MODE      | N        | false                    | If set to 'true', the difference between the updated flow and the exported flow is shown when updating a flow.                                                                                                                                                                                                                                    |
| NIFI_CERT       | N        |                          | TLS certificates that are used to connect to the NiFi target.<br/> Exact set of arguments depends on Linux distribution, refer to `curl` documentation on your system for more details on TLS-related parameters.<br/>For Alpine Linux the set of parameters is:<br/>`--cert 'client.p12:client.password' --cert-type P12 --cacert nifi-cert.pem` |

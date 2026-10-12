# Upgrade Script for Apache NiFi 2.x properties

## Scripts overview

The script `analyzeAndUpdateNiFiExports.sh` upgrades controller services and reporting tasks exports
done from older NiFi versions to be compatible with NiFi 2.5.0 through 2.11.0.
The script automatically checks version in target NiFi instance, as well as version in export
and determines, what changes must be applied to export to make it compatible.

Example of running a script:

`bash analyzeAndUpdateNiFiExports.sh <pathToFlow>`

Input arguments used in the script:

| Argument               | Required | Default                  | Description                                                 |
|------------------------|----------|--------------------------|-------------------------------------------------------------|
| pathToFlow             | Y        | ./export                 | Path to the directory where the exported flows are located. |

## Environment variables

The table below describes environment variables used in script

| Parameter       | Required | Default                  | Description                                                                                                                                                                                                                                                                                                                                       |
|-----------------|----------|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| NIFI_TARGET_URL | Y        | `https://localhost:8443` | URL for target NiFi.                                                                                                                                                                                                                                                                                                                              |
| DEBUG_MODE      | N        | false                    | If set to 'true', then when upgrading a flow, the difference between upgrade flow and export flow will be shown.                                                                                                                                                                                                                                  |
| NIFI_CERT       | N        |                          | TLS certificates that are used to connect to the NiFi target.<br/> Exact set of arguments depends on Linux distribution, refer to `curl` documentation on your system for more details on TLS-related parameters.<br/>For Alpine Linux the set of parameters is:<br/>`--cert 'client.p12:client.password' --cert-type P12 --cacert nifi-cert.pem` |

## Mapping configuration

Script is supplied with mapping configurations for NiFi 2.5, 2.6, 2.7, 2.10, and 2.11, one file per version:
`upgradeConfig_2_5.json` through `upgradeConfig_2_11.json`.
A version has no file when it did not rename or remove any controller service property.
The script applies every file whose version is later than the version in the export and not later than the target NiFi.
Each configuration file stores the mapping between old and new property names for NiFi components.
In case of property removal, value must be set to `null`.

```json
{
    "org.apache.nifi.services.protobuf.ProtobufReader": {
        "schema-reference-reader": "Schema Reference Reader",
        "schema-branch": "Schema Branch",
        "schema-name": "Schema Name",
        "schema-registry": "Schema Registry",
        "schema-access-strategy": "Schema Access Strategy",
        "schema-version": "Schema Version",
        "schema-text": "Schema Text"
    },
    "org.apache.nifi.xml.XMLRecordSetWriter" : {
        "schema-protocol-version" : null
    }
}
```

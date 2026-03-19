# Upgrade Script for Apache NiFi 2.7.2

## Scripts overview

The script `analyzeAndUpdateNiFiExports.sh` upgrades controller services and reporting tasks exports
done from older NiFi versions to be compatible with NiFi 2.7.2.
The script automatically checks for nifi version in target instance, as well version in export
and determines, if adaptation is required.

Example of running a script:

`bash analyzeAndUpdateNiFiExports.sh <pathToFlow> <pathToUpdateNiFiConfig>`

Input arguments used in the script:

| Argument               | Required | Default                  | Description                                                 |
|------------------------|----------|--------------------------|-------------------------------------------------------------|
| pathToFlow             | Y        | ./export                 | Path to the directory where the exported flows are located. |
| pathToUpdateNiFiConfig | Y        | ./upgradeConfig_2_7.json | Path to mapping config.                                     |

## Environment variables

The table below describes environment variables used in script

| Parameter       | Required | Default                  | Description                                                                                                                                                                                                                                                                                                                                       |
|-----------------|----------|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| NIFI_TARGET_URL | Y        | `https://localhost:8443` | URL for target NiFi.                                                                                                                                                                                                                                                                                                                              |
| DEBUG_MODE      | N        | false                    | If set to 'true', then when upgrading a flow, the difference between upgrade flow and export flow will be shown.                                                                                                                                                                                                                                  |
| NIFI_CERT       | N        |                          | TLS certificates that are used to connect to the NiFi target.<br/> Exact set of arguments depends on Linux distribution, refer to `curl` documentation on your system for more details on TLS-related parameters.<br/>For Alpine Linux the set of parameters is:<br/>`--cert 'client.p12:client.password' --cert-type P12 --cacert nifi-cert.pem` |

## Mapping config

Configuration file that stores the mapping between old and new property names for NiFi components.

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
    }
}
```

# Upgrade Script for Apache NiFi 2.x

## Scripts overview

The script 'updateNiFiFlow.sh' is designed to upgrade exported flows when switching to NiFi 2.4.0. The script changes the type and artifact for processors.
Example of running a script:

`bash updateNiFiFlow.sh <pathToFlow> <pathToUpdateNiFiConfig>`

As input arguments used in script:

| Argument               | Required | Default                       | Description                                                 |
|------------------------|----------|-------------------------------|-------------------------------------------------------------|
| pathToFlow             | Y        | ./export                      | Path to the directory where the exported flows are located. |
| pathToUpdateNiFiConfig | Y        | ./updateNiFiVerNarConfig.json | Path to mapping config.                                     |

## Environment variables

The table below describes environment variables used in script

| Parameter       | Required | Default                  | Description                                                                                                                                                                                                                                                                                                                                       |
|-----------------|----------|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| NIFI_TARGET_URL | Y        | `https://localhost:8443` | URL for target NiFi.                                                                                                                                                                                                                                                                                                                              |
| DEBUG_MODE      | N        | false                    | If set to 'true', then when upgrading a flow, the difference between upgrade flow and export flow will be shown.                                                                                                                                                                                                                                  |
| NIFI_CERT       | N        |                          | TLS certificates that are used to connect to the NiFi target.<br/> Exact set of arguments depends on Linux distribution, refer to `curl` documentation on your system for more details on TLS-related parameters.<br/>For Alpine Linux the set of parameters is:<br/>`--cert 'client.p12:client.password' --cert-type P12 --cacert nifi-cert.pem` |

## Mapping config

Configuration file that stores the mapping between the old and new types for NiFi components.
```json
{
    "org.apache.nifi.processors.standard.JoltTransformJSON": {
            "newType": "org.apache.nifi.processors.jolt.JoltTransformJSON",
            "newArtifact": "nifi-jolt-nar",
            "newGroup": "org.apache.nifi"
    }
}
```

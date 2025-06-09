# Upgrade Script for Apache NiFi 2.x

## Scripts overview

The script 'updateNiFiFlow.sh' is designed to upgrade exported flows when switching to NiFi 2.4.0. The script changes the type and artifact for processors.
Example of running a script:

`updateNiFiFlow.sh arg1 arg2`

As input arguments used in script:

| №    | Argument               | Required | Default                      | Description                                                 |
|------|------------------------|----------|------------------------------|-------------------------------------------------------------|
| arg1 | pathToFlow             | Y        | /export                      | Path to the directory where the exported flows are located. |
| arg2 | pathToUpdateNiFiConfig | Y        | /updateNiFiVerNarConfig.json | Path to mapping config .                                    |

## Environment variables

The table below describes environment variables used in script

| Parameter       | Required | Default                  | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
|-----------------|----------|--------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| NIFI_TARGET_URL | Y        | `https://localhost:8443` | URL for target NiFi.                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| DEBUG_MODE      | N        | false                    | If set to 'true', then when upgrading a flow, the difference between upgrade flow and export flow will be shown.                                                                                                                                                                                                                                                                                                                                                                             |
| NIFI_CERT       | N        |                          | TLS certificates that are used to connect to the NiFi target. In case of `Alpine image`, to connect using certificates you need to use: <br/> 1. PKCS12 keystore<br/> 2. PKCS12 keystore password <br/> 3. The public certificate of the intermediate CA in PEM format. <br/> Example of certificate installation:<br/> `--cert '/path-to-certificate/CN=admin_OU=NIFI.p12:/path-to-certificate/CN=admin_OU=NIFI.password' --cert-type P12 --cacert /path-to-certificate/nifi-cert.pem`<br/> |

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

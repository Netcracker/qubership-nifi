# Update scritps user Guide

## Describe scripts

The script 'updateNiFiFlow.sh' is designed to upgrade exported flows when switching to NiFi 2.4.0. The script changes the type and artifact for processors.
As input arguments used in script:

| Parameter                  | Required | Default                      | Description                                                 |
|----------------------------|----------|------------------------------|-------------------------------------------------------------|
| pathToFlow                 | Y        | /export                      | Path to the directory where the exported flows are located. |
| pathToUpdateNiFiConfig     | Y        | /updateNiFiVerNarConfig.json | Path to mapping config .                                    |

## Environment variables

The table below describes environment variables used in script

| Parameter                     | Required | Default                | Description                                                                                                      |
|-------------------------------|----------|------------------------|------------------------------------------------------------------------------------------------------------------|
| NIFI_TARGET_URL               | Y        | https://localhost:8443 | External Url for target NiFi.                                                                                    |
| DEBUG_MODE                    | N        | false                  | If set to 'true', then when upgrading a flow, the difference between upgrade flow and export flow will be shown. |
| NIFI_CERT                     | N        | empty                  | TLS certificates that are used to connect to the NiFi target.                                                    |

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

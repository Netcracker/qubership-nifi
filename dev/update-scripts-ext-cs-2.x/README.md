# Update script for Apache NiFi 2.x external controller service references

## Scripts overview

The script `updateExternalControllerServices.sh` fixes references to
`externalControllerServices` in NiFi flow exports before they are imported into a
target environment.

Apache NiFi resolves an external controller service reference **by ID first, and
only falls back to matching by name** when no ID matches.
However, starting from Apache NiFi 2.x, match by name does not happen, if referencing component
property was renamed via automatic property migration mechanism, causing NiFi to set the reference
incorrectly.

For each export that declares a non-empty `externalControllerServices` map, the
script looks up the same-named controller service in the target NiFi (top-level /
root services) and rewrites the foreign ID everywhere it appears:

- the `externalControllerServices` map key,
- its `identifier` field,
- and every component `properties` value that referenced that ID.

The script first checks the target NiFi version and only applies changes when the
target major version is `2`; otherwise it skips. If a target controller service
with a matching name cannot be found, the script logs a warning and leaves that
reference unchanged.

Example of running the script:

`bash updateExternalControllerServices.sh <pathToFlow>`

Input arguments used in the script:

| Argument   | Required | Default  | Description                                                 |
|------------|----------|----------|-------------------------------------------------------------|
| pathToFlow | Y        | ./export | Path to the directory where the exported flows are located. |

## Environment variables

The table below lists the environment variables used in the script.

| Parameter       | Required | Default                  | Description                                                                                                                                                                                                                                                                                                                                       |
|-----------------|----------|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| NIFI_TARGET_URL | Y        | `https://localhost:8443` | URL for target NiFi.                                                                                                                                                                                                                                                                                                                              |
| DEBUG_MODE      | N        | false                    | If set to 'true', the difference between the updated flow and the exported flow is shown when updating a flow.                                                                                                                                                                                                                                    |
| NIFI_CERT       | N        |                          | TLS certificates that are used to connect to the NiFi target.<br/> Exact set of arguments depends on Linux distribution, refer to `curl` documentation on your system for more details on TLS-related parameters.<br/>For Alpine Linux the set of parameters is:<br/>`--cert 'client.p12:client.password' --cert-type P12 --cacert nifi-cert.pem` |

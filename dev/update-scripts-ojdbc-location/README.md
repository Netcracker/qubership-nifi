# Oracle driver location update script

The script `updateOjdbcDriverLocation.sh` sets the driver location on Oracle connection pools in NiFi exports, so the
pools find the Oracle JDBC driver, which is not in the NiFi lib directory. Run it on flow exports and controller
service exports before you import them into NiFi. The script needs `bash`, `jq`, `curl`, and access to the API of
the target NiFi.

The script changes the exports only when the target NiFi runs qubership-nifi 2.6.0 or later. qubership-nifi 2.6.0
moved to Apache NiFi 2.9.0, which brought the change that makes the driver location necessary; older versions run an
older Apache NiFi and need no update. The script reads the version from the bundle of
`OraclePreparedStatementWithArrayProvider` in `/nifi-api/flow/controller-service-types`:

- Below 2.6.0, the script prints `Skipping update.`, leaves the exports unchanged, and exits with code 0.
- If the target NiFi has no `OraclePreparedStatementWithArrayProvider`, the script exits with an error, because the
  qubership-nifi version is unknown.

Example of running the script:

```bash
bash updateOjdbcDriverLocation.sh <pathToFlow>
```

The script takes one argument:

| Argument   | Required | Default    | Description                                                                 |
|------------|----------|------------|-----------------------------------------------------------------------------|
| pathToFlow | N        | `./export` | Directory with the exports, searched recursively for `*.json` files.        |

## What the script changes

The script updates each DBCPConnectionPool and HikariCPConnectionPool, in a controller service export or anywhere in
a flow export, including nested process groups, that meets both conditions:

- Database Driver Class Name is `oracle.jdbc.OracleDriver` or `oracle.jdbc.driver.OracleDriver`.
- Database Driver Location(s) is empty or missing.

The script compares the literal value of Database Driver Class Name, so it skips a pool that sets the driver class
through a parameter (`#{db.driver}`) or Expression Language (`${db.driver}`). Set Database Driver Location(s) on such
a pool by hand.

Such a pool gets this Database Driver Location(s) value:

```text
${OJDBC_DRIVER_LOCATION:replaceEmpty(${NIFI_HOME:append('/nifi-config-template')})}
```

If the pool already has `Database Driver Locations` or the old key from the table below, the script writes the value to
the keys it has. Otherwise, the property key depends on the bundle version of the pool in the export:

| Pool                   | Bundle version below 2.7    | Bundle version 2.7 or later |
|------------------------|-----------------------------|-----------------------------|
| DBCPConnectionPool     | `database-driver-locations` | `Database Driver Locations` |
| HikariCPConnectionPool | `hikaricp-driver-locations` | `Database Driver Locations` |

Run the script before the scripts in `dev/update-scripts-prop-2.x` and `dev/update-scripts-flow-2.x`. They rename the
driver location key for the target NiFi version and keep the bundle version of the export, and this script picks the
key from that bundle version.

At NiFi startup, `nifi-scripts/update_flow_json_ojdbc_location.sh` applies the same change to `flow.json.gz`.

## Environment variables

| Parameter       | Required | Default                  | Description                                                                                                                                                                                                                                                                  |
|-----------------|----------|--------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| NIFI_TARGET_URL | Y        | `https://localhost:8443` | URL of the target NiFi. The script reads the qubership-nifi version from it.                                                                                                                                                                                                 |
| NIFI_CERT       | N        |                          | TLS arguments `curl` uses to connect to the target NiFi.<br/>The exact set depends on the Linux distribution; see the `curl` documentation on your system.<br/>For Alpine Linux the set is:<br/>`--cert 'client.p12:client.password' --cert-type P12 --cacert nifi-cert.pem` |
| DEBUG_MODE      | N        | false                    | If set to `true`, the script prints a diff of each file it changes.                                                                                                                                                                                                          |

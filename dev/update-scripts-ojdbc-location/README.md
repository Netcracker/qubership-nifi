# Oracle driver location update script

The script `updateOjdbcDriverLocation.sh` sets the driver location on Oracle connection pools in NiFi exports, so the
pools find the Oracle JDBC driver, which is not in the NiFi lib directory. Run it on flow exports and controller
service exports before you import them into NiFi. The script needs `bash` and `jq`, and does not connect to NiFi.

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

Such a pool gets this Database Driver Location(s) value:

```text
${OJDBC_DRIVER_LOCATION:replaceEmpty(${NIFI_HOME:append('/nifi-config-template')})}
```

The property key depends on the bundle version of the pool in the export:

| Pool                   | Bundle version below 2.7    | Bundle version 2.7 or later |
|------------------------|-----------------------------|-----------------------------|
| DBCPConnectionPool     | `database-driver-locations` | `Database Driver Locations` |
| HikariCPConnectionPool | `hikaricp-driver-locations` | `Database Driver Locations` |

Run the script before the scripts in `dev/update-scripts-prop-2.x` and `dev/update-scripts-flow-2.x`. They rename the
driver location key for the target NiFi version and keep the bundle version of the export, and this script picks the
key from that bundle version.

At NiFi startup, `nifi-scripts/update_flow_json_ojdbc_location.sh` applies the same change to `flow.json.gz`.

## Environment variables

| Parameter  | Required | Default | Description                                                         |
|------------|----------|---------|---------------------------------------------------------------------|
| DEBUG_MODE | N        | false   | If set to `true`, the script prints a diff of each file it changes. |

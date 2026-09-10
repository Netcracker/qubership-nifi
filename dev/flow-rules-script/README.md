# Flow analysis rules provisioning script

## Scripts overview

`createFlowRules.sh` creates NiFi flow analysis rules in a running NiFi instance from a JSON
configuration file. For each entry it creates the rule through the NiFi REST API, applies the
configured properties and enforcement policy, and enables the rule.

The script is idempotent by rule type: if a rule of the same `Type` already exists in the target
NiFi, that entry is skipped, so re-running the script does not create duplicates.

Example of running the script:

```bash
bash createFlowRules.sh ./flowAnalysisRuleConf.json
```

Input arguments used in the script:

| Argument     | Required | Default | Description                          |
|--------------|----------|---------|--------------------------------------|
| pathToConfig | Y        | -       | Path to the JSON configuration file. |

Prerequisites:

- `jq` and `curl` on the machine that runs the script.
- A running NiFi whose REST API is reachable, with a user allowed to modify the `/controller` and
  `/flow-analysis-rules` resources.
- Every custom rule type listed in the configuration must be installed in the target NiFi (the
  `qubership-nifi-flow-analysis-rules` NAR is part of the qubership-nifi image). Built-in rule types
  such as `org.apache.nifi.flowanalysis.rules.RestrictBackpressureSettings` need nothing extra.

## Environment variables

| Parameter         | Required | Default                  | Description |
|-------------------|----------|--------------------------|-------------|
| NIFI_TARGET_URL   | N        | `https://localhost:8443` | Base URL of the target NiFi. |
| NIFI_CERT         | N        |                          | TLS arguments passed to `curl` for mutual TLS. The exact set depends on the Linux distribution; refer to the `curl` documentation on your system. For Alpine Linux: `--cert 'client.p12:client.password' --cert-type P12 --cacert nifi-cert.pem` |
| NIFI_ACCESS_TOKEN | N        |                          | Bearer token for single-user or OIDC authentication. When set, the script adds an `Authorization: Bearer <token>` header to every request. |

Set either `NIFI_CERT` (mutual TLS) or `NIFI_ACCESS_TOKEN` (bearer token), matching how the target
NiFi authenticates clients.

## Configuration file

A JSON array of objects, one per flow analysis rule.

| Field    | Required | Description |
|----------|----------|-------------|
| Name     | Y        | Name of the rule instance, shown in the flow analysis rules list in the NiFi UI. |
| Type     | Y        | Fully qualified class name of the flow analysis rule. |
| Policy   | Y        | Enforcement policy: `Warn` or `Enforce` (case-insensitive). |
| Property | Y        | Array of `{ "name": ..., "value": ... }` objects with the rule properties. Use `[]` when the rule has no properties. |

Example (`flowAnalysisRuleConf.json`):

```json
[
  {
    "Name": "UniqueProcessorNames",
    "Type": "org.qubership.nifi.flowanalysis.unique.UniqueProcessorNames",
    "Policy": "Warn",
    "Property": []
  },
  {
    "Name": "RestrictBackpressureSettings",
    "Type": "org.apache.nifi.flowanalysis.rules.RestrictBackpressureSettings",
    "Policy": "Warn",
    "Property": [
      { "name": "Min Backpressure Object Count Threshold", "value": "1" },
      { "name": "Max Backpressure Object Count Threshold", "value": "20000" },
      { "name": "Min Backpressure Data Size Threshold", "value": "1 MB" },
      { "name": "Max Backpressure Data Size Threshold", "value": "1 GB" }
    ]
  },
  {
    "Name": "RestrictSourceProcessorRunSchedule",
    "Type": "org.qubership.nifi.flowanalysis.scheduling.RestrictSourceProcessorRunSchedule",
    "Policy": "Warn",
    "Property": [
      { "name": "Run Schedule Threshold", "value": "100 msec" }
    ]
  }
]
```

The repository ships a `flowAnalysisRuleConf.json` with the six custom qubership-nifi rules plus the
built-in `RestrictBackpressureSettings` as a starting point.

Property names must match the rule's property descriptor names exactly. NiFi ignores an unknown
property name silently, so verify the names in the NiFi UI (Controller Settings -> Flow Analysis
Rules) if a property does not take effect.

## What the script does

1. Checks that the configuration file exists.
2. `GET /nifi-api/flow/flow-analysis-rule-types` - resolves the bundle coordinates for each rule
   type. A type that is not installed in the target NiFi is a fatal error.
3. `GET /nifi-api/controller/flow-analysis-rules` - the rules that already exist, used to skip
   entries whose `Type` is already present.
4. For each remaining entry:
   - `POST /nifi-api/controller/flow-analysis-rules` with the type, bundle, name, enforcement
     policy, and properties (expects HTTP 201).
   - If the created rule is not `VALID` (for example an invalid property value), it is left
     disabled, the validation errors are printed, and the script continues.
   - `PUT /nifi-api/controller/flow-analysis-rules/{id}/run-status` with `state: "ENABLED"`
     (expects HTTP 200).
5. Prints a summary: how many rules were created and how many were skipped.

On any unexpected HTTP response the script prints the response body, removes its temporary files,
and exits with a non-zero code.

The script writes four temporary files in the working directory
(`flow-analysis-rule-types.json`, `flow-analysis-rules-existing.json`, `flow-analysis-rule-body.json`,
`flow-analysis-rule-response.json`) and deletes them before it exits.

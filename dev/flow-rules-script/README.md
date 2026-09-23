# Flow analysis rules provisioning script

## Scripts overview

`createFlowRules.sh` creates and updates NiFi flow analysis rules in a running NiFi instance. It reads the rules
from a Consul key or from a JSON configuration file, and for each entry it creates the rule through the NiFi REST
API, applies the configured properties and enforcement policy, and enables the rule.

Rules are matched by `Name`, so re-running the script does not create duplicates. A rule whose enforcement policy
and configured properties already match the configuration is left as it is, except that a rule which is `DISABLED`
but `VALID` is enabled, which repairs a rule left disabled by an earlier failed run. A rule that differs is
disabled, updated, and enabled again, and the script prints what it changed.

A rule that NiFi reports as not `VALID` stays `DISABLED` and enforces nothing. The script still processes every
other entry, then prints the names of those rules and exits with a non-zero code.

Example of running the script:

```bash
bash createFlowRules.sh ./flowAnalysisRuleConf.json
```

Input arguments used in the script:

| Argument     | Required | Default | Description                                                                        |
|--------------|----------|---------|------------------------------------------------------------------------------------|
| pathToConfig | Y        | -       | Path to the JSON configuration file. Used when the rules are not read from Consul. |

Prerequisites:

- `jq` and `curl` on the machine that runs the script.
- A running NiFi whose REST API is reachable, with a user allowed to modify the `/controller` and
  `/flow-analysis-rules` resources.
- Every custom rule type listed in the configuration must be installed in the target NiFi (the
  `qubership-nifi-flow-analysis-rules` NAR is part of the qubership-nifi image). Built-in rule types
  such as `org.apache.nifi.flowanalysis.rules.RestrictBackpressureSettings` need nothing extra.

## Environment variables

| Parameter        | Required | Default                  | Description                                                                                                                                                                                                                                      |
|------------------|----------|--------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| NIFI_TARGET_URL  | N        | `https://localhost:8443` | Base URL of the target NiFi.                                                                                                                                                                                                                     |
| NIFI_CERT        | N        |                          | TLS arguments passed to `curl` for mutual TLS. The exact set depends on the Linux distribution; refer to the `curl` documentation on your system. For Alpine Linux: `--cert 'client.p12:client.password' --cert-type P12 --cacert nifi-cert.pem` |
| CONSUL_URL       | N        |                          | URL of the Consul the rules are read from, either as `<hostname>:<port>` or as `<protocol>://<hostname>:<port>`. Leave it empty to read the rules from the configuration file.                                                                   |
| NAMESPACE        | N        | `local`                  | Namespace segment of the Consul key. Not used when `CONSUL_URL` is empty.                                                                                                                                                                        |
| CONSUL_ACL_TOKEN | N        |                          | Consul ACL token, sent in the `X-Consul-Token` header. Leave it empty for a Consul with ACLs disabled.                                                                                                                                           |

Leave `NIFI_CERT` empty if the target NiFi does not require mutual TLS.

## Reading the configuration from Consul

With `CONSUL_URL` set, the script reads the rules from the Consul key
`config/<NAMESPACE>/qubership-nifi/flow-analysis-rules`. Its value is the same JSON array the configuration
file holds, described under [Configuration file](#configuration-file).

The path to the configuration file stays a required argument, and the file is the fallback:

| Condition                                                                           | Rules are read from                                                          |
|-------------------------------------------------------------------------------------|------------------------------------------------------------------------------|
| `CONSUL_URL` is empty                                                               | The configuration file.                                                      |
| Consul holds the key                                                                | Consul.                                                                      |
| Consul answers `404`, so the key is not set                                         | The configuration file.                                                      |
| The key holds anything other than a JSON array                                      | Neither: the script reports the error and exits with a non-zero code.        |
| Consul cannot be reached                                                            | Neither: the script reports the error and exits with a non-zero code.        |
| Consul answers with any other code, such as the `403` a rejected ACL token produces | Neither: the script prints the response body and exits with a non-zero code. |

A key that exists but holds an unusable value is a misconfiguration, so the script reports it instead of falling
back to the file, which would hide it.

The key name does not start with `nifi` or `logger.`, and a replacement must not either. The Consul integration of
the qubership-nifi image copies every key of this folder whose name starts with `nifi` into the `nifi.properties`
it generates, and turns every key whose name starts with `logger.` into a `logback.xml` logger. The value of this
key is a multi-line JSON array, which fits neither.

The script has no TLS options for Consul. An `https://` `CONSUL_URL` works only when `curl` already trusts the
certificate of the Consul server.

Writing the rules to Consul:

```bash
curl -X PUT --data @flowAnalysisRuleConf.json \
    "http://localhost:8500/v1/kv/config/local/qubership-nifi/flow-analysis-rules"
```

### Consul ACL token

The `get_consul_token` function in `createFlowRules.sh` returns the token the read is made with. Its default
implementation returns `CONSUL_ACL_TOKEN`. Replace the body to read the token from somewhere else, such as a
mounted secret:

```bash
get_consul_token() {
    cat /run/secrets/consul-acl-token
}
```

An empty token sends the request without an `X-Consul-Token` header, which is what a Consul with ACLs disabled
needs.

## Configuration file

A JSON array of objects, one per flow analysis rule.

| Field    | Required | Description                                                                                                                                                                                                                   |
|----------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Name     | Y        | Name of the rule instance, shown in the flow analysis rules list in the NiFi UI.                                                                                                                                              |
| Type     | Y        | Fully qualified class name of the flow analysis rule.                                                                                                                                                                         |
| Policy   | Y        | Enforcement policy: `Warn` or `Enforce` (case-insensitive).                                                                                                                                                                   |
| Property | Y        | Array of `{ "name": ..., "value": ... }` objects with the rule properties. Use `[]` when the rule has no properties. A value written as a number or a boolean is sent as its string form, because that is how NiFi stores it. |

Minimal example of the format:

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
    "Policy": "Enforce",
    "Property": [
      { "name": "Maximum Backpressure Object Count Threshold", "value": "20000" },
      { "name": "Maximum Backpressure Data Size Threshold", "value": "1 GB" }
    ]
  }
]
```

The repository ships a `flowAnalysisRuleConf.json` with the six custom qubership-nifi rules plus the
built-in `RestrictBackpressureSettings` as a starting point. Review it against your flows before you apply it:
some of its rules are set to `Enforce`, as described under
[Rules the shipped configuration enforces](#rules-the-shipped-configuration-enforces).

Each entry must have a unique `Name`. A configuration that lists a name twice fails the run before any rule is
created or updated.

Property names must match the rule's property descriptor names exactly. An unknown property name
makes the rule invalid: the script prints the validation errors, leaves the rule disabled, and exits with a
non-zero code. Verify the names in the NiFi UI (Controller Settings -> Flow Analysis Rules) if a rule ends up
in this state.

Only the properties an entry lists are compared against an existing rule. A property the entry leaves out keeps
whatever value the rule has, so it never counts as a difference. A sensitive property always counts as a
difference, because NiFi never returns its value.

A property `value` must not be `null`, because NiFi keeps the current value of a required property that an update
sets to `null`. To reset a property, write its default value. A configuration with a `null` value fails the run
before any rule is created or updated.

## Rules the shipped configuration enforces

Nothing applies `flowAnalysisRuleConf.json` automatically. Once you run the script with it, three rules are set to
`Enforce`, and a component that violates an enforced rule becomes invalid and cannot be started. Check your flows
against the limits below before you run the script, including on a NiFi where an earlier run created these rules
with `Warn`: the script updates a rule whose policy differs from the configuration.

| Rule                                 | A component passes when                                                                                                                             |
|--------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| `UniqueControllerServiceNames`       | No other controller service with the same name exists in its process group or in a descendant process group.                                        |
| `RestrictBackpressureSettings`       | The connection's back pressure object threshold is between 1 and 20000, and its data size threshold is between 1 MB and 1 GB.                       |
| `RestrictSourceProcessorRunSchedule` | The source processor (timer driven, with no incoming connection) has a Run Schedule above `50 millis`, or its type is in `Ignored Processor Types`. |

`Ignored Processor Types` lists the processors that can run as a source and, while idle, either wait inside
`onTrigger` with a timeout above 20 ms or yield, so a Run Schedule of `0 sec` costs little CPU. Listeners that
return from `onTrigger` without such a wait or a yield, such as `HandleHttpRequest`, `ListenTCP`, `ListenUDP`, and `ListenSyslog`, are left
out on purpose: at `0 sec` NiFi calls them constantly and idle CPU usage goes up. Give them a Run Schedule above
`50 millis`.

To keep one of these rules at `Warn` while you fix the flows, write a copy of the configuration with `"Policy":
"Warn"` for that rule to the Consul key and run the script again.

## Running the tests

`CreateFlowRulesIT` in `dev-tools-integration-tests` drives this script against a live NiFi and a live Consul.
Bring up the `oidc` compose stack, which publishes NiFi on `https://localhost:8080` and Consul on
`http://localhost:8500`, and generates the mTLS certificates under `temp-vol/tls-cert/nifi`:

```bash
. .github/workflows/sh/nifi-lib.sh
setup_env_before_tests "oidc"
docker compose -f .github/docker/oidc/docker-compose.yaml --env-file ./docker.env up -d --wait
```

Then run the suite:

```bash
export NIFI_CLIENT_PASSWORD=$(cat './temp-vol/tls-cert/nifi/CN=admin_OU=NIFI.password')
mvn verify -pl dev-tools-integration-tests -P flow-rules-tests -DskipITs=false \
    "-Dnifi.cert.dir=$(pwd)/temp-vol/tls-cert/nifi" \
    -Dconsul.url=http://localhost:8500
```

The class fails when a prerequisite is missing rather than skipping itself; a skipped suite leaves the build green
with nothing asserted, which looks exactly like a passing run. If your machine cannot satisfy the prerequisites,
leave the integration tests out by not passing `-DskipITs=false`.

The tests need a NiFi 2.x: flow analysis rules arrived in Apache NiFi 2.0, so the `dev-tools-test` workflow runs
this suite on every image in its matrix except the NiFi 1.28.1 one.

On Windows, the first `bash` on the `PATH` is often the one from WSL, which cannot see native paths. Point
`DEV_SCRIPTS_BASH` at a `bash` that shares a filesystem with the repository:

```bash
export DEV_SCRIPTS_BASH="C:/Program Files/Git/bin/bash.exe"
```

## What the script does

1. Checks that the configuration file exists.
2. Reads the rules from Consul or from the configuration file, as described under
   [Reading the configuration from Consul](#reading-the-configuration-from-consul).
3. Checks that the rules are a JSON array in which no `Name` appears twice and no property value is `null`. A
   failed check ends the run before any request to NiFi.
4. `GET /nifi-api/flow/flow-analysis-rule-types` - resolves the bundle coordinates for each rule
   type. A type that is not installed in the target NiFi is a fatal error.
5. `GET /nifi-api/controller/flow-analysis-rules` - the rules that already exist, matched against the
   configuration by `Name`.
6. For each entry whose `Name` already exists:
   - A rule whose type differs from the configured `Type` is a fatal error. The type of a rule is fixed once it
     is created, so the rule has to be deleted before the script can create it again.
   - The configured enforcement policy and properties are compared against the rule. One that matches on every
     configured value is left alone, except that a rule which is `DISABLED` but `VALID` is enabled, and a rule
     which is `DISABLED` and not `VALID` fails the run.
   - A rule that differs is brought in line with the configuration, and the differences are printed. NiFi
     accepts the change only while the rule is disabled, so the script sends
     `PUT /nifi-api/controller/flow-analysis-rules/{id}/run-status` with `state: "DISABLED"`, polls
     `GET /nifi-api/controller/flow-analysis-rules/{id}` until NiFi reports `DISABLED`, sends
     `PUT /nifi-api/controller/flow-analysis-rules/{id}` with the new policy and properties, and enables the
     rule again. A rule the update leaves not `VALID` stays disabled and fails the run.
7. For each entry whose `Name` does not exist yet:
   - `POST /nifi-api/controller/flow-analysis-rules` with the type, bundle, name, enforcement
     policy, and properties (expects HTTP 201).
   - NiFi validates the new rule asynchronously, so the script polls
     `GET /nifi-api/controller/flow-analysis-rules/{id}` (up to 10 times, 1 second apart) until
     validation settles. A rule still `VALIDATING` after that is a fatal error.
   - If the rule is not `VALID` (for example an invalid property value), it is left disabled, the
     validation errors are printed, and the script continues with the next entry.
   - `PUT /nifi-api/controller/flow-analysis-rules/{id}/run-status` with `state: "ENABLED"`
     (expects HTTP 200).
8. Prints a summary: how many rules were created, how many were updated, and how many were skipped because they
   already matched the configuration.
9. Exits with a non-zero code if any rule was left `DISABLED` because it is not `VALID`, and names those rules.

On any unexpected HTTP response the script prints the response body, removes its temporary files,
and exits with a non-zero code.

The script writes five temporary files in the working directory
(`flow-analysis-rule-types.json`, `flow-analysis-rules-existing.json`, `flow-analysis-rule-body.json`,
`flow-analysis-rule-response.json`, `flow-analysis-rules-consul.json`) and deletes them before it exits.

# NiFi API usage collector

`collect_api_usage.py` lists the external APIs that the flows on a NiFi instance expose and call. It works with NiFi
1.x and 2.x. The report has one section per top-level process group that has at least one API. Each section lists the
REST, Kafka, S3, and RabbitMQ processors in that group and in all its child groups, split into outbound and inbound.

## How it works

1. Reads the top-level process groups from `GET /nifi-api/flow/process-groups/root`.
2. Downloads each group with `GET /nifi-api/process-groups/{id}/download` into a new temporary directory,
   `api-usage-tmp-<random suffix>`, under the current working directory. A group the user cannot read, or whose
   download fails, is skipped with a warning; see [Skipped groups and exit codes](#skipped-groups-and-exit-codes).
3. Finds the processors listed in [Detected processors](#detected-processors) in each download.
4. Leaves out the REST entries that match `--exclude-hosts` or `--exclude-paths-regex`, printing a line for each.
5. Writes `<prefix>.md` and `<prefix>.json` to the current working directory. A group with no inbound or outbound
   APIs is left out of both files, and the script prints a line saying so.

The script sends GET requests only and does not change the flow.

Processors placed directly on the root canvas, outside any process group, are not analyzed and do not appear in the
report. To include them, move them into a process group.

## Prerequisites

- Python 3.10 or later.
- For `--auth cert`: the `cryptography` package or the `openssl` command, used to read the PKCS#12 client
  certificate. `requirements.txt` pins `cryptography` and its dependencies with their hashes:

  ```bash
  python -m pip install --require-hashes -r requirements.txt
  ```

  When `cryptography` is not installed, the script runs `openssl pkcs12`. If OpenSSL 3 cannot read the file, which
  happens with PKCS#12 files encrypted with RC2 or 3DES by older Java `keytool` versions, the script runs the command
  again with `-legacy`.

## Usage

```bash
export NIFI_PKCS12_PASSWORD='<client certificate password>'
python <pathToScript>/collect_api_usage.py --url https://localhost:8443 --auth cert \
    --ca-cert ca.cer --client-cert client.p12
```

With a NiFi login cookie instead of a client certificate:

```bash
export NIFI_AUTHORIZATION_BEARER_COOKIE='<value of the __Secure-Authorization-Bearer cookie>'
python <pathToScript>/collect_api_usage.py --url https://localhost:8443 --auth cookie --ca-cert ca.cer
```

| Parameter               | Required           | Default     | Description                                                                         |
| ----------------------- | ------------------ | ----------- | ----------------------------------------------------------------------------------- |
| `--url`                 | Y                  |             | NiFi base URL. A URL ending in `/nifi` or `/nifi-api` is accepted too.              |
| `--auth`                | Y                  |             | `cert` for a PKCS#12 client certificate, `cookie` for a NiFi login cookie.          |
| `--ca-cert`             | Y                  |             | PEM file with the CA certificate that signed the NiFi server certificate.           |
| `--client-cert`         | With `--auth cert` |             | PKCS#12 file with the client certificate and its private key.                       |
| `--output-prefix`       | N                  | `api-usage` | Report filename without the extension.                                              |
| `--keep-temp`           | N                  |             | Keep the downloaded flow JSON files. By default the temporary directory is deleted. |
| `--exclude-hosts`       | N                  |             | Comma-separated hostnames. See [Excluding entries](#excluding-entries).             |
| `--exclude-paths-regex` | N                  |             | Regular expression for REST paths. See [Excluding entries](#excluding-entries).     |

| Environment variable               | Used with       | Description                                                            |
| ---------------------------------- | --------------- | ---------------------------------------------------------------------- |
| `NIFI_PKCS12_PASSWORD`             | `--auth cert`   | Password of the PKCS#12 client certificate.                            |
| `NIFI_AUTHORIZATION_BEARER_COOKIE` | `--auth cookie` | Value of the `__Secure-Authorization-Bearer` cookie, without the name. |

### Excluding entries

Two options leave REST entries out of both report files:

- `--exclude-hosts` takes a comma-separated list of hostnames. A REST client entry (InvokeHTTP, PostHTTP, GetHTTP) is
  left out when the host of its URL is in the list. The comparison ignores case and the port, and matches the whole
  hostname, so `example.com` does not exclude `api.example.com`. A URL whose host is still a parameter or variable
  reference after [resolution](#property-values) is never excluded. HandleHttpRequest and ListenHTTP entries are not
  excluded by host.
- `--exclude-paths-regex` takes a Python regular expression. A REST entry in either direction is left out when the
  expression matches its whole `path`, so `/health` excludes `/health` but not `/health/live`; use `/health.*` for
  both. For HandleHttpRequest and ListenHTTP, the expression is matched against the `Allowed Paths` or `Base Path`
  value as written, and `Allowed Paths` is itself a regular expression: `/health` does not exclude an entry whose
  `Allowed Paths` is `/health|/ready`. An entry with no `path` is never excluded. An invalid expression stops the script with
  `error: argument --exclude-paths-regex: invalid regular expression ...` before it connects to NiFi.

The script prints one line for each excluded entry, with the processor, its identifier, its path, the top-level group,
and the reason:

```text
Excluded outbound REST processor 'InvokeHTTP' (<processor id>) at Call Processing/InvokeHTTP in REST Integration (<group id>): host google.com is in --exclude-hosts
```

A group whose entries are all excluded is left out of the report, like a group with no APIs.

```bash
python <pathToScript>/collect_api_usage.py --url https://localhost:8443 --auth cookie --ca-cert ca.cer \
    --exclude-hosts 'localhost,internal.example.com' --exclude-paths-regex '/health.*|/metrics'
```

### Skipped groups and exit codes

A top-level process group is skipped when the user has no read permission on it, when its download fails (for
example, with HTTP 403), or when the response is not a flow, such as an HTML login page returned by a proxy. For each
skipped group, the script prints a warning to stderr and goes on with the other groups:

```text
Warning: skipped process group Billing (<group id>): GET /process-groups/<group id>/download -> HTTP 403: ...
```

The Markdown report then ends with a `# Skipped process groups` table that lists each skipped group with the reason.
The JSON report holds only the groups that were analyzed.

| Exit code | Meaning                                                                                  |
| --------- | ---------------------------------------------------------------------------------------- |
| 0         | Every top-level group was analyzed.                                                      |
| 1         | The script stopped with an `Error:` message, such as a TLS failure, and wrote no report. |
| 2         | Invalid command-line arguments.                                                          |
| 3         | The report was written, but at least one group was skipped.                              |

## Detected processors

Processors that read data are inbound, and processors that write data are outbound. HandleHttpResponse is not listed,
because it answers the request its HandleHttpRequest already reports.

| Type     | Inbound                                                     | Outbound                                                  |
| -------- | ----------------------------------------------------------- | --------------------------------------------------------- |
| REST     | HandleHttpRequest, ListenHTTP, GetHTTP                      | InvokeHTTP, PostHTTP                                      |
| Kafka    | ConsumeKafka, ConsumeKafkaRecord                            | PublishKafka, PublishKafkaRecord                          |
| S3       | ListS3, FetchS3Object, GetS3ObjectMetadata, GetS3ObjectTags | PutS3Object, DeleteS3Object, CopyS3Object, TagS3Object    |
| RabbitMQ | ConsumeAMQP                                                 | PublishAMQP                                               |

A Kafka processor is detected with no version suffix or with `_1_0`, `_2_0`, or `_2_6`; for example,
`ConsumeKafka_2_6`. Other suffixes, such as `_0_10` and `_0_11`, are not detected. A processor whose type is not in
the table is not reported.

## Property values

Property values in the report are resolved as follows:

- A parameter reference such as `#{kafka.topic}` is replaced with the parameter value from the parameter context of
  the group that holds the processor, including inherited contexts. A sensitive parameter, or one the context does not
  define, stays as written.
- On NiFi 1.x, a bare variable reference such as `${kafka.topic}` is replaced with the variable value. A group sees the
  variables of its own registry and of every ancestor group up to the root, and the nearest definition wins.
- Any other Expression Language, such as `${filename}` with no matching variable or `${host:toUpper()}`, stays as
  written.

## Report files

The Markdown report has a `# <process group name>` section for each top-level group that has APIs, with
`## Outbound` and `## Inbound` subsections. Each subsection has one `### <type>` table per API type it contains, in the
order REST, Kafka, S3, RabbitMQ. A subsection with no APIs says `None found.` When groups were
[skipped](#skipped-groups-and-exit-codes), a `# Skipped process groups` table follows the last group:

```markdown
# Orders

## Outbound

### REST

| Method | URL | Path | Processor identifier | Processor relative path |
| --- | --- | --- | --- | --- |
| POST | https://example.com/api/invoices | /api/invoices | 0b7c...-... | Billing/Send invoice |

### Kafka

| Topics | Bootstrap servers | Processor identifier | Processor relative path |
| --- | --- | --- | --- |
| orders.created | kafka:9092 | 1f2e...-... | Publish/PublishKafka |

## Inbound

None found.
```

The table columns depend on the type and the direction. Every table ends with the processor identifier and the
processor path.

| Type     | Outbound columns                              | Inbound columns                                   |
| -------- | --------------------------------------------- | ------------------------------------------------- |
| REST     | Method, URL, Path                             | Method, Path, Port                                |
| Kafka    | Topics, Bootstrap servers                     | Topics, Topic type, Group ID, Bootstrap servers   |
| S3       | Bucket, Key or prefix, Region, Endpoint       | Bucket, Key or prefix, Region, Endpoint           |
| RabbitMQ | Exchange, Routing key, Broker, Virtual host   | Queue, Broker, Virtual host                       |

For CopyS3Object, the Bucket and Key cells show the source and the destination as `source -> destination`. The Broker
cell shows the Brokers property, or `host:port` when Brokers is empty.

The processor path is relative to the top-level group: the child groups from the top down, then the processor name.

The JSON report holds one object per top-level group that has APIs:

```json
[
  {
    "pgId": "<top-level group id>",
    "pgName": "Orders",
    "inbound": [],
    "outbound": [
      {
        "type": "REST",
        "processorId": "<processor id>",
        "processorName": "Send invoice",
        "state": "ENABLED",
        "processorPath": [{"name": "Billing", "id": "<group id>"}],
        "additionalInfo": {
          "url": "https://example.com/api/invoices",
          "path": "/api/invoices",
          "method": ["POST"]
        }
      }
    ]
  }
]
```

`type` is one of `REST`, `Kafka`, `S3`, or `RabbitMQ`. `state` is the scheduled state of the processor as the group
download records it: `ENABLED` or `DISABLED`. A download does not record whether an enabled processor is running or
stopped, so `ENABLED` covers both. `additionalInfo` holds the fields below that have a value:

| Type           | Fields                                                                                                                             |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| REST, outbound | `url`; `path`, the path part of `url` without the query and fragment, omitted when `url` has no scheme; `method`, a one-item array |
| REST, inbound  | `url` and `path`, both the listening path; `method`, an array of the allowed methods; `port`; `hostname`                           |
| Kafka          | `topics`, `topicType` (`names` or `pattern`), `groupId`, `bootstrapServers`                                                        |
| S3             | `bucket`, `key` or `prefix` (ListS3), `region`, `endpointOverride`; CopyS3Object adds `destinationBucket` and `destinationKey`     |
| RabbitMQ       | `brokers`, `host`, `port`, `virtualHost`; `queue` for ConsumeAMQP; `exchange` and `routingKey` for PublishAMQP                     |

On NiFi 2.x, ConsumeKafka and PublishKafka read the brokers from a Kafka connection service. `bootstrapServers` is set
when that service is part of the download.

## Running the tests

```bash
pip install -r tests/requirements-test.txt
python -m pytest tests
```

The requirements file pins every package with its hashes, so pip installs it in hash-checking mode. It includes the
`cryptography` package, which the HTTP client tests use to make the certificate for a local HTTPS server. Without
`cryptography`, pytest skips those tests.

# NiFi API usage collector

`collect_api_usage.py` lists the external APIs that the flows on a NiFi instance expose and call. It works with NiFi
1.x and 2.x. The report has one section per top-level process group that has at least one API. Each section lists the
REST, Kafka, S3, and RabbitMQ processors in that group and in all its child groups, split into outbound and inbound.

## How it works

1. Reads the top-level process groups from `GET /nifi-api/flow/process-groups/root`.
2. Downloads each group with `GET /nifi-api/process-groups/{id}/download` into a new temporary directory,
   `api-usage-tmp-<timestamp>`, under the current working directory.
3. Finds the processors listed in [Detected processors](#detected-processors) in each download.
4. Writes `<prefix>.md` and `<prefix>.json` to the current working directory. A group with no inbound or outbound
   APIs is left out of both files, and the script prints a line saying so.

The script sends GET requests only and does not change the flow.

## Prerequisites

- Python 3.10 or later.
- For `--auth cert`: the `cryptography` package (`pip install -r requirements.txt`) or the `openssl` command, used to
  read the PKCS#12 client certificate.

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

| Parameter         | Required           | Default     | Description                                                                         |
| ----------------- | ------------------ | ----------- | ----------------------------------------------------------------------------------- |
| `--url`           | Y                  |             | NiFi base URL. A URL ending in `/nifi` or `/nifi-api` is accepted too.              |
| `--auth`          | Y                  |             | `cert` for a PKCS#12 client certificate, `cookie` for a NiFi login cookie.          |
| `--ca-cert`       | Y                  |             | PEM file with the CA certificate that signed the NiFi server certificate.           |
| `--client-cert`   | With `--auth cert` |             | PKCS#12 file with the client certificate and its private key.                       |
| `--output-prefix` | N                  | `api-usage` | Report filename without the extension.                                              |
| `--keep-temp`     | N                  |             | Keep the downloaded flow JSON files. By default the temporary directory is deleted. |

| Environment variable               | Used with       | Description                                                            |
| ---------------------------------- | --------------- | ---------------------------------------------------------------------- |
| `NIFI_PKCS12_PASSWORD`             | `--auth cert`   | Password of the PKCS#12 client certificate.                            |
| `NIFI_AUTHORIZATION_BEARER_COOKIE` | `--auth cookie` | Value of the `__Secure-Authorization-Bearer` cookie, without the name. |

## Detected processors

Processors that read data are inbound, and processors that write data are outbound. HandleHttpResponse is not listed,
because it answers the request its HandleHttpRequest already reports.

| Type     | Inbound                                                     | Outbound                                                  |
| -------- | ----------------------------------------------------------- | --------------------------------------------------------- |
| REST     | HandleHttpRequest, ListenHTTP, GetHTTP                      | InvokeHTTP, PostHTTP                                      |
| Kafka    | ConsumeKafka, ConsumeKafkaRecord, with any version suffix   | PublishKafka, PublishKafkaRecord, with any version suffix |
| S3       | ListS3, FetchS3Object, GetS3ObjectMetadata, GetS3ObjectTags | PutS3Object, DeleteS3Object, CopyS3Object, TagS3Object    |
| RabbitMQ | ConsumeAMQP                                                 | PublishAMQP                                               |

The Kafka version suffixes are `_1_0`, `_2_0`, and `_2_6`. A processor whose type is not in the table is not reported.

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
order REST, Kafka, S3, RabbitMQ. A subsection with no APIs says `None found.`:

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

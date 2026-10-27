# StandardHttpRouterService benchmark

This benchmark measures the response time of qubership-nifi's StandardHttpRouterService with HandleRoutedHttpRequest
processors and compares it with the Apache NiFi HandleHttpRequest processor under the same load. HandleHttpRequest
listens on its own port in every flow. StandardHttpRouterService listens on one port for all flows and routes each
request to a HandleRoutedHttpRequest processor by its path.

## Prerequisites

- A qubership-nifi image with the `qubership-nifi-http-router-api-nar`, `qubership-nifi-http-router-nar`, and
  `qubership-nifi-http-router-processors-nar` bundles. The NiFi container must publish these ports:

  | Port        | Used by                                                |
  |-------------|--------------------------------------------------------|
  | 11001-11020 | HandleHttpRequest, one port per chain                  |
  | 12981       | StandardHttpRouterService                              |
  | 9192        | ComponentPrometheusReportingTask, only with Prometheus |

- Python 3 on the host that sends the requests. The script uses only the standard library.
- Optional: Prometheus, to collect NiFi metrics during a run, such as active threads, queued FlowFiles, and garbage
  collection.

## Running the benchmark

1. Start NiFi.
2. Upload one of the process groups from `flows/` to the NiFi canvas. The groups are tested one at a time, and only
   the group under test is on the canvas during a run.
3. Optional: set up Prometheus.
   1. In NiFi, add ComponentPrometheusReportingTask and start it. It serves metrics on port 9192.
   2. Add a scrape job for NiFi to `prometheus.yml`:

      ```yaml
      scrape_configs:
        - job_name: "nifi"
          static_configs:
            - targets: ["localhost:9192"]
      ```

   3. Start Prometheus. The `nifi` target on the Targets page must be `UP`.
4. In the process group, enable the controller services and start all processors.
5. Run the script. If the HandleHttpRequest group is on the canvas, send requests to it every 100 ms for 10 minutes:

   ```bash
   python dev/standard-http-router-service-benchmark/handle_http_request_load.py 100 10
   ```

   If the HandleRoutedHttpRequest group is on the canvas, send the same requests to it through
   StandardHttpRouterService:

   ```bash
   python dev/standard-http-router-service-benchmark/handle_http_request_load.py 100 10 --router-port 12981
   ```

6. Stop the process group and delete it from the canvas. Then upload the other process group and repeat steps 4
   and 5.

Every `interval_ms`, the script sends one batch: one POST request to each of the 20 chains, in parallel, so both
groups get the same number of requests per interval. The body is JSON, with `payload` holding `--payload-size`
characters:

```json
{"chain": 7, "status": "new", "payload": "xxxx..."}
```

For each response, the script checks that it holds `"status": "processed by chain N"` for the chain it called. After
`duration_min`, the script stops sending batches and waits for the responses to the requests already sent. For a
quick check of the setup, run `handle_http_request_load.py 1000 1`.

`handle_http_request_load.py <interval_ms> <duration_min> [options]` reads these arguments:

| Argument         | Default     | Description                                                                                              |
|------------------|-------------|----------------------------------------------------------------------------------------------------------|
| `interval_ms`    | required    | Interval between request batches, in milliseconds.                                                       |
| `duration_min`   | required    | How long the script sends batches, in minutes.                                                           |
| `--router-port`  | not set     | Port of StandardHttpRouterService. When set, chain N is called at `http://<host>:<port>/chainNN`.        |
| `--host`         | `127.0.0.1` | NiFi host.                                                                                               |
| `--first-port`   | `11001`     | Without `--router-port`, chain N is called at `http://<host>:<first-port + N - 1>/`.                     |
| `--count`        | `20`        | Number of chains, and number of requests in each batch.                                                  |
| `--timeout`      | `70`        | Response timeout, in seconds. It is longer than the 1-minute Request Expiration of the HTTP Context Map. |
| `--payload-size` | `1024`      | Length of the `payload` text in the request body, in characters.                                         |
| `--log`          | see below   | CSV log file.                                                                                            |

The default host is `127.0.0.1` rather than `localhost`. On Windows, `localhost` resolves to `::1` first, and when the
ports are published on `127.0.0.1` only, every request waits about 2 seconds for the IPv6 attempt to fail.

The script runs the requests in a pool of at most 500 threads. When every thread waits for a response, new requests
wait in the pool queue and start later than scheduled, so the run lasts longer than `duration_min`. The time a request
waits in the pool queue is not part of `duration_ms`.

Each run writes `results/http_load_<mode>_<interval_ms>ms_<timestamp>.csv`, where the mode is `ports` for
HandleHttpRequest and `router` with `--router-port`. The file has one row per request, separated by `;`, and the
script prints the same rows to standard output:

| Column          | Content                                                                                                         |
|-----------------|-----------------------------------------------------------------------------------------------------------------|
| `chain`         | Chain number, 1 to `--count`                                                                                    |
| `url`           | Request URL                                                                                                     |
| `sent_at`       | Local time the request started, with milliseconds                                                               |
| `received_at`   | Local time the response was read or the request failed                                                          |
| `duration_ms`   | Time from sending the request to reading the whole response                                                     |
| `response_code` | HTTP status code, or `ERROR <exception>: <message>` when no response arrived                                    |
| `check`         | `ok` when the response holds `"status": "processed by chain N"` for the chain called, `mismatch` otherwise, empty on an error |

The script prints its progress to standard error:

| Message                                                      | Printed when                                                               |
|--------------------------------------------------------------|----------------------------------------------------------------------------|
| `Started at <time>: mode ..., interval ..., duration ...`    | Before the first batch                                                     |
| `Sending stopped at <time>, waiting for requests in flight`  | `duration_min` has passed or Ctrl+C was pressed; no new batches are sent  |
| `Finished at <time>`                                         | Every request sent has a response or has failed                            |
| `Log written to <file>`                                      | After `Finished`                                                           |

## Results

**Note:** all results were measured with Docker Desktop 27.4.0 on Windows 11, with 4 CPUs and 9.7 GiB of memory
available to containers, NiFi 2.12.0 in the qubership-nifi image, and OpenJDK 25.0.4.1. Each run used 20 chains and
`duration_min` of 10.

| Interval | Component                 | Requests | Mean, ms | Median, ms | Min, ms | Max, ms |
|----------|---------------------------|----------|----------|------------|---------|---------|
| 100 ms   | HandleHttpRequest         | 120000   | 53.7     | 52.3       | 7.5     | 813.9   |
| 100 ms   | StandardHttpRouterService | 120000   | 52.2     | 52.2       | 6.3     | 271.6   |
| 50 ms    | HandleHttpRequest         | 240000   | 1281.5   | 1294.3     | 69.9    | 1943.6  |
| 50 ms    | StandardHttpRouterService | 240000   | 1269.2   | 1277.7     | 32.9    | 2391.0  |
| 25 ms    | HandleHttpRequest         | 480000   | 1297.6   | 1293.0     | 27.1    | 2787.3  |
| 25 ms    | StandardHttpRouterService | 480000   | 1303.6   | 1301.8     | 1.4     | 3221.3  |

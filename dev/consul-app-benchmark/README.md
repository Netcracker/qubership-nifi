# Consul app JVM benchmark

This benchmark measures the memory footprint and startup time of qubership-nifi's auxiliary application for Consul
integration. It covers both implementations that `NIFI_CONSUL_INT_FRAMEWORK` selects, `spring` and `quarkus`, and
compares default JVM ergonomics with tuned `CONSUL_CONFIG_JAVA_OPTIONS` values.

## Recommended JVM options

Set `CONSUL_CONFIG_JAVA_OPTIONS` to the following value. It works for both `spring` and `quarkus`:

```text
-XX:+UseSerialGC -Xms16m -Xmx64m -XX:MaxMetaspaceSize=64m -XX:CompressedClassSpaceSize=16m -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -XX:ReservedCodeCacheSize=24m -Xss512k -XX:MaxDirectMemorySize=16m -XX:+UseCompactObjectHeaders
```

Compared with default JVM options, in the 10-minute run with configuration changes every 30 seconds:

| Framework | Peak RSS, default | Peak RSS, recommended | Main execution time, default | Main execution time, recommended |
|-----------|-------------------|-----------------------|------------------------------|----------------------------------|
| spring    | 219 MiB           | 157 MiB               | 3.12 s                       | 2.35 s                           |
| quarkus   | 171 MiB           | 96 MiB                | 0.82 s                       | 0.77 s                           |

The options do the following:

| Option                                                                        | Effect                                                                                                                                                                                             |
|-------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `-XX:+UseSerialGC`                                                            | Serial GC. With default options, the JVM picks G1, whose native data structures took 54 to 56 MiB in both apps. Serial GC needs less than 1 MiB.                                                   |
| `-Xms16m -Xmx64m`                                                             | Heap limit. The largest live heap after a collection was 28 MiB for `spring` and 8 MiB for `quarkus`.                                                                                              |
| `-XX:MaxMetaspaceSize=64m -XX:CompressedClassSpaceSize=16m`                   | Class metadata limit. Peak metaspace use was 43.6 MiB for `spring` and 24 MiB for `quarkus`.                                                                                                       |
| `-XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -XX:ReservedCodeCacheSize=24m` | C1 compiler only, in one thread. Peak code cache use was 8.9 MiB for `spring` and 4.8 MiB for `quarkus`. A 3 MiB code cache made `spring` startup more than twice as slow, see the tuning history. |
| `-Xss512k -XX:MaxDirectMemorySize=16m`                                        | Smaller thread stacks and a direct buffer limit.                                                                                                                                                   |
| `-XX:+UseCompactObjectHeaders`                                                | Smaller object headers. Requires Java 25 or later.                                                                                                                                                 |

Without these options, the JVM sizes the heap from the memory it can see. In a NiFi container, that is the container
memory limit, which is sized for NiFi, so the default maximum heap of the Consul app is a quarter of the memory meant
for NiFi.

The JVM refuses to start when two garbage collectors are selected. `JAVA_TOOL_OPTIONS` applies to every JVM in the
container, the Consul app included, and when `X_JAVA_ARGS` is set, the base image entrypoint
(`/usr/bin/entrypoint.sh`) copies it into `JAVA_TOOL_OPTIONS`. If either variable selects a collector, leave
`-XX:+UseSerialGC` out of `CONSUL_CONFIG_JAVA_OPTIONS`.

With the recommended options, both apps used less CPU time than with default options, and `spring` started faster.
These results are for the options as a set; the effect of each option was not measured on its own.

## Results

**Note:** all results on this page were measured with Podman 6.0.2 in a WSL-based Podman machine on Windows 11, with
12 CPUs and 7.6 GiB of memory available to containers, Amazon Corretto 25.0.4.1 from the container base image, and
commit `e6f30b34`.

All sizes are in MiB. Each profile had 5 cold starts and one 10-minute run with 20 change rounds. Every run passed:
no `OutOfMemoryError`, and `logback.xml` held the new logger levels within 7 seconds of the start of each change
round. The table is the output of `report.sh`.

| Framework | Profile     | Result | Main execution time, s: median (min-max) | Startup time logged, s | Peak RSS | NMT committed at exit | Live heap after GC, max / heap committed, peak | Metaspace used / committed | Code cache used / committed | Threads | CPU time, s | GC pauses (full), total time | logback.xml update, max s |
|-----------|-------------|--------|------------------------------------------|------------------------|----------|-----------------------|------------------------------------------------|----------------------------|-----------------------------|---------|-------------|------------------------------|---------------------------|
| spring    | baseline    | pass   | 3.12 (3.02-3.38)                         | 2.41                   | 218.5    | 198.9                 | 20 / 124                                       | 38.6 / 39                  | 19 / 29                     | 40      | 16.44       | 33 (0), 115.6 ms             | 7                         |
| spring    | tuned-v1    | pass   | 2.25 (2.14-2.35)                         | 1.76                   | 155.1    | 111                   | 26 / 36.7                                      | 43.6 / 44.1                | 8.9 / 13.9                  | 22      | 5.99        | 97 (3), 229.6 ms             | 7                         |
| spring    | tuned-v2    | pass   | 2.19 (2.09-2.3)                          | 1.76                   | 153.8    | 109.4                 | 24 / 36.3                                      | 43.6 / 44.1                | 8.4 / 13.2                  | 22      | 5.77        | 96 (6), 339.3 ms             | 7                         |
| spring    | recommended | pass   | 2.35 (2.14-2.61)                         | 1.87                   | 157.3    | 112.9                 | 28 / 38.9                                      | 43.6 / 44.1                | 8.9 / 13.9                  | 22      | 7.87        | 97 (3), 279.8 ms             | 7                         |
| quarkus   | baseline    | pass   | 0.82 (0.82-1.13)                         | 0.64                   | 171      | 233.1                 | 9 / 124                                        | 16.8 / 17.1                | 9.3 / 14.9                  | 39      | 5.2         | 3 (0), 18.1 ms               | 7                         |
| quarkus   | tuned-v1    | pass   | 0.77 (0.71-0.82)                         | 0.55                   | 97       | 61                    | 8 / 19.2                                       | 24 / 24.3                  | 4.8 / 7.1                   | 24      | 2.7         | 35 (2), 63.1 ms              | 7                         |
| quarkus   | tuned-v2    | pass   | 0.77 (0.77-0.92)                         | 0.57                   | 96.9     | 60.5                  | 8 / 18.7                                       | 24 / 24.3                  | 4.8 / 7.1                   | 24      | 2.82        | 36 (3), 87.6 ms              | 7                         |
| quarkus   | recommended | pass   | 0.77 (0.77-0.92)                         | 0.57                   | 95.8     | 57.8                  | 8 / 16.1                                       | 24 / 24.3                  | 4.8 / 7.1                   | 23      | 2.79        | 36 (1), 65.8 ms              | 6                         |

The `spring/recommended` profile holds the same options as `spring/tuned-v1`. Differences in main execution time of up
to about 0.5 seconds are within the spread between runs of one profile.

### Tuning history

| Profile            | Limits                                                                                                | Outcome                                                                                                                                                                                                                                                                                                              |
|--------------------|-------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `tuned-v1`         | `spring`: heap 64m, metaspace 64m, code cache 24m. `quarkus`: heap 32m, metaspace 40m, code cache 16m | Passed. Peak RSS dropped by 63 MiB (`spring`) and 74 MiB (`quarkus`).                                                                                                                                                                                                                                                |
| `tuned-v2`         | `spring`: heap 48m, metaspace 56m, code cache 16m. `quarkus`: heap 24m, metaspace 32m, code cache 12m | Passed, with the same peak RSS as `tuned-v1`. Serial GC commits heap as the live set grows, so the lower limits saved no memory. They cut the metaspace headroom of `spring` to about 12 MiB, and for `spring` the 16 MiB code cache raised the full collections that `CodeCache GC Threshold` triggers from 1 to 5. |
| `recommended`      | The `spring` `tuned-v1` limits for both frameworks                                                    | Passed for both frameworks. `quarkus` had the same footprint as with its own `tuned-v1`, so one value serves both.                                                                                                                                                                                                   |
| `undersized`       | Heap 12m and metaspace 24m (`spring`), heap 8m and metaspace 12m (`quarkus`)                          | Negative control. Both apps failed at startup with `java.lang.OutOfMemoryError: Metaspace`, and the harness reported the runs as failed.                                                                                                                                                                             |
| `small-code-cache` | The recommended options with a 3 MiB code cache, `spring` only                                        | Control. The JVM printed no `CodeCache is full` warning: it unloaded compiled code in 787 full collections instead. Main execution time was 5.78 s, against 2.35 s with a 24 MiB code cache. The harness does not treat this as a failure.                                                                           |

## Running the benchmark

Prerequisites:

- Docker with Compose v2, or Podman with a provider for `podman compose`, such as `docker-compose`. Also `bash` and
  `jq`. On Windows, use Git Bash. The scripts call the Consul API with `curl` inside the Consul container, so no port
  is published on the host.
- About 25 minutes per profile for both frameworks.
- Both apps built from the repository root:

  ```bash
  mvn install -pl qubership-consul/qubership-consul-application,qubership-nifi-quarkus-consul/qubership-nifi-quarkus-consul-application -am -DskipUnitTests=true
  ```

Run one framework and one profile:

```bash
bash dev/consul-app-benchmark/run-benchmark.sh quarkus recommended
```

Run profiles for both frameworks, one run after another, and print a comparison table:

```bash
bash dev/consul-app-benchmark/run-all.sh baseline recommended
```

Rebuild the comparison table from existing results:

```bash
bash dev/consul-app-benchmark/report.sh dev/consul-app-benchmark/results/*
```

A profile is a file `profiles/<framework>/<profile>.opts` with one JVM option per line; `#` starts a comment. The
options in `profiles/measurement.opts` are added to every profile.

`run-benchmark.sh` reads these environment variables:

| Variable           | Default  | Description                                                                                                  |
|--------------------|----------|--------------------------------------------------------------------------------------------------------------|
| `COLD_RUNS`        | `5`      | Number of cold starts.                                                                                       |
| `SOAK_MINUTES`     | `10`     | Length of the run with configuration changes.                                                                |
| `ROUND_INTERVAL`   | `30`     | Seconds between change rounds.                                                                               |
| `SAMPLE_INTERVAL`  | `10`     | Seconds between memory samples.                                                                              |
| `SKIP_BUILD`       | `false`  | Set to `true` to reuse the `consul-app-benchmark:<framework>` image instead of rebuilding it from `target/`. |
| `CONTAINER_ENGINE` | `docker` | The container engine CLI, `docker` or `podman`.                                                              |

For a quick check of the harness, run `COLD_RUNS=1 SOAK_MINUTES=1`.

Each run writes `results/<framework>-<profile>-<timestamp>/`:

| File                           | Content                                                                                                          |
|--------------------------------|------------------------------------------------------------------------------------------------------------------|
| `summary.md`, `summary.json`   | The metrics in the results table                                                                                 |
| `failures.txt`                 | Why the run failed; empty for a passed run. `run-benchmark.sh` exits with code 1 when it is not empty            |
| `environment.txt`, `flags.txt` | Base image, commit, container engine version and resources, and the effective JVM flags (`-XX:+PrintFlagsFinal`) |
| `cold-<n>/`, `soak/`           | Application log with the exit statistics, GC log, code cache log, container exit state                           |
| `soak/samples.csv`             | RSS, threads, CPU ticks, heap, metaspace, and code cache every `SAMPLE_INTERVAL` seconds                         |
| `soak/rounds.txt`              | For each change round, the seconds from its start until `logback.xml` held the new levels                        |

## Method

### Environment

- The app runs in a container built from `ghcr.io/netcracker/qubership-java-base`, with the tag and digest pinned in
  the repository `Dockerfile`, under the image entrypoint, as in the qubership-nifi image. The container has no memory
  limit, so only the JVM options differ between runs.
- Consul is `hashicorp/consul:1.22`, the image the autotests in `.github/docker` use.

### Test data

`seed-consul.sh` writes 1000 keys under `config/local/`:

| Keys | Location                                  | Read by the app                                                                                        |
|------|-------------------------------------------|--------------------------------------------------------------------------------------------------------|
| 300  | `application/bench.unrelated.prop-NNN`    | Watched, then filtered out: no `logger.` or `nifi.` prefix                                             |
| 100  | `qubership-nifi/bench.unrelated.prop-NNN` | Watched, then filtered out                                                                             |
| 400  | `bench-other-service-NN/prop-NNN`         | No                                                                                                     |
| 170  | `application/logger.org.bench...`         | Yes, into `logback.xml`. Every fifth key uses the `logger/org/bench/...` form                          |
| 10   | `application/nifi.*`                      | Yes: the 9 keys the apps write to `custom.properties`, and `nifi.security.identity.mapping.pattern.dn` |
| 20   | `application/nifi.*`                      | Yes, into `nifi.properties`                                                                            |

### Scenario

1. Start Consul and write the keys.
2. Cold-start the app `COLD_RUNS` times. Each start runs until the app writes `/tmp/initial-config-completed.txt`, the
   file `nifi-scripts/start.sh` waits for. The first start also checks the seeded values in `nifi.properties`,
   `custom.properties`, and `logback.xml`.
3. Start the app once more and run the change rounds. Each round sends two Consul transactions: one moves 50 loggers
   to another level, the other gives 50 watched unrelated keys new values. After each round, the harness waits up to
   25 seconds for `logback.xml` to hold the new levels of three sampled loggers.
4. Stop the app with `docker stop` or `podman stop`. The JVM prints its native memory and code cache statistics as it
   exits.

### Metrics

| Metric                | Source                                                                                               |
|-----------------------|------------------------------------------------------------------------------------------------------|
| Main execution time   | From the launch of `java` to the creation of `/tmp/initial-config-completed.txt`                     |
| Startup time logged   | `Started NifiPropertiesLookup in` (`spring`) or `started in` (`quarkus`) in the application log      |
| Peak RSS              | `VmHWM` of the JVM process at the end of the run with change rounds                                  |
| NMT committed at exit | Total committed memory in the `-XX:NativeMemoryTracking=summary` report                              |
| Heap                  | Largest heap size after a collection in the GC log; committed peak from the `Java Heap` NMT category |
| Metaspace             | Largest used and committed values from the GC log and `jstat -gc` samples, class space included      |
| Code cache            | `max_used` from `-XX:+PrintCodeCache`; committed peak from the `Code` NMT category                   |
| Threads, CPU time     | `/proc/<pid>/status` and `/proc/<pid>/stat` of the JVM process                                       |
| GC pauses             | Pause lines in the GC log; the number in parentheses counts `Pause Full`                             |
| logback.xml update    | Largest value in `soak/rounds.txt`                                                                   |

Native memory tracking adds a few MiB of its own to every run, the baseline included.

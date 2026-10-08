# ComponentPrometheusReportingTask

The ComponentPrometheusReportingTask sends metrics of NiFi components (Processors, Connections, Process Groups) to
Prometheus.

## Metrics over the last 5 minutes

Each of these metrics is a total over the last 5 minutes, as of the last run of the reporting task. It is not a counter
since startup, so do not apply `rate()` or `increase()` to it.

### Processor metrics

- `nc_nifi_processor_tasks_time_total`
- `nc_nifi_processor_tasks_count`

### Processing performance metrics

The values of these metrics depend on the NiFi property `nifi.performance.tracking.percentage`. By default, the property
is `0` and performance tracking is disabled. For details, refer to the `Processing performance metrics` section of the
qubership-nifi Administrator's Guide.

Metrics of a Processor:

- `nc_nifi_processor_cpu_duration`
- `nc_nifi_processor_content_read_duration`
- `nc_nifi_processor_content_write_duration`
- `nc_nifi_processor_session_commit_duration`
- `nc_nifi_processor_gc_duration`

Metrics of a Process Group:

- `nc_nifi_pg_cpu_duration`
- `nc_nifi_pg_content_read_duration`
- `nc_nifi_pg_content_write_duration`
- `nc_nifi_pg_session_commit_duration`
- `nc_nifi_pg_gc_duration`

The value of a process group includes the processors of all its child groups, so adding up the values of a group and
its child groups counts the same processors more than once.

## Other metrics

The values of these metrics are not totals over the last 5 minutes:

- Connection, Process Group, and Root Process Group metrics, except the bulletin metrics of a Process Group, are
  current values as of the last run of the reporting task.
- `nc_nifi_bulletin_count` and `nc_nifi_pg_bulletin_count` are the numbers of bulletins since the previous run of the
  reporting task.
- `nc_nifi_bulletin_cnt_total` and `nc_nifi_pg_bulletin_cnt_total` are counters, so `rate()` and `increase()` apply to
  them.

### Connection metrics

- `nc_nifi_connection_queued_count`
- `nc_nifi_connection_queued_bytes`
- `nc_nifi_connection_percent_used_count`
- `nc_nifi_connection_percent_used_bytes`

### Process Group metrics

- `nc_nifi_pg_component_count`
- `nc_nifi_pg_bulletin_count`
- `nc_nifi_pg_bulletin_cnt_total`
- `nc_nifi_pg_active_thread_count`
- `nc_nifi_pg_queued_count`
- `nc_nifi_pg_queued_bytes`

The values of `nc_nifi_pg_component_count`, `nc_nifi_pg_active_thread_count`, `nc_nifi_pg_queued_count`, and
`nc_nifi_pg_queued_bytes` include all child groups of the process group.

### Root Process Group metrics

- `nifi_amount_threads_active`
- `nifi_amount_items_queued`
- `nifi_size_content_queued_total`

### Bulletin metrics

- `nc_nifi_bulletin_count`
- `nc_nifi_bulletin_cnt_total`

### JVM metrics

- `nifi_jvm_thread_count`
- `nifi_jvm_uptime`
- `nifi_jvm_heap_usage`
- garbage collector metrics with the prefix `nifi_jvm_gc_`
- Micrometer JVM metrics with the prefix `jvm_`

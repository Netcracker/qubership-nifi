# QubershipPrometheusRecordSink

The QubershipPrometheusRecordSink is a NiFi Controller Service that exposes metrics to Prometheus. 
The service collects metrics from incoming records and uses an embedded Jetty server to expose metrics at the `/metrics` endpoint on a configurable port. 
Incoming records may have different schemas; the service dynamically handles varying schemas by processing each record based on its actual fields and types at runtime.

## Field to Metric Mapping

The service maps record fields to Prometheus metrics by interpreting:
1. String or char fields as labels.
2. Numeric (int, short, long, BigInt, double, float, decimal), boolean, and choice fields (provided they can be converted to a numeric value) as gauges.
3. Nested record fields with specific structure as either counters or distribution summaries based on a specified `type` field. Each nested record must contain `type` and `value` fields to be processed correctly.

All other field types not listed above are ignored and not included in the metrics. 
String, numeric, and boolean fields with null values are also ignored. 
Field names are used as metric names in Prometheus for numeric and nested record fields, and as label names for string fields.

Incoming records may contain multiple fields; all applicable fields are processed to generate the corresponding Prometheus metrics. 
Labels are applied to all metrics generated from the same record.

## Static Labels

The QubershipPrometheusRecordSink automatically adds the following static labels to all metrics:
- `instance`: The value configured in the "Instance ID" property of the service.
- `hostname`: The hostname of the NiFi instance, obtained as the FQDN of the localhost network interface.
- `namespace`: The Kubernetes namespace of the NiFi instance, obtained from the `NAMESPACE` environment variable.

If any of these labels are already present in the incoming records, their values will be overridden by the static label values.

## Nested Record Structure

Nested records intended to be interpreted as counters or distribution summaries must have the following structure:
- `type`: A string field indicating the metric type, either `Counter` or `Summary`.
- `value`: A numeric field representing the metric value.
- `publishPercentiles`: (optional, for Summary type) An array of numeric values indicating the percentiles to be published. The 95th percentile should be expressed as `0.95`.
- `statisticExpiry`: (optional, for Summary type) A string field indicating the time period over which samples are accumulated to a histogram before it is reset and rotated. The value must be in ISO-8601 duration format (e.g., "PT1M" for 1 minute).
- `statisticBufferLength`: (optional, for Summary type) An integer field indicating the number of histograms to keep in the ring buffer for the summary statistic.

For example, a nested record for a counter might look like:
```json
{
    "request_count": {
        "type": "Counter",
        "value": 2.5
    }
}
```
This example indicates that the counter metric `request_count` should be incremented by 2.5.

For a distribution summary, it might look like:
```json
{
    "response_time": {
        "type": "Summary",
        "value": 150,
        "publishPercentiles": [0.5, 0.95, 0.99],
        "statisticExpiry": "PT5M",
        "statisticBufferLength": 5
    }
}
```
In this example, the distribution summary metric `response_time` records a value of 150. 
If this metric (identified by its name `response_time` and all its labels) does not already exist, it is created with the specified percentiles and histogram settings. 
If the metric already exists, `publishPercentiles`, `statisticExpiry`, and `statisticBufferLength` settings are ignored.

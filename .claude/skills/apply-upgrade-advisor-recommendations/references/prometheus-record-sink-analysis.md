## PrometheusRecordSink upgrade analysis

Ask the user what target type, bundle, and property mapping to use for the upgrade, showing these defaults:

```python
new_type   = "org.qubership.nifi.service.QubershipPrometheusRecordSink"
new_bundle = {"group": "org.qubership.nifi", "artifact": "qubership-service-nar", "version": "1.0.7"}
prop_map   = {
    "prometheus-reporting-task-metrics-endpoint-port": "prometheus-sink-metrics-endpoint-port",
    "prometheus-reporting-task-instance-id":           "prometheus-sink-instance-id",
    "prometheus-reporting-task-ssl-context":           None,  # dropped
    "prometheus-reporting-task-client-auth":           None,  # dropped
}
```

If the user accepts the defaults, use them as-is.
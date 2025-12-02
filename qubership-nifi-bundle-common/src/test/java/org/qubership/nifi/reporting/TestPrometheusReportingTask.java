package org.qubership.nifi.reporting;

import io.micrometer.core.instrument.Gauge;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.nifi.reporting.ReportingContext;

public class TestPrometheusReportingTask
        extends AbstractPrometheusReportingTask {
    private final MutablePair<Double, Double> metricValue;

    public TestPrometheusReportingTask() {
        this.metricValue = new MutablePair<>();
    }

    @Override
    public void registerMetrics(ReportingContext context) {
        Gauge.builder("test_metric_name001", metricValue, Pair::getLeft)
                .tags("tag1", "tagValue1", "tag2", "tagValue2",
                        "instance", getInstance(), "hostname", getHostname(), "namespace", getNamespace())
                .register(this.getGenericMeterRegistry());
        Gauge.builder("test_metric_name002", metricValue, Pair::getRight)
                .tags("tag1", "tagValue1", "tag2", "tagValue2")
                .register(this.getGenericMeterRegistry());
    }

    public MutablePair<Double, Double> getMetricValue() {
        return metricValue;
    }
}

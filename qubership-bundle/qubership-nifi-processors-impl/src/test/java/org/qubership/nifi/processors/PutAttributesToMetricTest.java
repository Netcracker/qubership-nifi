package org.qubership.nifi.processors;

import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.service.QubershipPrometheusRecordSink;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.nifi.processors.PutAttributesToMetric.RECORD_SINK;

public class PutAttributesToMetricTest {

    private TestRunner testRunner;
    private QubershipPrometheusRecordSink recordSink;

    /**
     * Method for initializing the PutAttributesToMetric test processor.
     *
     * @throws InitializationException
     */
    @BeforeEach
    public void init() throws InitializationException {
        recordSink = new QubershipPrometheusRecordSink();
        final Map<String, String> recordSinkProperties = new HashMap<>();
        recordSinkProperties.put("prometheus-sink-instance-id", "test-instance-id");
        testRunner = TestRunners.newTestRunner(PutAttributesToMetric.class);

        testRunner.setValidateExpressionUsage(false);
        testRunner.setProperty(RECORD_SINK, "recordSink");
        testRunner.addControllerService("recordSink", recordSink);

        testRunner.enableControllerService(recordSink);
    }

    @Test
    public void testSimpleData() throws Exception {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("testAttr1", "25.3");
        attrs.put("testAttr2", "2");
        testRunner.setProperty("test_metric", "${testAttr1}");
        testRunner.enqueue("", attrs);
        testRunner.run();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(BackupAttributes.REL_SUCCESS);
        assertEquals(1, result.size());
        recordSink.meterRegistry.getMeters().get(0).getId().toString();
    }
}

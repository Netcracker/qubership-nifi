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
import static org.qubership.nifi.processors.PutAttributesToMetric.LIST_JSON_DYNAMIC_PROPERTY;
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

        //TODO: add установка namespace, hostname
        //delete using QubershipPrometheusRecordSink
        recordSink = new QubershipPrometheusRecordSink();
        final Map<String, String> recordSinkProperties = new HashMap<>();
        testRunner = TestRunners.newTestRunner(PutAttributesToMetric.class);

        testRunner.setValidateExpressionUsage(false);
        testRunner.setProperty(RECORD_SINK, "recordSink");
        testRunner.addControllerService("recordSink", recordSink);
        testRunner.setProperty(recordSink, QubershipPrometheusRecordSink.INSTANCE_ID, "test-instance");

        testRunner.enableControllerService(recordSink);
        testRunner.assertValid(recordSink);
    }

    @Test
    public void testSimpleData() throws Exception {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("testAttr1", "25.3");
        attrs.put("testAttr2", "2");
        testRunner.setProperty("test_metric1", "${testAttr1}");
        testRunner.setProperty("test_metric2", "${testAttr2}");
        testRunner.enqueue("", attrs);
        testRunner.run();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(BackupAttributes.REL_SUCCESS);
        assertEquals(1, result.size());
        recordSink.meterRegistry.getMeters().get(0).getId().toString();

        //TODO: add check for lables

        assertEquals(2, recordSink.meterRegistry.getMeters().size());
        assertEquals(25.3, recordSink.meterRegistry.getMeters().get(0).measure().iterator().next().getValue());
        assertEquals(2, recordSink.meterRegistry.getMeters().get(1).measure().iterator().next().getValue());
    }

    @Test
    public void testJsonDynamicProperty() throws Exception {
        String jsonObject = "{\n" +
                "\t\"size\": ${size},\n" +
                "\t\"endpoint\": \"${endpoint}\",\n" +
                "\t\"method\": \"${method}\",\n" +
                "\t\"status\": \"${status}\"\n" +
                "}";

        Map<String, String> attrs = new HashMap<>();
        attrs.put("size", "700");
        attrs.put("endpoint", "/api/data");
        attrs.put("method", "GET");
        attrs.put("status", "200");

        testRunner.setProperty("response_size_bytes", jsonObject);
        testRunner.setProperty(LIST_JSON_DYNAMIC_PROPERTY, "response_size_bytes");
        testRunner.enqueue("", attrs);
        testRunner.run();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(BackupAttributes.REL_SUCCESS);
        assertEquals(1, result.size());
        assertEquals(1, recordSink.meterRegistry.getMeters().size());
    }
}

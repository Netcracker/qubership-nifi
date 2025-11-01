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
import static org.qubership.nifi.processors.PutRecordFromProperty.*;

public class PutRecordFromPropertyTest {

    private TestRunner testRunner;
    private QubershipPrometheusRecordSink recordSink;

    /**
     * Method for initializing the PutAttributesToMetric test processor.
     *
     * @throws InitializationException
     */
    @BeforeEach
    public void init() throws InitializationException {

        //delete using QubershipPrometheusRecordSink
        recordSink = new QubershipPrometheusRecordSink();
        testRunner = TestRunners.newTestRunner(PutRecordFromProperty.class);

        testRunner.setValidateExpressionUsage(false);
        testRunner.setProperty(RECORD_SINK, "recordSink");
        testRunner.addControllerService("recordSink", recordSink);

        testRunner.enableControllerService(recordSink);
        testRunner.assertValid(recordSink);
    }

    @Test
    public void testSimpleDynamicProperty() throws Exception {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("testAttr1", "25.3");
        attrs.put("testAttr2", "2");
        testRunner.setProperty("test_metric1", "${testAttr1}");
        testRunner.setProperty("test_metric2", "${testAttr2}");
        testRunner.enqueue("", attrs);
        testRunner.run();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());
    }

    @Test
    public void testJsonDynamicProperty() throws Exception {
        String jsonObject = "{\n" +
                "\"size\": ${size},\n" +
                "\"endpoint\": \"${endpoint}\",\n" +
                "\"method\": \"${method}\",\n" +
                "\"status\": \"${status}\"\n" +
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
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());
        assertEquals(0, recordSink.meterRegistry.getMeters().size());
    }

    @Test
    public void testStaticJson() throws Exception {
        String staticJsonObject = "{\n" +
                "    \"request_duration_seconds\": {\n" +
                "        \"type\": \"Summary\",\n" +
                "        \"quantiles\": [\n" +
                "            0.05, 0.12, 0.18, 0.45\n" +
                "        ],\n" +
                "        \"value\": ${value},\n" +
                "        \"method\": \"${method}\",\n" +
                "        \"endpoint\": \"${endpoint}\",\n" +
                "        \"timestamp\": \"${status}\"\n" +
                "    }\n" +
                "}";

        Map<String, String> attrs = new HashMap<>();
        attrs.put("value", "1200");
        attrs.put("endpoint", "/api/data");
        attrs.put("method", "GET");
        attrs.put("status", "200");

        testRunner.setProperty(METRIC_TYPE, STATIC_JSON);
        testRunner.setProperty(STATIC_JSON_OBJECT, staticJsonObject);
        testRunner.enqueue("", attrs);
        testRunner.run();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());
        assertEquals(1, recordSink.meterRegistry.getMeters().size());
    }

}

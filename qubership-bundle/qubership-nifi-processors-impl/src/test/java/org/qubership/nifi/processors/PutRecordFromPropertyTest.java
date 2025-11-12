package org.qubership.nifi.processors;

import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.service.MockRecordSinkService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.qubership.nifi.processors.PutRecordFromProperty.RECORD_SINK;
import static org.qubership.nifi.processors.PutRecordFromProperty.LIST_JSON_DYNAMIC_PROPERTY;
import static org.qubership.nifi.processors.PutRecordFromProperty.SOURCE_TYPE;
import static org.qubership.nifi.processors.PutRecordFromProperty.JSON_PROPERTY_OBJECT;

public class PutRecordFromPropertyTest {

    private TestRunner testRunner;
    private MockRecordSinkService recordSink;


    /**
     * Method for initializing the PutRecordFromProperty test processor.
     *
     * @throws InitializationException
     */
    @BeforeEach
    public void init() throws InitializationException {
        testRunner = TestRunners.newTestRunner(PutRecordFromProperty.class);

        recordSink = new MockRecordSinkService();
        testRunner.setValidateExpressionUsage(false);
        testRunner.addControllerService("recordSink", recordSink);
        testRunner.setProperty(RECORD_SINK, "recordSink");
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
        List<Map<String, Object>> row = recordSink.getRows();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());
        assertEquals("25.3", row.get(0).get("test_metric1"));
        assertEquals("2", row.get(0).get("test_metric2"));
    }

    @Test
    public void testComplexJsonDynamicProperty() throws Exception {
        String jsonObject = "{\n"
                + "\"size\": ${size},\n"
                + "\"endpoint\": \"${endpoint}\",\n"
                + "\"method\": \"${method}\",\n"
                + "\"status\": \"${status}\"\n"
                + "}";

        Map<String, String> attrs = new HashMap<>();
        attrs.put("size", "700");
        attrs.put("endpoint", "/api/data");
        attrs.put("method", "GET");
        attrs.put("status", "200");

        testRunner.setProperty("response_size_bytes", jsonObject);
        testRunner.setProperty(LIST_JSON_DYNAMIC_PROPERTY, "response_size_bytes");
        testRunner.enqueue("", attrs);
        testRunner.run();
        List<Map<String, Object>> row = recordSink.getRows();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());
        assertEquals("MapRecord[{endpoint=/api/data, size=700.0, method=GET, status=200}]", row.get(0).get("response_size_bytes").toString());
    }

    @Test
    public void testComplexJsonProperty() throws Exception {
        String complexJson = "{\n"
                + "    \"request_duration_seconds\": {\n"
                + "        \"type\": \"Summary\",\n"
                + "        \"quantiles\": [\n"
                + "            0.05, 0.12, 0.18, 0.45\n"
                + "        ],\n"
                + "        \"value\": ${value},\n"
                + "        \"method\": \"${method}\",\n"
                + "        \"endpoint\": \"${endpoint}\",\n"
                + "        \"timestamp\": \"${status}\"\n"
                + "    }\n"
                + "}";

        Map<String, String> attrs = new HashMap<>();
        attrs.put("value", "1200");
        attrs.put("endpoint", "/api/data");
        attrs.put("method", "GET");
        attrs.put("status", "200");

        testRunner.setProperty(SOURCE_TYPE, SourceTypeValues.JSON_PROPERTY.getAllowableValue());
        testRunner.setProperty(JSON_PROPERTY_OBJECT, complexJson);
        testRunner.enqueue("", attrs);
        testRunner.run();
        List<Map<String, Object>> row = recordSink.getRows();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());
    }

    @Test
    public void testSimpleJsonProperty() {
        String simpleJsonProperty = "{\n" +
                "\"topLevelFieldName1\": 0.1,\n" +
                "\"topLevelFieldName2\": 0.2\n" +
                "}";

        testRunner.setProperty(SOURCE_TYPE, SourceTypeValues.JSON_PROPERTY.getAllowableValue());
        testRunner.setProperty(JSON_PROPERTY_OBJECT, simpleJsonProperty);
        testRunner.enqueue("");
        testRunner.run();
        List<Map<String, Object>> row = recordSink.getRows();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());
    }

    @Test
    public void testCombineDynamicPropertyAndJson() {
        String jsonMetric = "{\n"
                + "  \"value\": ${size},\n"
                + "  \"label1\": \"${label1}\",\n"
                + "  \"label2\": \"${label2}\"\n"
                + "}";

        Map<String, String> attrs = new HashMap<>();
        attrs.put("attr1", "11.3");
        attrs.put("attr2", "5");
        attrs.put("size", "123.45");
        attrs.put("label1", "production");
        attrs.put("label2", "api_server_01");

        testRunner.setProperty("attr1", "${attr1}");
        testRunner.setProperty("attr2", "${attr2}");
        testRunner.setProperty("json_metric", jsonMetric);
        testRunner.setProperty(LIST_JSON_DYNAMIC_PROPERTY, "jsonMetric");

        testRunner.enqueue("", attrs);
        testRunner.run();
        List<Map<String, Object>> row = recordSink.getRows();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());
        assertEquals("{\n  \"value\": 123.45,\n  \"label1\": \"production\",\n  \"label2\":"
                + " \"api_server_01\"\n}", row.get(0).get("json_metric").toString());
        assertEquals("11.3", row.get(0).get("attr1").toString());
        assertEquals("5", row.get(0).get("attr2").toString());
    }

    @Test
    public void testJsonPropertyWrongStructure() {
        String jsonWithArray = "{\n"
                + "  \"name\": \"http_requests_total\",\n"
                + "  \"value\": 12345,\n"
                + "  \"labels\": [\n"
                + "    \"method\",\n"
                + "    \"endpoint\",\n"
                + "    \"status\"\n"
                + "  ]\n"
                + "}";

        testRunner.setProperty(SOURCE_TYPE, SourceTypeValues.JSON_PROPERTY.getAllowableValue());
        testRunner.setProperty(JSON_PROPERTY_OBJECT, jsonWithArray);
        testRunner.enqueue("");
        assertThrows(AssertionError.class, () -> {
            testRunner.run();
        });
    }

    @Test
    public void testJsonPropertyInvalidJson() {
        String invalidJson = "{\n"
                + "\"size\": ${size},\n"
                + "\"endpoint\": \"${endpoint}\"\n"
                + "\"method\": \"${method}\",\n"
                + "\"status\": \"${status}\"\n"
                + "}";

        Map<String, String> attrs = new HashMap<>();
        attrs.put("size", "35");
        attrs.put("endpoint", "/api/test");
        attrs.put("method", "POST");
        attrs.put("status", "404");

        testRunner.setProperty("response_size_bytes", invalidJson);
        testRunner.setProperty(LIST_JSON_DYNAMIC_PROPERTY, "response_size_bytes");
        testRunner.enqueue("", attrs);
        assertThrows(AssertionError.class, () -> {
            testRunner.run();
        });
    }

}

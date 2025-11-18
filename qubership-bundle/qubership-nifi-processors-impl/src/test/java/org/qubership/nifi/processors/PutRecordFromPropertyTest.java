package org.qubership.nifi.processors;

import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.service.MockRecordSinkService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

        List<RecordField> fieldsTypes = new ArrayList<>();
        fieldsTypes.add(new RecordField("test_metric1", RecordFieldType.DOUBLE.getDataType()));
        fieldsTypes.add(new RecordField("test_metric2", RecordFieldType.DOUBLE.getDataType()));
        RecordSchema recordSchema = new SimpleRecordSchema(fieldsTypes);

        Map<String, Object> fieldValues = new HashMap<>();
        fieldValues.put("test_metric1", 25.3);
        fieldValues.put("test_metric2", 2.0);
        MapRecord expectMapRecord = generateRecord(recordSchema, fieldValues);

        testRunner.enqueue("", attrs);
        testRunner.run();
        List<Map<String, Object>> row = recordSink.getRows();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());
        assertEquals(expectMapRecord.getValue("test_metric1"), row.get(0).get("test_metric1"));
        assertEquals(expectMapRecord.getValue("test_metric2"), row.get(0).get("test_metric2"));
    }

    @Test
    public void testComplexJsonDynamicProperty() throws Exception {
        String jsonObject = """
              {
                   "size": ${size},
                   "endpoint": "${endpoint}",
                   "method": "${method}",
                   "success": ${success},
                   "status": "${status}"
               }
              """;

        Map<String, String> attrs = new HashMap<>();
        attrs.put("size", "700.0");
        attrs.put("endpoint", "/api/data");
        attrs.put("method", "GET");
        attrs.put("success", "true");
        attrs.put("status", "200");

        List<RecordField> fieldsTypes = new ArrayList<>();
        fieldsTypes.add(new RecordField("size", RecordFieldType.DOUBLE.getDataType()));
        fieldsTypes.add(new RecordField("endpoint", RecordFieldType.STRING.getDataType()));
        fieldsTypes.add(new RecordField("method", RecordFieldType.STRING.getDataType()));
        fieldsTypes.add(new RecordField("success", RecordFieldType.BOOLEAN.getDataType()));
        fieldsTypes.add(new RecordField("status", RecordFieldType.STRING.getDataType()));
        RecordSchema recordSchema = new SimpleRecordSchema(fieldsTypes);

        Map<String, Object> fieldValues = new HashMap<>();
        fieldValues.put("size", 700.0);
        fieldValues.put("endpoint", "/api/data");
        fieldValues.put("method", "GET");
        fieldValues.put("success", true);
        fieldValues.put("status", "200");
        MapRecord expectMapRecord = generateRecord(recordSchema, fieldValues);

        testRunner.setProperty("response_size_bytes", jsonObject);
        testRunner.setProperty(LIST_JSON_DYNAMIC_PROPERTY, "response_size_bytes");
        testRunner.enqueue("", attrs);
        testRunner.run();
        List<Map<String, Object>> row = recordSink.getRows();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());
        assertEquals(expectMapRecord, row.get(0).get("response_size_bytes"));
    }

    @Test
    public void testComplexJsonProperty() throws Exception {
        String complexJson = """
                {
                     "request_duration_seconds": {
                         "type": "Summary",
                         "quantiles": [
                             0.05, 0.12, 0.18, 0.45
                         ],
                         "value": ${value},
                         "endpoint": "${endpoint}",
                         "status": "${status}"
                     },
                     "requestMethod": "${method}"
                 }
              """;

        Map<String, String> attrs = new HashMap<>();
        attrs.put("value", "1200");
        attrs.put("endpoint", "/api/data");
        attrs.put("method", "GET");
        attrs.put("status", "200");

        List<RecordField> fieldsTypes = new ArrayList<>();
        fieldsTypes.add(new RecordField("type", RecordFieldType.STRING.getDataType()));
        fieldsTypes.add(new RecordField("quantiles", RecordFieldType.ARRAY.getArrayDataType(
                RecordFieldType.DOUBLE.getDataType())));
        fieldsTypes.add(new RecordField("value", RecordFieldType.DOUBLE.getDataType()));
        fieldsTypes.add(new RecordField("endpoint", RecordFieldType.STRING.getDataType()));
        fieldsTypes.add(new RecordField("status", RecordFieldType.STRING.getDataType()));
        RecordSchema recordSchema = new SimpleRecordSchema(fieldsTypes);

        Map<String, Object> fieldValues = new HashMap<>();
        fieldValues.put("type", "Summary");
        fieldValues.put("quantiles", new Double[]{0.05, 0.12, 0.18, 0.45});
        fieldValues.put("value", 1200.0);
        fieldValues.put("endpoint", "/api/data");
        fieldValues.put("status", "200");
        MapRecord expectMapRecord = generateRecord(recordSchema, fieldValues);

        testRunner.setProperty(SOURCE_TYPE, SourceTypeValues.JSON_PROPERTY.getAllowableValue());
        testRunner.setProperty(JSON_PROPERTY_OBJECT, complexJson);
        testRunner.enqueue("", attrs);
        testRunner.run();
        List<Map<String, Object>> row = recordSink.getRows();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());
        assertEquals(expectMapRecord, row.get(0).get("request_duration_seconds"));
    }

    @Test
    public void testSimpleJsonProperty() {
        String simpleJsonProperty = """
              {
                 "topLevelFieldName1": 0.1,
                 "topLevelFieldName2": 0.2
              }
              """;

        List<RecordField> fieldsTypes = new ArrayList<>();
        fieldsTypes.add(new RecordField("topLevelFieldName1", RecordFieldType.DOUBLE.getDataType()));
        fieldsTypes.add(new RecordField("topLevelFieldName2", RecordFieldType.DOUBLE.getDataType()));
        RecordSchema recordSchema = new SimpleRecordSchema(fieldsTypes);

        Map<String, Object> fieldValues = new HashMap<>();
        fieldValues.put("topLevelFieldName1", 0.1);
        fieldValues.put("topLevelFieldName2", 0.2);
        MapRecord expectMapRecord = generateRecord(recordSchema, fieldValues);

        testRunner.setProperty(SOURCE_TYPE, SourceTypeValues.JSON_PROPERTY.getAllowableValue());
        testRunner.setProperty(JSON_PROPERTY_OBJECT, simpleJsonProperty);
        testRunner.enqueue("");
        testRunner.run();
        List<Map<String, Object>> row = recordSink.getRows();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());
        assertEquals(expectMapRecord.getValue("topLevelFieldName1"), row.get(0).get("topLevelFieldName1"));
        assertEquals(expectMapRecord.getValue("topLevelFieldName2"), row.get(0).get("topLevelFieldName2"));
    }

    @Test
    public void testCombineDynamicPropertyAndJson() {
        String jsonMetric = """
             {
                  "value": ${size},
                  "label1": "${label1}",
                  "label2": "${label2}"
             }
             """;

        Map<String, String> attrs = new HashMap<>();
        attrs.put("attr1", "11.3");
        attrs.put("attr2", "5");
        attrs.put("size", "123.45");
        attrs.put("label1", "production");
        attrs.put("label2", "api_server_01");

        List<RecordField> fieldsTypes = new ArrayList<>();
        fieldsTypes.add(new RecordField("value", RecordFieldType.DOUBLE.getDataType()));
        fieldsTypes.add(new RecordField("label1", RecordFieldType.STRING.getDataType()));
        fieldsTypes.add(new RecordField("label2", RecordFieldType.STRING.getDataType()));
        RecordSchema recordSchema = new SimpleRecordSchema(fieldsTypes);

        Map<String, Object> fieldValues = new HashMap<>();
        fieldValues.put("value", 123.45);
        fieldValues.put("label1", "production");
        fieldValues.put("label2", "api_server_01");
        MapRecord expectMapRecord = generateRecord(recordSchema, fieldValues);

        List<RecordField> fieldsSimple = new ArrayList<>();
        fieldsSimple.add(new RecordField("attr1", RecordFieldType.STRING.getDataType()));
        fieldsSimple.add(new RecordField("attr2", RecordFieldType.STRING.getDataType()));
        RecordSchema recordSchemaSimple = new SimpleRecordSchema(fieldsSimple);

        Map<String, Object> fieldValuesSimple = new HashMap<>();
        fieldValuesSimple.put("attr1", 11.3);
        fieldValuesSimple.put("attr2", 5.0);
        MapRecord expectMapRecordSimple = generateRecord(recordSchemaSimple, fieldValuesSimple);

        testRunner.setProperty("attr1", "${attr1}");
        testRunner.setProperty("attr2", "${attr2}");
        testRunner.setProperty("json_metric", jsonMetric);
        testRunner.setProperty(LIST_JSON_DYNAMIC_PROPERTY, "json_metric");
        testRunner.enqueue("", attrs);
        testRunner.run();
        List<Map<String, Object>> row = recordSink.getRows();
        List<MockFlowFile> result = testRunner.getFlowFilesForRelationship(PutRecordFromProperty.REL_SUCCESS);
        assertEquals(1, result.size());

        assertEquals(expectMapRecordSimple.getValue("attr1"), row.get(0).get("attr1"));
        assertEquals(expectMapRecordSimple.getValue("attr2"), row.get(0).get("attr2"));
        assertEquals(expectMapRecord, row.get(0).get("json_metric"));
    }

    @Test
    public void testJsonPropertyStringArray() {
        String jsonWithArray = """
              {
                   "name": "http_requests_total",
                   "value": 12345,
                   "labels": [
                       "method",
                       "endpoint",
                       "status"
                   ]
               }
             """;

        testRunner.setProperty("testMetric", jsonWithArray);
        testRunner.setProperty(LIST_JSON_DYNAMIC_PROPERTY, "testMetric");
        testRunner.enqueue("");
        assertThrows(AssertionError.class, () -> {
            testRunner.run();
        });
    }

    @Test
    public void testJsonPropertyInvalidJson() {
        String invalidJson = """
              {
                   "size": ${size},
                   "endpoint": "${endpoint}"
                   "method": "${method}",
                   "status": "${status}"
               }
              """;

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

    private MapRecord generateRecord(RecordSchema schema, Map<String, Object> fieldValues) {
        Map<String, Object> recordMap = new HashMap<>();
        for (Map.Entry<String, Object> valueEntry : fieldValues.entrySet()) {
            String fieldName = valueEntry.getKey();
            Object value = valueEntry.getValue();
            recordMap.put(fieldName, value);
        }
        return new MapRecord(schema, recordMap);
    }
}

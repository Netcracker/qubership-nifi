/*
 * Copyright 2020-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.nifi.service;

import io.micrometer.core.instrument.Meter;
import io.prometheus.client.exporter.common.TextFormat;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.nifi.serialization.WriteResult;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSet;
import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.ListRecordSet;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class QubershipPrometheusRecordSinkTest {

    private QubershipPrometheusRecordSink recordSink;
    private TestRunner runner;
    private static final String SERVER_PORT = "9092";
    private static final String SERVER_URL = "http://localhost:" + SERVER_PORT + "/metrics";
    private OkHttpClient client;

    @BeforeEach
    public void setup() throws Exception {
        recordSink = new MockQubershipPrometheusRecordSink();
        runner = TestRunners.newTestRunner(TestProcessorRecordSink.class);
        runner.setValidateExpressionUsage(false);
        runner.addControllerService(MockQubershipPrometheusRecordSink.class.getSimpleName(), recordSink);
        runner.setProperty(recordSink, QubershipPrometheusRecordSink.METRICS_ENDPOINT_PORT, SERVER_PORT);
        runner.setProperty(recordSink, QubershipPrometheusRecordSink.INSTANCE_ID, "test-instance-id");
        runner.assertValid(recordSink);
        //
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.connectTimeout(5, TimeUnit.SECONDS);
        builder.readTimeout(5, TimeUnit.SECONDS);
        client = builder.build();
        runner.enableControllerService(recordSink);
    }

    @AfterEach
    public void tearDown() {
        runner.disableControllerService(recordSink);
        try {
            recordSink.onShutDown();
        } catch (Exception e) {
            Assertions.fail("Failed to shutdown recordSink", e);
        }
    }

    @Test
    public void testSendData() throws Exception {
        List<RecordField> recordFields = Arrays.asList(
                new RecordField("field11", RecordFieldType.INT.getDataType()),
                new RecordField("field12", RecordFieldType.INT.getDataType()),
                new RecordField("field2", RecordFieldType.STRING.getDataType()),
                new RecordField("field3", RecordFieldType.STRING.getDataType())
        );
        RecordSchema recordSchema = new SimpleRecordSchema(recordFields);

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("field11", 15);
        row1.put("field12", 6);
        row1.put("field2", "value12");
        row1.put("field3", "value13");

        RecordSet recordSet = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, row1)
        ));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a", "Hello");

        WriteResult writeResult = recordSink.sendData(recordSet, attributes, true);
        assertNotNull(writeResult);
        assertEquals(1, writeResult.getRecordCount());
        assertEquals("Hello", writeResult.getAttributes().get("a"));

        //check content of metrics
        assertTrue(("MeterId{name='field11', tags=[tag(field2=value12),tag(field3=value13),tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(0).getId().toString()));
        assertTrue(("MeterId{name='field12', tags=[tag(field2=value12),tag(field3=value13),tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(1).getId().toString()));
        assertEquals(2, recordSink.meterRegistry.getMeters().size());
        assertEquals(15, recordSink.meterRegistry.getMeters().get(0).measure().iterator().next().getValue());
        assertEquals(6, recordSink.meterRegistry.getMeters().get(1).measure().iterator().next().getValue());
        //test endpoint:
        Request request = new Request.Builder().url(SERVER_URL).get().build();
        try (Response resp = client.newCall(request).execute()) {
            assertTrue(resp.isSuccessful());
            assertEquals(TextFormat.CONTENT_TYPE_004, resp.header("Content-Type"));
            String responseBody = resp.body().string();
            assertTrue(responseBody.contains("field11{field2=\"value12\",field3=\"value13\",hostname=\"test-hostname\","
                    + "instance=\"test-namespace_test-hostname\",namespace=\"test-namespace\",}"));
            assertTrue(responseBody.contains("field12{field2=\"value12\",field3=\"value13\",hostname=\"test-hostname\","
                    + "instance=\"test-namespace_test-hostname\",namespace=\"test-namespace\",}"));
        }
    }

    @Test
    public void testSendDataWithNullValue() throws Exception {
        List<RecordField> recordFields = Arrays.asList(
                new RecordField("field11", RecordFieldType.INT.getDataType()),
                new RecordField("field12", RecordFieldType.INT.getDataType()),
                new RecordField("field2", RecordFieldType.STRING.getDataType()),
                new RecordField("field3", RecordFieldType.STRING.getDataType())
        );
        RecordSchema recordSchema = new SimpleRecordSchema(recordFields);

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("field11", 15);
        row1.put("field12", 6);
        row1.put("field2", null);
        row1.put("field3", "value13");

        RecordSet recordSet = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, row1)
        ));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a", "Hello");
        WriteResult writeResult = recordSink.sendData(recordSet, attributes, true);
        assertNotNull(writeResult);
        assertEquals(1, writeResult.getRecordCount());
        assertEquals("Hello", writeResult.getAttributes().get("a"));

        //check content of metrics
        assertTrue(("MeterId{name='field11', tags=[tag(field2=),tag(field3=value13),tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(0).getId().toString()));
        assertTrue(("MeterId{name='field12', tags=[tag(field2=),tag(field3=value13),tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(1).getId().toString()));
        assertEquals(2, recordSink.meterRegistry.getMeters().size());
        assertEquals(15, recordSink.meterRegistry.getMeters().get(0).measure().iterator().next().getValue());
        assertEquals(6, recordSink.meterRegistry.getMeters().get(1).measure().iterator().next().getValue());
    }

    @Test
    public void testWithNullMetricName() throws Exception {
        List<RecordField> recordFields = Arrays.asList(
                new RecordField("field11", RecordFieldType.INT.getDataType()),
                new RecordField("field12", RecordFieldType.INT.getDataType()),
                new RecordField("field2", RecordFieldType.STRING.getDataType()),
                new RecordField("field3", RecordFieldType.STRING.getDataType())
        );
        RecordSchema recordSchema = new SimpleRecordSchema(recordFields);

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("field11", 15);
        row1.put("field2", "value12");
        row1.put("field3", "value13");

        RecordSet recordSet = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, row1)
        ));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a", "Hello");
        WriteResult writeResult = recordSink.sendData(recordSet, attributes, true);
        assertNotNull(writeResult);
        assertEquals(1, writeResult.getRecordCount());
        assertEquals("Hello", writeResult.getAttributes().get("a"));

        //check content of metrics
        assertTrue(("MeterId{name='field11', tags=[tag(field2=value12),tag(field3=value13),tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(0).getId().toString()));
        assertEquals(1, recordSink.meterRegistry.getMeters().size());
        assertEquals(15, recordSink.meterRegistry.getMeters().get(0).measure().iterator().next().getValue());
    }

    @Test
    public void testWithNullLabelValues() throws Exception {
        List<RecordField> recordFields = Arrays.asList(
                new RecordField("field11", RecordFieldType.INT.getDataType()),
                new RecordField("field12", RecordFieldType.INT.getDataType()),
                new RecordField("field2", RecordFieldType.STRING.getDataType()),
                new RecordField("field3", RecordFieldType.STRING.getDataType())
        );
        RecordSchema recordSchema = new SimpleRecordSchema(recordFields);

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("field11", 15);
        row1.put("field12", 6);
        row1.put("field2", "value12");

        RecordSet recordSet = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, row1)
        ));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a", "Hello");
        WriteResult writeResult = recordSink.sendData(recordSet, attributes, true);
        assertNotNull(writeResult);
        assertEquals(1, writeResult.getRecordCount());
        assertEquals("Hello", writeResult.getAttributes().get("a"));

        //check content of metrics
        assertTrue(("MeterId{name='field11', tags=[tag(field2=value12),tag(field3=),tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(0).getId().toString()));
        assertTrue(("MeterId{name='field12', tags=[tag(field2=value12),tag(field3=),tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(1).getId().toString()));
        assertEquals(2, recordSink.meterRegistry.getMeters().size());
        assertEquals(6, recordSink.meterRegistry.getMeters().get(1).measure().iterator().next().getValue());
        assertEquals(15, recordSink.meterRegistry.getMeters().get(0).measure().iterator().next().getValue());
    }

    @Test
    public void testChangeValue() throws Exception {
        List<RecordField> recordFields = Arrays.asList(
                new RecordField("field1", RecordFieldType.INT.getDataType()),
                new RecordField("field2", RecordFieldType.STRING.getDataType()),
                new RecordField("field3", RecordFieldType.STRING.getDataType())
        );
        RecordSchema recordSchema1 = new SimpleRecordSchema(recordFields);

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("field1", 6);
        row1.put("field2", "value12");
        row1.put("field3", "value13");

        RecordSet recordSet1 = new ListRecordSet(recordSchema1, Arrays.asList(
                new MapRecord(recordSchema1, row1)
        ));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a", "Hello");
        WriteResult writeResult = recordSink.sendData(recordSet1, attributes, true);

        assertNotNull(writeResult);
        assertEquals(1, writeResult.getRecordCount());
        assertEquals("Hello", writeResult.getAttributes().get("a"));

        assertTrue(("MeterId{name='field1', tags=[tag(field2=value12),tag(field3=value13),tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(0).getId().toString()));
        assertEquals(1, recordSink.meterRegistry.getMeters().size());
        assertEquals(6, recordSink.meterRegistry.getMeters().get(0).measure().iterator().next().getValue());

        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("field1", 15);
        row2.put("field2", "value12");
        row2.put("field3", "value13");

        RecordSet recordSet2 = new ListRecordSet(recordSchema1, Arrays.asList(
                new MapRecord(recordSchema1, row2)
        ));

        attributes = new LinkedHashMap<>();
        attributes.put("a", "Hello");
        writeResult = recordSink.sendData(recordSet2, attributes, true);

        assertNotNull(writeResult);
        assertEquals(1, writeResult.getRecordCount());
        assertEquals("Hello", writeResult.getAttributes().get("a"));

        assertTrue(("MeterId{name='field1', tags=[tag(field2=value12),tag(field3=value13),tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(0).getId().toString()));
        assertEquals(1, recordSink.meterRegistry.getMeters().size());
        assertEquals(15, recordSink.meterRegistry.getMeters().get(0).measure().iterator().next().getValue());
    }


    @Test
    public void testSendDataWithDifSchema() throws Exception {
        List<RecordField> recordFields1 = Arrays.asList(
                new RecordField("field1", RecordFieldType.INT.getDataType()),
                new RecordField("field2", RecordFieldType.STRING.getDataType()),
                new RecordField("field3", RecordFieldType.STRING.getDataType())
        );
        RecordSchema recordSchema1 = new SimpleRecordSchema(recordFields1);

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("field1", 10);
        row1.put("field2", "value1");
        row1.put("field3", "value2");

        RecordSet recordSet1 = new ListRecordSet(recordSchema1, Arrays.asList(
                new MapRecord(recordSchema1, row1)
        ));

        Map<String, String> attributes1 = new LinkedHashMap<>();
        attributes1.put("a1", "Hello1");
        WriteResult writeResult1 = recordSink.sendData(recordSet1, attributes1, true);

        List<RecordField> recordFields2 = Arrays.asList(
                new RecordField("newField1", RecordFieldType.INT.getDataType()),
                new RecordField("newField2", RecordFieldType.STRING.getDataType())
        );
        RecordSchema recordSchema2 = new SimpleRecordSchema(recordFields2);

        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("newField1", 5);
        row2.put("newField2", "value2");

        RecordSet recordSet2 = new ListRecordSet(recordSchema2, Arrays.asList(
                new MapRecord(recordSchema2, row2)
        ));

        Map<String, String> attributes2 = new LinkedHashMap<>();
        attributes2.put("a2", "Hello2");
        WriteResult writeResult2 = recordSink.sendData(recordSet2, attributes2, true);

        assertNotNull(writeResult1);
        assertNotNull(writeResult2);
        assertEquals(1, writeResult1.getRecordCount());
        assertEquals(1, writeResult2.getRecordCount());
        assertEquals("Hello1", writeResult1.getAttributes().get("a1"));
        assertEquals("Hello2", writeResult2.getAttributes().get("a2"));

        List<Meter> content = recordSink.meterRegistry.getMeters();
        assertFalse(recordSink.meterRegistry.getMeters().get(0).getId().toString().
                equals(recordSink.meterRegistry.getMeters().get(1).getId().toString()));
    }

    @Test
    public void testClearData() throws Exception {
        runner.disableControllerService(recordSink);
        runner.setProperty(recordSink, recordSink.CLEAR_METRICS, "Yes");
        runner.enableControllerService(recordSink);
        List<RecordField> recordFields = Arrays.asList(
                new RecordField("field1", RecordFieldType.INT.getDataType()),
                new RecordField("field2", RecordFieldType.DECIMAL.getDecimalDataType(30, 10)),
                new RecordField("field3", RecordFieldType.STRING.getDataType())
        );
        RecordSchema recordSchema = new SimpleRecordSchema(recordFields);

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("field1", 15);
        row1.put("field2", BigDecimal.valueOf(12.34567D));
        row1.put("field3", "Hello");

        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("field1", 6);
        row2.put("field2", BigDecimal.valueOf(0.1234567890123456789D));
        row2.put("field3", "World!");

        RecordSet recordSet = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, row1),
                new MapRecord(recordSchema, row2)
        ));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a", "Hello");
        WriteResult writeResult = recordSink.sendData(recordSet, attributes, true);
        assertNotNull(writeResult);
        assertEquals(2, writeResult.getRecordCount());
        assertEquals("Hello", writeResult.getAttributes().get("a"));

        assertNotNull(recordSink.meterRegistry.getMeters());
        runner.disableControllerService(recordSink);
        assertEquals(0, recordSink.meterRegistry.getMeters().size());
        runner.enableControllerService(recordSink);
    }

    @Test
    public void testSendCounterMetric() throws Exception {
        List<RecordField> childRecordFields = Arrays.asList(
                new RecordField("value", RecordFieldType.DOUBLE.getDataType()),
                new RecordField("type", RecordFieldType.STRING.getDataType(), "Counter")
        );

        RecordSchema childRecordSchema = new SimpleRecordSchema(childRecordFields);

        List<RecordField> recordFields = Arrays.asList(
                new RecordField("namespace", RecordFieldType.STRING.getDataType()),
                new RecordField("integration_name", RecordFieldType.STRING.getDataType()),
                new RecordField("integration_executions_count",
                        RecordFieldType.RECORD.getRecordDataType(childRecordSchema))
        );
        RecordSchema recordSchema = new SimpleRecordSchema(recordFields);

        Map<String, Object> recordFiled = new HashMap<>();
        recordFiled.put("value", 25);
        recordFiled.put("type", "Counter");


        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("integration_name", "MyIntegration");
        row1.put("integration_executions_count", new MapRecord(childRecordSchema, recordFiled));

        RecordSet recordSet = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, row1)
        ));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a", "Hello");
        WriteResult writeResult = recordSink.sendData(recordSet, attributes, true);
        assertNotNull(writeResult);
        assertEquals(1, writeResult.getRecordCount());
        assertEquals("Hello", writeResult.getAttributes().get("a"));
        //check content of metrics
        assertTrue(("MeterId{name='integration_executions_count', tags=[tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(integration_name=MyIntegration),"
                + "tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(0).getId().toString()));
        assertEquals(25.0, recordSink.meterRegistry.getMeters().get(0).measure().iterator().next().getValue());
    }

    @Test
    public void testIncrementCounterMetric() throws Exception {
        List<RecordField> childRecordFields = Arrays.asList(
                new RecordField("value", RecordFieldType.INT.getDataType()),
                new RecordField("type", RecordFieldType.STRING.getDataType(), "Counter")
        );

        RecordSchema childRecordSchema = new SimpleRecordSchema(childRecordFields);

        List<RecordField> recordFields = Arrays.asList(
                new RecordField("namespace", RecordFieldType.STRING.getDataType()),
                new RecordField("integration_name", RecordFieldType.STRING.getDataType()),
                new RecordField("integration_executions_count",
                        RecordFieldType.RECORD.getRecordDataType(childRecordSchema))
        );
        RecordSchema recordSchema = new SimpleRecordSchema(recordFields);

        Map<String, Object> recordFiled = new HashMap<>();
        recordFiled.put("value", 25);
        recordFiled.put("type", "Counter");


        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("integration_name", "MyIntegration");
        row1.put("integration_executions_count", new MapRecord(childRecordSchema, recordFiled));

        RecordSet recordSet = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, row1)
        ));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a", "Hello");
        WriteResult writeResult = recordSink.sendData(recordSet, attributes, true);
        assertNotNull(writeResult);
        assertEquals(1, writeResult.getRecordCount());
        assertEquals("Hello", writeResult.getAttributes().get("a"));
        //check content of metrics
        assertTrue(("MeterId{name='integration_executions_count', tags=[tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(integration_name=MyIntegration),"
                + "tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(0).getId().toString()));
        assertEquals(25.0, recordSink.meterRegistry.getMeters().get(0).measure().iterator().next().getValue());

        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("integration_name", "MyIntegration");
        row2.put("integration_executions_count", new MapRecord(childRecordSchema, recordFiled));
        RecordSet recordSet2 = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, row2)
        ));
        WriteResult writeResult2 = recordSink.sendData(recordSet2, attributes, true);
        assertNotNull(writeResult);
        assertTrue(("MeterId{name='integration_executions_count', tags=[tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(integration_name=MyIntegration),"
                + "tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(0).getId().toString()));
        assertEquals(50.0, recordSink.meterRegistry.getMeters().get(0).measure().iterator().next().getValue());
    }


    @Test
    public void testSendSummaryMetric() throws Exception {
        List<RecordField> childRecordFields = Arrays.asList(
                new RecordField("value", RecordFieldType.DOUBLE.getDataType()),
                new RecordField("type", RecordFieldType.STRING.getDataType(), "Summary"),
                new RecordField("publishPercentiles",
                        RecordFieldType.ARRAY.getArrayDataType(RecordFieldType.DOUBLE.getDataType()),
                        new Double[]{0.05, 0.95}),
                new RecordField("statisticExpiry", RecordFieldType.STRING.getDataType(), "PT5M"),
                new RecordField("statisticBufferLength", RecordFieldType.INT.getDataType(), 25)
        );

        RecordSchema childRecordSchema = new SimpleRecordSchema(childRecordFields);

        List<RecordField> recordFields = Arrays.asList(
                new RecordField("namespace", RecordFieldType.STRING.getDataType()),
                new RecordField("integration_name", RecordFieldType.STRING.getDataType()),
                new RecordField("integration_execution_duration",
                        RecordFieldType.RECORD.getRecordDataType(childRecordSchema))
        );
        RecordSchema recordSchema = new SimpleRecordSchema(recordFields);

        Map<String, Object> recordFiled = new HashMap<>();
        recordFiled.put("value", 101.512);
        recordFiled.put("type", "Summary");
        recordFiled.put("statisticExpiry", "PT10M");
        recordFiled.put("statisticBufferLength", 36);
        recordFiled.put("publishPercentiles", new Double[]{0.06, 0.96});

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("integration_name", "MyIntegration");
        row1.put("integration_execution_duration", new MapRecord(childRecordSchema, recordFiled));

        RecordSet recordSet = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, row1)
        ));
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a", "Hello");
        WriteResult writeResult = recordSink.sendData(recordSet, attributes, true);
        assertNotNull(writeResult);
        assertEquals(1, writeResult.getRecordCount());
        assertEquals("Hello", writeResult.getAttributes().get("a"));
        //check content of metrics
        assertTrue(("MeterId{name='integration_execution_duration', tags=[tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(integration_name=MyIntegration),"
                + "tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(0).getId().toString()));
        assertEquals(101.512,
                recordSink.meterRegistry.get("integration_execution_duration").summary().totalAmount());
    }

    @Test
    public void testIncrementSummaryMetric() throws Exception {
        List<RecordField> childRecordFields = Arrays.asList(
                new RecordField("value", RecordFieldType.DOUBLE.getDataType()),
                new RecordField("type", RecordFieldType.STRING.getDataType(), "Summary"),
                new RecordField("publishPercentiles",
                        RecordFieldType.ARRAY.getArrayDataType(RecordFieldType.DOUBLE.getDataType()),
                        new Double[]{0.05, 0.95}),
                new RecordField("statisticExpiry", RecordFieldType.STRING.getDataType(), "PT5M"),
                new RecordField("statisticBufferLength", RecordFieldType.INT.getDataType(), 25)
        );

        RecordSchema childRecordSchema = new SimpleRecordSchema(childRecordFields);

        List<RecordField> recordFields = Arrays.asList(
                new RecordField("namespace", RecordFieldType.STRING.getDataType()),
                new RecordField("integration_name", RecordFieldType.STRING.getDataType()),
                new RecordField("integration_execution_duration",
                        RecordFieldType.RECORD.getRecordDataType(childRecordSchema))
        );
        RecordSchema recordSchema = new SimpleRecordSchema(recordFields);

        Map<String, Object> recordFiled = new HashMap<>();
        recordFiled.put("value", 101.512);
        recordFiled.put("type", "Summary");
        recordFiled.put("statisticExpiry", "PT10M");
        recordFiled.put("statisticBufferLength", 36);
        recordFiled.put("publishPercentiles", new Double[]{0.06, 0.96});

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("integration_name", "MyIntegration");
        row1.put("integration_execution_duration", new MapRecord(childRecordSchema, recordFiled));

        RecordSet recordSet = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, row1)
        ));
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a", "Hello");
        WriteResult writeResult = recordSink.sendData(recordSet, attributes, true);
        assertNotNull(writeResult);
        assertEquals(1, writeResult.getRecordCount());
        assertEquals("Hello", writeResult.getAttributes().get("a"));
        //check content of metrics
        assertTrue(("MeterId{name='integration_execution_duration', tags=[tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(integration_name=MyIntegration),"
                + "tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(0).getId().toString()));
        //assertEquals(101.512, recordSink.meterRegistry.getMeters().get(0).measure().iterator().next().getValue());

        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("integration_name", "MyIntegration");
        row2.put("integration_execution_duration", new MapRecord(childRecordSchema, recordFiled));
        RecordSet recordSet2 = new ListRecordSet(recordSchema, Arrays.asList(
                new MapRecord(recordSchema, row2)
        ));
        WriteResult writeResult2 = recordSink.sendData(recordSet2, attributes, true);
        assertNotNull(writeResult);
        assertTrue(("MeterId{name='integration_execution_duration', tags=[tag(hostname=test-hostname),"
                + "tag(instance=test-namespace_test-hostname),tag(integration_name=MyIntegration),"
                + "tag(namespace=test-namespace)]}").
                equals(recordSink.meterRegistry.getMeters().get(0).getId().toString()));
        assertEquals(203.024,
                recordSink.meterRegistry.get("integration_execution_duration").summary().totalAmount());
    }
}

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

package org.qubership.nifi.processors;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.nifi.processors.validation.JsonTestData;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaException;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.provenance.ProvenanceEventRecord;
import org.apache.nifi.provenance.ProvenanceEventType;
import org.apache.nifi.util.LogMessage;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.processors.validation.JsonTestData;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.nifi.processors.validation.ValidationErrorAssertions.assertErrors;
import static org.qubership.nifi.processors.validation.ValidationErrorAssertions.readTree;

public class ValidateJsonTest {
    private static final String STRING_ARRAY_SCHEMA = """
            {"type": "array", "items": {"type": "string"}}""";

    private static final String MONITORING_SCHEMA = """
            {
               "title":"Check Monitoring Request",
               "required":[
                  "entities",
                  "newStatus"
               ],
               "properties":{
                  "entities":{
                     "type":"array",
                     "items":{
                        "type":"object",
                        "required":[
                           "businessEntityType",
                           "sourceId"
                        ],
                        "properties":{
                           "businessEntityType":{
                              "type":"string",
                              "enum":[
                                 "Customer",
                                 "Organization",
                                 "Individual",
                                 "PartyRole",
                                 "PartyInteraction",
                                 "ProductHierarchy",
                                 "AllCustomerProducts"
                              ]
                           },
                           "sourceId":{
                              "type":"string"
                           },
                           "_sourceTimeStamp":{
                              "type":"string",
                              "format":"date-time"
                           }
                        }
                     }
                  },
                  "newStatus":{
                     "type":"string",
                     "enum":[
                        "Prepared",
                        "Ingested",
                        "In Progress",
                        "Processed Successfully",
                        "Processed with Errors",
                        "Processed with Warnings",
                        "Rolled back",
                        "Failed to rollback",
                        "Extracted",
                        "Transformed"
                     ]
                  },
                  "requestId":{
                     "type":[
                        "string",
                        "null"
                     ]
                  },
                  "sessionId":{
                     "type":[
                        "string",
                        "null"
                     ]
                  }
               }
            }""";

    private TestRunner testRunner;

    @BeforeEach
    public void init() {
        this.testRunner = TestRunners.newTestRunner(ValidateJson.class);
    }

    private MockFlowFile runOne(String schema, String content, Relationship expectedRelationship) {
        testRunner.setProperty(ValidateJson.SCHEMA, schema);
        testRunner.enqueue(content);
        testRunner.run();
        testRunner.assertAllFlowFilesTransferred(expectedRelationship, 1);
        return testRunner.getFlowFilesForRelationship(expectedRelationship).get(0);
    }

    private void assertRoutedToValidUnchanged(String schema, String content) {
        MockFlowFile flowFile = runOne(schema, content, ValidateJson.REL_VALID);
        flowFile.assertContentEquals(content);
        flowFile.assertAttributeEquals("migration.phase", "Validation");
    }

    private void assertRoutedToInvalid(String schema, String content) {
        MockFlowFile flowFile = runOne(schema, content, ValidateJson.REL_INVALID);
        assertAll(
                () -> flowFile.assertAttributeEquals("migration.phase", "Validation"),
                () -> flowFile.assertAttributeEquals("processor.uuid", testRunner.getProcessor().getIdentifier()));
    }

    private static JsonNode withoutErrors(JsonNode document) {
        ObjectNode copy = document.deepCopy();
        copy.remove("_errors");
        return copy;
    }

    @Test
    public void flatCustomerMatchingSchema1IsRoutedToValidUnchanged() {
        assertRoutedToValidUnchanged(JsonTestData.TEST_JSON_SCHEMA_1, JsonTestData.VALID_TEST_JSON_BY_SHEMA1);
    }

    @Test
    public void validCustomerWithMissingCustomMetadataIsRoutedToValidUnchanged() {
        testRunner.setProperty(ValidateJson.BE_TYPE_PATH_PROP, "missingType");
        testRunner.setProperty(ValidateJson.ID_PATH_PROP, "missingId");

        assertRoutedToValidUnchanged(JsonTestData.TEST_JSON_SCHEMA_1, JsonTestData.VALID_TEST_JSON_BY_SHEMA1);
    }

    @Test
    public void invalidCustomerWithMissingCustomMetadataGetsFallbackErrors() {
        testRunner.setProperty(ValidateJson.BE_TYPE_PATH_PROP, "missingType");
        testRunner.setProperty(ValidateJson.ID_PATH_PROP, "missingId");

        MockFlowFile flowFile = runOne(JsonTestData.TEST_JSON_SCHEMA_2, JsonTestData.INVALID_TEST_JSON_BY_SHEMA2,
                ValidateJson.REL_INVALID);

        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'type' in 'object' Business Entity 'key5.key6'",
                  "details": "inner.key31.key5[0].key6: integer found, string expected",
                  "source": {"missingId": "", "path": "inner.key31.key5[0].key6"}
                }]""",
                readTree(flowFile.getContent()).get("_errors"));
    }

    @Test
    public void schemaChangedBetweenRunsIsUsedAfterRescheduling() {
        runOne(JsonTestData.TEST_JSON_SCHEMA_2, JsonTestData.INVALID_TEST_JSON_BY_SHEMA2, ValidateJson.REL_INVALID);
        testRunner.clearTransferState();

        runOne(JsonTestData.TEST_JSON_SCHEMA_4, JsonTestData.INVALID_TEST_JSON_BY_SHEMA2, ValidateJson.REL_VALID);
        testRunner.clearTransferState();

        runOne(JsonTestData.TEST_JSON_SCHEMA_4, JsonTestData.INVALID_TEST1_JSON_BY_SHEMA4, ValidateJson.REL_INVALID);
    }

    @Test
    public void nestedCustomerMatchingSchema2IsRoutedToValidUnchanged() {
        assertRoutedToValidUnchanged(JsonTestData.TEST_JSON_SCHEMA_2, JsonTestData.VALID_TEST_JSON_BY_SHEMA2);
    }

    @Test
    public void validDateTimeMatchingSchema4IsRoutedToValidUnchanged() {
        assertRoutedToValidUnchanged(JsonTestData.TEST_JSON_SCHEMA_4, JsonTestData.VALID_TEST_JSON_BY_SHEMA4);
    }

    @Test
    public void validFlowFileGetsOneRouteProvenanceEvent() {
        runOne(JsonTestData.TEST_JSON_SCHEMA_1, JsonTestData.VALID_TEST_JSON_BY_SHEMA1, ValidateJson.REL_VALID);

        List<ProvenanceEventRecord> events = testRunner.getProvenanceEvents();
        assertEquals(List.of(ProvenanceEventType.ROUTE),
                events.stream().map(ProvenanceEventRecord::getEventType).toList());
        assertEquals("valid", events.get(0).getRelationship());
    }

    @Test
    public void wrongTypesAndMissingPropertyAgainstSchema1AreRoutedToInvalid() {
        assertRoutedToInvalid(JsonTestData.TEST_JSON_SCHEMA_1, JsonTestData.INVALID_TEST_JSON_BY_SHEMA1);
    }

    @Test
    public void wrongTypeInArrayElementAgainstSchema2IsRoutedToInvalid() {
        assertRoutedToInvalid(JsonTestData.TEST_JSON_SCHEMA_2, JsonTestData.INVALID_TEST_JSON_BY_SHEMA2);
    }

    @Test
    public void missingPropertyInArrayElementAgainstSchema3IsRoutedToInvalid() {
        assertRoutedToInvalid(JsonTestData.TEST_JSON_SCHEMA_3, JsonTestData.INVALID_TEST_JSON_BY_SHEMA3);
    }

    @Test
    public void invalidDateAgainstSchema4IsRoutedToInvalid() {
        assertRoutedToInvalid(JsonTestData.TEST_JSON_SCHEMA_4, JsonTestData.INVALID_TEST1_JSON_BY_SHEMA4);
    }

    @Test
    public void invalidHourAgainstSchema4IsRoutedToInvalid() {
        assertRoutedToInvalid(JsonTestData.TEST_JSON_SCHEMA_4, JsonTestData.INVALID_TEST2_JSON_BY_SHEMA4);
    }

    @Test
    public void invalidContentIsTheInputWithErrorsBuiltFromTheDefaultProperties() {
        MockFlowFile flowFile = runOne(JsonTestData.TEST_JSON_SCHEMA_2, JsonTestData.INVALID_TEST_JSON_BY_SHEMA2,
                ValidateJson.REL_INVALID);

        JsonNode output = readTree(flowFile.getContent());
        assertEquals(readTree(JsonTestData.INVALID_TEST_JSON_BY_SHEMA2), withoutErrors(output));
        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'type' in 'Customer' Business Entity 'key5.key6'",
                  "details": "inner.key31.key5[0].key6: integer found, string expected",
                  "source": {"_sourceId": "CUST#123456789", "path": "inner.key31.key5[0].key6"}
                }]""",
                output.get("_errors"));
    }

    @Test
    public void customPathsAndErrorCodeAreUsedInTheErrors() {
        testRunner.setProperty(ValidateJson.BE_TYPE_PATH_PROP, "kind");
        testRunner.setProperty(ValidateJson.ID_PATH_PROP, "ref");
        testRunner.setProperty(ValidateJson.ERROR_CODE_PROP, "E-1");

        MockFlowFile flowFile = runOne("""
                {"properties": {"qty": {"type": "integer"}}}""", """
                {"kind": "Order", "ref": "R-7", "qty": "x"}""", ValidateJson.REL_INVALID);

        assertErrors("""
                [{
                  "code": "E-1",
                  "title": "Validation error 'type' in 'Order' Business Entity '$.qty'",
                  "details": "qty: string found, integer expected",
                  "source": {"ref": "R-7", "path": "qty"}
                }]""",
                readTree(flowFile.getContent()).get("_errors"));
    }

    @Test
    public void schemaWithANonStringTypeRoutesAnObjectToInvalid() {
        // Pins current behavior, not a contract: {"type": 5} is not a valid schema, yet the processor accepts it.
        MockFlowFile flowFile = runOne("""
                {"type": 5}""", """
                {"_sourceId": "S1", "_businessEntityType": "Customer"}""", ValidateJson.REL_INVALID);

        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'type' in 'Customer' Business Entity '$'",
                  "details": "$: object found, unknown expected",
                  "source": {"_sourceId": "S1", "path": "$"}
                }]""",
                readTree(flowFile.getContent()).get("_errors"));
    }

    @Test
    public void schemaWithoutDollarSchemaIsReadAsDraft4() {
        // A boolean exclusiveMaximum is draft 4 syntax; draft 6 and later expect a number there.
        MockFlowFile flowFile = runOne("""
                {"properties": {"count": {"maximum": 10, "exclusiveMaximum": true}}}""", """
                {"_sourceId": "S1", "_businessEntityType": "Customer", "count": 10}""", ValidateJson.REL_INVALID);

        assertErrors("""
                [{
                  "code": "ME-JV-0002",
                  "title": "Validation error 'maximum' in 'Customer' Business Entity '$.count'",
                  "details": "count: must have a maximum value of 10",
                  "source": {"_sourceId": "S1", "path": "count"}
                }]""",
                readTree(flowFile.getContent()).get("_errors"));
    }

    @Test
    public void schemaDeclaringDraft07FailsToSchedule() {
        testRunner.setProperty(ValidateJson.SCHEMA, """
                {"$schema": "http://json-schema.org/draft-07/schema#", "type": "object"}""");

        AssertionError error = assertThrows(AssertionError.class, () -> testRunner.run(0),
                "testRunner.run(0)");

        Throwable rootCause = error;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        JsonSchemaException schemaError = assertInstanceOf(JsonSchemaException.class, rootCause,
                "root cause of the scheduling failure");
        assertEquals("Unknown MetaSchema: https://json-schema.org/draft-07/schema", schemaError.getMessage());
    }

    @Test
    public void arrayRootMatchingTheSchemaIsRoutedToValidUnchanged() {
        assertRoutedToValidUnchanged(STRING_ARRAY_SCHEMA, "[\"a\"]");
    }

    @Test
    public void arrayRootWithErrorsFailsTheTrigger() {
        // Pins current behavior, not a contract: the session rolls back, so the FlowFile is retried and never
        // reaches invalid.
        testRunner.setProperty(ValidateJson.SCHEMA, STRING_ARRAY_SCHEMA);
        testRunner.enqueue("[1]");

        AssertionError error = assertThrows(AssertionError.class, () -> testRunner.run());

        assertInstanceOf(ProcessException.class, error.getCause(), "cause of the failed run");
        testRunner.assertTransferCount(ValidateJson.REL_VALID, 0);
        testRunner.assertTransferCount(ValidateJson.REL_INVALID, 0);
        testRunner.assertTransferCount(ValidateJson.REL_NOT_JSON, 0);
    }

    @Test
    public void schemaThatIsNotJsonFailsPropertyValidation() {
        testRunner.setProperty(ValidateJson.SCHEMA, JsonTestData.TEST_INVALID_JSON_SCHEMA_4);

        testRunner.assertNotValid();
    }

    @Test
    public void checkRelationships() {
        Set<Relationship> relationships = testRunner.getProcessContext().getAvailableRelationships();
        Assertions.assertEquals(3, relationships.size());
        relationships.forEach(r -> Assertions.assertTrue(
                r.getName().equals("valid")
                        || r.getName().equals("invalid")
                        || r.getName().equals("not_json")));
    }

    /**
     * A schema without {@code $schema} is read as draft-04. The boolean {@code exclusiveMaximum} is valid only in
     * draft-04; later drafts require a number and reject the schema when the processor is scheduled.
     */
    @Test
    public void schemaWithoutDialectIsReadAsDraft04() {
        testRunner.setProperty(ValidateJson.SCHEMA, "{\"type\": \"object\","
                + " \"properties\": {\"n\": {\"type\": \"number\", \"maximum\": 10, \"exclusiveMaximum\": true}}}");
        testRunner.enqueue("{\"n\": 10}");
        testRunner.run();

        testRunner.assertAllFlowFilesTransferred(ValidateJson.REL_INVALID, 1);
    }

    @Test
    public void errorDetailsAreEnglishUnderAnotherDefaultLocale() throws IOException {
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.GERMAN);
        try {
            testRunner.setProperty(ValidateJson.SCHEMA, "{\"$schema\": \"http://json-schema.org/draft-04/schema#\","
                    + " \"type\": \"object\", \"properties\": {\"o\": {\"type\": \"string\"}}}");
            testRunner.enqueue("{\"o\": 1}");
            testRunner.run();
        } finally {
            Locale.setDefault(defaultLocale);
        }

        testRunner.assertAllFlowFilesTransferred(ValidateJson.REL_INVALID, 1);
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(ValidateJson.REL_INVALID).get(0);
        JsonNode errors = new ObjectMapper().readTree(flowFile.getContent()).get("_errors");
        assertEquals("o: integer found, string expected", errors.get(0).get("details").asText());
    }

    @Test
    public void testNullFlowFile() {
        testRunner.setProperty(ValidateJson.SCHEMA, JsonTestData.TEST_JSON_SCHEMA_1);
        testRunner.run();
        testRunner.assertQueueEmpty();
    }

    @Test
    public void testNoWarnWithRegEx() {
        testRunner.setProperty(ValidateJson.SCHEMA, JsonTestData.CUSTOMER_PRODUCT_SCHEMA);
        testRunner.setProperty(ValidateJson.ID_PATH_PROP, "_sourceId");
        testRunner.setProperty(ValidateJson.WRAPPER_REGEX, "(\\$\\._items\\[[0-9]+]\\.)");
        testRunner.enqueue(JsonTestData.ALL_CUSTOMER_PRODUCT_REQUEST);
        testRunner.run();

        List<LogMessage> warnLog = testRunner.getLogger().getWarnMessages();
        Assertions.assertEquals(0, warnLog.size());

        List<MockFlowFile> invalidFlows = testRunner.getFlowFilesForRelationship(ValidateJson.REL_INVALID);
        Assertions.assertEquals(1, invalidFlows.size());
    }

    @Test
    public void testWarnWithInvalidRegEx() {
        testRunner.setProperty(ValidateJson.SCHEMA, JsonTestData.CUSTOMER_PRODUCT_SCHEMA);
        testRunner.setProperty(ValidateJson.ID_PATH_PROP, "_sourceId");
        testRunner.setProperty(ValidateJson.WRAPPER_REGEX, "(\\$\\._invalid-regEx\\[[0-9]+]\\.)");
        testRunner.enqueue(JsonTestData.ALL_CUSTOMER_PRODUCT_REQUEST);
        testRunner.run();

        List<LogMessage> warnLog = testRunner.getLogger().getWarnMessages();
        Assertions.assertEquals(2, warnLog.size());

        List<MockFlowFile> invalidFlows = testRunner.getFlowFilesForRelationship(ValidateJson.REL_INVALID);
        Assertions.assertEquals(1, invalidFlows.size());
    }

    @Test
    public void testWarnWithInvalidSourceIdPath() {
        testRunner.setProperty(ValidateJson.SCHEMA, JsonTestData.CUSTOMER_PRODUCT_SCHEMA);
        testRunner.setProperty(ValidateJson.ID_PATH_PROP, "invalid-sourceId-path");
        testRunner.setProperty(ValidateJson.WRAPPER_REGEX, "(\\$\\._items\\[[0-9]+]\\.)");
        testRunner.enqueue(JsonTestData.ALL_CUSTOMER_PRODUCT_REQUEST);
        testRunner.run();

        List<LogMessage> warnLog = testRunner.getLogger().getWarnMessages();
        Assertions.assertEquals(4, warnLog.size());

        List<MockFlowFile> invalidFlows = testRunner.getFlowFilesForRelationship(ValidateJson.REL_INVALID);
        Assertions.assertEquals(1, invalidFlows.size());
    }

    @Test
    public void testWarnWithInvalidInstanceSourceIdPath() {
        testRunner.setProperty(ValidateJson.SCHEMA, JsonTestData.CUSTOMER_PRODUCT_SCHEMA);
        testRunner.setProperty(ValidateJson.ID_PATH_PROP, "_sourceId");
        testRunner.setProperty(ValidateJson.WRAPPER_REGEX, "(\\$\\._items\\[[0-9]+]\\.)");
        testRunner.enqueue(JsonTestData.MEDIUM_ACP_REQUEST_LOSTED_INNER_SOURCE_ID);
        testRunner.run();

        List<LogMessage> warnLog = testRunner.getLogger().getWarnMessages();
        Assertions.assertEquals(2, warnLog.size());

        List<MockFlowFile> invalidFlows = testRunner.getFlowFilesForRelationship(ValidateJson.REL_INVALID);
        Assertions.assertEquals(1, invalidFlows.size());
    }

    @Test
    public void testInvalidMonitoringRequest() {
        testRunner.setProperty(ValidateJson.SCHEMA, MONITORING_SCHEMA);
        testRunner.enqueue("""
                {
                    "entities": {
                        "businessEntityType":"Customer",
                        "sourceId":"New_Update_Processor_4",
                        "_sourceTimeStamp":"2018-01-31T09:40:15.581Z"
                    },
                    "newStatus":"In Progress",
                    "sessionId": "12",
                    "requestId": "12"
                }""");
        testRunner.run();
        List<MockFlowFile> invalidFlows = testRunner.getFlowFilesForRelationship(ValidateJson.REL_INVALID);
        Assertions.assertEquals(1, invalidFlows.size());
    }

    @Test
    public void jsonFollowedByTrailingTextIsRoutedToNotJsonUnchanged() {
        String content = """
                {
                   "entities":
                \t\t[{
                \t\t "businessEntityType":"Customer",
                \t\t "sourceId":"New_Update_Processor_4",
                \t\t "_sourceTimeStamp":"2018-01-31T09:40:15.581Z"
                \t\t}]
                   ,
                   "newStatus":"In Progress",
                   "sessionId": "12",
                   "requestId": "12"
                }ioioiooo""";

        MockFlowFile flowFile = runOne(MONITORING_SCHEMA, content, ValidateJson.REL_NOT_JSON);

        flowFile.assertContentEquals(content);
        flowFile.assertAttributeExists(ValidateJson.ERROR_ATTR);
    }

    @Test
    public void testNotJson() {
        testRunner.setProperty(ValidateJson.SCHEMA, MONITORING_SCHEMA);
        testRunner.enqueue("ioioiooo");
        testRunner.run();
        testRunner.assertAllFlowFilesTransferred(ValidateJson.REL_NOT_JSON);
        List<MockFlowFile> notJsonFFs = testRunner.getFlowFilesForRelationship(ValidateJson.REL_NOT_JSON);
        assertEquals(1, notJsonFFs.size());
        assertNotNull(notJsonFFs.get(0).getAttribute(ValidateJson.ERROR_ATTR));
        assertTrue(notJsonFFs.get(0).getAttribute(ValidateJson.ERROR_ATTR).matches(
                "Not json content in Flow file: Unrecognized token 'ioioiooo': was expecting "
                        + "\\(JSON String, Number, Array, Object or token 'null', 'true' or 'false'\\)\n"
                        + " at \\[Source: .*; line: 1, column: \\d+]"));
    }
}

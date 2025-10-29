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

import org.qubership.nifi.processors.validation.JsonTestData;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.util.LogMessage;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ValidateJsonTest {
    private TestRunner testRunner;
    private JsonTestData testData = new JsonTestData();

    @BeforeEach
    public void init() {
        this.testRunner = TestRunners.newTestRunner(ValidateJson.class);

    }

    @Test
    public void testValidDataWithCustomProperty() {
        testRunner.setProperty(ValidateJson.BE_TYPE_PATH_PROP, "custom-type-path-value");
        testRunner.setProperty(ValidateJson.ID_PATH_PROP, "custom-id-path-value");
        testRunner.setProperty(ValidateJson.ERROR_CODE_PROP, "custom-error-code");
        startTestWithValidData();
    }

    @Test
    public void testInvalidDataWithCustomProperty() {
        testRunner.setProperty(ValidateJson.BE_TYPE_PATH_PROP, "_businessEntityType");
        testRunner.setProperty(ValidateJson.ID_PATH_PROP, "_sourceId");
        testRunner.setProperty(ValidateJson.ERROR_CODE_PROP, "ME-JV-0002");
        startTestWithInvalidData();
    }

    @Test
    public void testIfCustomPropertyNotFound() {
        testRunner.setProperty(ValidateJson.BE_TYPE_PATH_PROP, "custom-type-path-value");
        testRunner.setProperty(ValidateJson.ID_PATH_PROP, "custom-id-path-value");
        testRunner.setProperty(ValidateJson.ERROR_CODE_PROP, "custom-error-code");
        startTestWithInvalidData();
    }

    @Test
    public void startTestWithValidData() {
        for (Map.Entry<String, String> entry : testData.getValidJsonSchemaMap().entrySet()) {
            testRunner.setProperty(ValidateJson.SCHEMA, entry.getValue());
            testRunner.enqueue(entry.getKey());
            testRunner.run();

            testTransferToValidRelation();
        }
    }

    @Test
    public void startTestWithInvalidData() {
        for (Map.Entry<String, String> entry : testData.getInvalidJsonSchemaMap().entrySet()) {
            testRunner.setProperty(ValidateJson.SCHEMA, entry.getValue());
            testRunner.enqueue(entry.getKey());
            testRunner.run();

            testTransferToInvalidRelation();
        }
    }


    @Test
    public void checkRelationships() {
        Set<Relationship> relationships = testRunner.getProcessContext().getAvailableRelationships();
        Assertions.assertEquals(3, relationships.size());
        relationships.forEach(r -> Assertions.assertTrue(
                r.getName().equals("valid") ||
                        r.getName().equals("invalid") ||
                        r.getName().equals("not_json")));
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
    public void testSetInvalidSchema() {
        testRunner.setProperty(ValidateJson.SCHEMA, JsonTestData.TEST_INVALID_JSON_SCHEMA_4);
        testRunner.setProperty(ValidateJson.ID_PATH_PROP, "_sourceId");

        assertThrows(AssertionError.class, () -> {
            testRunner.run();
        });


    }

    @Test
    public void testInvalidMonitoringRequest() {
        testRunner.setProperty(ValidateJson.SCHEMA, "{\n" +
                "   \"title\":\"Check Monitoring Request\",\n" +
                "   \"required\":[\n" +
                "      \"entities\",\n" +
                "      \"newStatus\"\n" +
                "   ],\n" +
                "   \"properties\":{\n" +
                "      \"entities\":{\n" +
                "         \"type\":\"array\",\n" +
                "         \"items\":{\n" +
                "            \"type\":\"object\",\n" +
                "            \"required\":[\n" +
                "               \"businessEntityType\",\n" +
                "               \"sourceId\"\n" +
                "            ],\n" +
                "            \"properties\":{\n" +
                "               \"businessEntityType\":{\n" +
                "                  \"type\":\"string\",\n" +
                "                  \"enum\":[\n" +
                "                     \"Customer\",\n" +
                "                     \"Organization\",\n" +
                "                     \"Individual\",\n" +
                "                     \"PartyRole\",\n" +
                "                     \"PartyInteraction\",\n" +
                "                     \"ProductHierarchy\",\n" +
                "                     \"AllCustomerProducts\"\n" +
                "                  ]\n" +
                "               },\n" +
                "               \"sourceId\":{\n" +
                "                  \"type\":\"string\"\n" +
                "               },\n" +
                "               \"_sourceTimeStamp\":{\n" +
                "                  \"type\":\"string\",\n" +
                "                  \"format\":\"date-time\"\n" +
                "               }\n" +
                "            }\n" +
                "         }\n" +
                "      },\n" +
                "      \"newStatus\":{\n" +
                "         \"type\":\"string\",\n" +
                "         \"enum\":[\n" +
                "            \"Prepared\",\n" +
                "            \"Ingested\",\n" +
                "            \"In Progress\",\n" +
                "            \"Processed Successfully\",\n" +
                "            \"Processed with Errors\",\n" +
                "            \"Processed with Warnings\",\n" +
                "            \"Rolled back\",\n" +
                "            \"Failed to rollback\",\n" +
                "            \"Extracted\",\n" +
                "            \"Transformed\"\n" +
                "         ]\n" +
                "      },\n" +
                "      \"requestId\":{\n" +
                "         \"type\":[\n" +
                "            \"string\",\n" +
                "            \"null\"\n" +
                "         ]\n" +
                "      },\n" +
                "      \"sessionId\":{\n" +
                "         \"type\":[\n" +
                "            \"string\",\n" +
                "            \"null\"\n" +
                "         ]\n" +
                "      }\n" +
                "   }\n" +
                "}");
        testRunner.enqueue("{\n" +
                "   \"entities\":\n" +
                "\t\t{\n" +
                "\t\t \"businessEntityType\":\"Customer\",\n" +
                "\t\t \"sourceId\":\"New_Update_Processor_4\",\n" +
                "\t\t \"_sourceTimeStamp\":\"2018-01-31T09:40:15.581Z\"\n" +
                "\t\t}\n" +
                "   ,\n" +
                "   \"newStatus\":\"In Progress\",\n" +
                "   \"sessionId\": \"12\",\n" +
                "   \"requestId\": \"12\"\n" +
                "}");
        testRunner.run();
        List<MockFlowFile> invalidFlows = testRunner.getFlowFilesForRelationship(ValidateJson.REL_INVALID);
        Assertions.assertEquals(1, invalidFlows.size());
    }

    @Test
    public void testMonitoringRequestWithGarbage() {
        testRunner.setProperty(ValidateJson.SCHEMA, "{\n" +
                "   \"title\":\"Check Monitoring Request\",\n" +
                "   \"required\":[\n" +
                "      \"entities\",\n" +
                "      \"newStatus\"\n" +
                "   ],\n" +
                "   \"properties\":{\n" +
                "      \"entities\":{\n" +
                "         \"type\":\"array\",\n" +
                "         \"items\":{\n" +
                "            \"type\":\"object\",\n" +
                "            \"required\":[\n" +
                "               \"businessEntityType\",\n" +
                "               \"sourceId\"\n" +
                "            ],\n" +
                "            \"properties\":{\n" +
                "               \"businessEntityType\":{\n" +
                "                  \"type\":\"string\",\n" +
                "                  \"enum\":[\n" +
                "                     \"Customer\",\n" +
                "                     \"Organization\",\n" +
                "                     \"Individual\",\n" +
                "                     \"PartyRole\",\n" +
                "                     \"PartyInteraction\",\n" +
                "                     \"ProductHierarchy\",\n" +
                "                     \"AllCustomerProducts\"\n" +
                "                  ]\n" +
                "               },\n" +
                "               \"sourceId\":{\n" +
                "                  \"type\":\"string\"\n" +
                "               },\n" +
                "               \"_sourceTimeStamp\":{\n" +
                "                  \"type\":\"string\",\n" +
                "                  \"format\":\"date-time\"\n" +
                "               }\n" +
                "            }\n" +
                "         }\n" +
                "      },\n" +
                "      \"newStatus\":{\n" +
                "         \"type\":\"string\",\n" +
                "         \"enum\":[\n" +
                "            \"Prepared\",\n" +
                "            \"Ingested\",\n" +
                "            \"In Progress\",\n" +
                "            \"Processed Successfully\",\n" +
                "            \"Processed with Errors\",\n" +
                "            \"Processed with Warnings\",\n" +
                "            \"Rolled back\",\n" +
                "            \"Failed to rollback\",\n" +
                "            \"Extracted\",\n" +
                "            \"Transformed\"\n" +
                "         ]\n" +
                "      },\n" +
                "      \"requestId\":{\n" +
                "         \"type\":[\n" +
                "            \"string\",\n" +
                "            \"null\"\n" +
                "         ]\n" +
                "      },\n" +
                "      \"sessionId\":{\n" +
                "         \"type\":[\n" +
                "            \"string\",\n" +
                "            \"null\"\n" +
                "         ]\n" +
                "      }\n" +
                "   }\n" +
                "}");
        testRunner.enqueue("{\n" +
                "   \"entities\":\n" +
                "\t\t[{\n" +
                "\t\t \"businessEntityType\":\"Customer\",\n" +
                "\t\t \"sourceId\":\"New_Update_Processor_4\",\n" +
                "\t\t \"_sourceTimeStamp\":\"2018-01-31T09:40:15.581Z\"\n" +
                "\t\t}]\n" +
                "   ,\n" +
                "   \"newStatus\":\"In Progress\",\n" +
                "   \"sessionId\": \"12\",\n" +
                "   \"requestId\": \"12\"\n" +
                "}ioioiooo");
        testRunner.run();
        testTransferToNotJSONRelation();
    }

    @Test
    public void testNotJson() {
        testRunner.setProperty(ValidateJson.SCHEMA, "{\n" +
                "   \"title\":\"Check Monitoring Request\",\n" +
                "   \"required\":[\n" +
                "      \"entities\",\n" +
                "      \"newStatus\"\n" +
                "   ],\n" +
                "   \"properties\":{\n" +
                "      \"entities\":{\n" +
                "         \"type\":\"array\",\n" +
                "         \"items\":{\n" +
                "            \"type\":\"object\",\n" +
                "            \"required\":[\n" +
                "               \"businessEntityType\",\n" +
                "               \"sourceId\"\n" +
                "            ],\n" +
                "            \"properties\":{\n" +
                "               \"businessEntityType\":{\n" +
                "                  \"type\":\"string\",\n" +
                "                  \"enum\":[\n" +
                "                     \"Customer\",\n" +
                "                     \"Organization\",\n" +
                "                     \"Individual\",\n" +
                "                     \"PartyRole\",\n" +
                "                     \"PartyInteraction\",\n" +
                "                     \"ProductHierarchy\",\n" +
                "                     \"AllCustomerProducts\"\n" +
                "                  ]\n" +
                "               },\n" +
                "               \"sourceId\":{\n" +
                "                  \"type\":\"string\"\n" +
                "               },\n" +
                "               \"_sourceTimeStamp\":{\n" +
                "                  \"type\":\"string\",\n" +
                "                  \"format\":\"date-time\"\n" +
                "               }\n" +
                "            }\n" +
                "         }\n" +
                "      },\n" +
                "      \"newStatus\":{\n" +
                "         \"type\":\"string\",\n" +
                "         \"enum\":[\n" +
                "            \"Prepared\",\n" +
                "            \"Ingested\",\n" +
                "            \"In Progress\",\n" +
                "            \"Processed Successfully\",\n" +
                "            \"Processed with Errors\",\n" +
                "            \"Processed with Warnings\",\n" +
                "            \"Rolled back\",\n" +
                "            \"Failed to rollback\",\n" +
                "            \"Extracted\",\n" +
                "            \"Transformed\"\n" +
                "         ]\n" +
                "      },\n" +
                "      \"requestId\":{\n" +
                "         \"type\":[\n" +
                "            \"string\",\n" +
                "            \"null\"\n" +
                "         ]\n" +
                "      },\n" +
                "      \"sessionId\":{\n" +
                "         \"type\":[\n" +
                "            \"string\",\n" +
                "            \"null\"\n" +
                "         ]\n" +
                "      }\n" +
                "   }\n" +
                "}");
        testRunner.enqueue("ioioiooo");
        testRunner.run();
        testTransferToNotJSONRelation();
        List<MockFlowFile> notJsonFFs = testRunner.getFlowFilesForRelationship(ValidateJson.REL_NOT_JSON);
        assertEquals(1, notJsonFFs.size());
        assertNotNull(notJsonFFs.get(0).getAttribute(ValidateJson.ERROR_ATTR));
        assertTrue(notJsonFFs.get(0).getAttribute(ValidateJson.ERROR_ATTR).matches(
                "Not json content in Flow file: Unrecognized token 'ioioiooo': was expecting "
                        + "\\(JSON String, Number, Array, Object or token 'null', 'true' or 'false'\\)\n"
                        + " at \\[Source: .*; line: 1, column: 9]"));
    }

    private void testTransferToValidRelation() {
        testRunner.assertAllFlowFilesTransferred(ValidateJson.REL_VALID);
    }

    private void testTransferToInvalidRelation() {
        testRunner.assertAllFlowFilesTransferred(ValidateJson.REL_INVALID);
    }

    private void testTransferToNotJSONRelation() {
        testRunner.assertAllFlowFilesTransferred(ValidateJson.REL_NOT_JSON);
    }
}

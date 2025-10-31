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
                r.getName().equals("valid")
                        || r.getName().equals("invalid")
                        || r.getName().equals("not_json")));
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
        testRunner.setProperty(ValidateJson.SCHEMA, """
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
                }""");
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
    public void testMonitoringRequestWithGarbage() {
        testRunner.setProperty(ValidateJson.SCHEMA, """
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
                }""");
        testRunner.enqueue("""
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
                }ioioiooo""");
        testRunner.run();
        testTransferToNotJSONRelation();
    }

    @Test
    public void testNotJson() {
        testRunner.setProperty(ValidateJson.SCHEMA, """
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
                }""");
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

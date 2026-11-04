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

package org.qubership.nifi.processors.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.apache.nifi.documentation.init.NopComponentLog;
import org.apache.nifi.logging.ComponentLog;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JsonValidationHandlerTest {
    private JsonSchema testJsonSchema;
    private JsonNode testJson;
    private ComponentLog nopCopLogger = new NopComponentLog();

    private void initDataForSchema2() {
        this.testJsonSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4)
                .getSchema(JsonTestData.TEST_JSON_SCHEMA_2);
        try {
            this.testJson = new ObjectMapper().readTree(JsonTestData.INVALID_TEST_JSON_BY_SHEMA2);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initDataForAcp() {
        this.testJsonSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4)
                .getSchema(JsonTestData.CUSTOMER_PRODUCT_SCHEMA);

        try {
            this.testJson = new ObjectMapper().readTree(JsonTestData.ALL_CUSTOMER_PRODUCT_REQUEST);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testAddErrorArray() {
        initDataForSchema2();

        JsonValidationHandler validationHandler = new JsonValidationHandler(
                testJson,
                new ProcessorProperty(
                        "_businessEntityType",
                        "_sourceId",
                        "ME-JV-0002",
                        this.testJsonSchema,
                        null),
                nopCopLogger);
        ValidationContext context = validationHandler.process();
        JsonNode validatedJson = context.getValidatedJson();

        JsonNode error = validatedJson.get("_errors");
        assertTrue(error != null && !error.isNull());

        JsonNode code = error.findValue("code");
        assertTrue(code != null && !code.isNull());

        JsonNode title = error.findValue("title");
        assertTrue(title != null && !title.isNull());

        JsonNode source = error.findValue("source");
        assertTrue(source != null && !source.isNull());

        JsonNode details = error.findValue("details");
        assertTrue(details != null && !details.isNull());

        JsonNode sourceId = source.get("_sourceId");
        assertTrue(sourceId != null && !sourceId.isNull());

        JsonNode path = source.get("path");
        assertTrue(path != null && !path.isNull());

    }

    @Test
    public void testAddErrorArrayIfCustomPropNotFound() {
        initDataForSchema2();
        JsonValidationHandler validationHandler = new JsonValidationHandler(
                testJson,
                new ProcessorProperty(
                        "CustomBeType",
                        "CustomSourceID",
                        "CustomErrorCode",
                        this.testJsonSchema,
                        null),
                nopCopLogger);
        ValidationContext context = validationHandler.process();
        JsonNode validatedJson = context.getValidatedJson();

        JsonNode error = validatedJson.get("_errors");
        assertTrue(error != null && !error.isNull());

        JsonNode code = error.findValue("code");
        assertTrue(code != null && !code.isNull());
        assertTrue(code.isValueNode() && code.asText().equals("CustomErrorCode"));

        JsonNode title = error.findValue("title");
        assertTrue(title != null && !title.isNull());
        assertTrue(title.isValueNode());
        assertTrue(title.asText().contains("object"));

        JsonNode source = error.findValue("source");
        assertTrue(source != null && !source.isNull());

        JsonNode details = error.findValue("details");
        assertTrue(details != null && !details.isNull());

        JsonNode customSourceID = source.get("CustomSourceID");
        assertTrue(customSourceID != null && !customSourceID.isNull());
        assertTrue(customSourceID.isValueNode() && customSourceID.asText().equals(""));
    }

    @Test
    public void testUnwrapCustomerProducts() {
        initDataForAcp();

        JsonValidationHandler validationHandler =
                new JsonValidationHandler(this.testJson,
                        new ProcessorProperty(
                                "_businessEntityType",
                                "_sourceId",
                                "ME-JV-0002",
                                this.testJsonSchema,
                                "(\\$\\._items\\[[0-9]+]\\.)"),
                        nopCopLogger);
        ValidationContext context = validationHandler.process();
        JsonNode validatedJson = context.getValidatedJson();

        JsonNode firstError = validatedJson.get("_errors").get(0);
        JsonNode secondError = validatedJson.get("_errors").get(1);

        assertTrue(firstError != null && !firstError.isNull());
        assertTrue(secondError != null && !secondError.isNull());

        JsonNode firstDetails = firstError.get("details");
        assertTrue(firstDetails != null && !firstDetails.isNull());
        JsonNode secondDetails = secondError.get("details");
        assertTrue(secondDetails != null && !secondDetails.isNull());

        JsonNode firstSource = firstError.get("source");

        String firstSourceId = firstSource.get("_sourceId").asText();
        String firstPath = firstSource.get("path").asText();

        assertEquals("PI#TLO_01_CUST#00000002", firstSourceId);
        assertEquals("relatedPartyRef[1].referredType", firstPath);

        JsonNode secondSource = secondError.get("source");
        String secondSourceId = secondSource.get("_sourceId").asText();
        String secondPath = secondSource.get("path").asText();

        assertEquals("PI#TLO_01_CUST#00000002", secondSourceId);
        assertEquals("state", secondPath);
    }


    @Test
    public void testUnwrapLongPath() {
        initDataForAcp();
        JsonValidationHandler validationHandler =
                new JsonValidationHandler(this.testJson,
                        new ProcessorProperty("_businessEntityType",
                                "_sourceRefId",
                                "ME-JV-0002",
                                this.testJsonSchema,
                                "(\\$\\._items\\[[0-9]+\\]\\.relatedPartyRef\\[[0-9]+\\]\\.)"),
                        nopCopLogger);
        ValidationContext context = validationHandler.process();
        JsonNode validatedJson = context.getValidatedJson();

        JsonNode firstError = validatedJson.get("_errors").get(0);
        assertTrue(firstError != null && !firstError.isNull());

        JsonNode details = firstError.findValue("details");
        assertTrue(details != null && !details.isNull());

        JsonNode firstSource = firstError.get("source");
        String firstSourceId = firstSource.get("_sourceRefId").asText();
        String firstPath = firstSource.get("path").asText();

        assertEquals("CUST#00000002", firstSourceId);
        assertEquals("referredType", firstPath);

    }


    @Test
    public void testUnwrapWithInvalidRegEx() {
        initDataForAcp();

        JsonValidationHandler validationHandler =
                new JsonValidationHandler(this.testJson,
                        new ProcessorProperty(
                                "_businessEntityType",
                                "_sourceId",
                                "ME-JV-0002",
                                this.testJsonSchema,
                                "(\\$\\.Invalid-regex\\[[0-9]+]\\.)"),
                        nopCopLogger);
        ValidationContext context = validationHandler.process();
        JsonNode validatedJson = context.getValidatedJson();

        JsonNode firstError = validatedJson.get("_errors").get(0);
        assertTrue(firstError != null && !firstError.isNull());

        JsonNode details = firstError.findValue("details");
        assertTrue(details != null && !details.isNull());

        JsonNode firstSource = firstError.get("source");
        String firstSourceId = firstSource.get("_sourceId").asText();
        String firstPath = firstSource.get("path").asText();

        assertEquals("CUST#00000002", firstSourceId);
        assertEquals("_items[0].relatedPartyRef[1].referredType", firstPath);
    }

    @Test
    public void testUnwrapWithInvalidSourceIdPath() {
        initDataForAcp();

        JsonValidationHandler validationHandler =
                new JsonValidationHandler(this.testJson,
                        new ProcessorProperty(
                                "_businessEntityType",
                                "_sourceId-invalid",
                                "ME-JV-0002",
                                this.testJsonSchema,
                                "(\\$\\._items\\[[0-9]+]\\.)"),
                        nopCopLogger);
        ValidationContext context = validationHandler.process();
        JsonNode validatedJson = context.getValidatedJson();

        JsonNode firstError = validatedJson.get("_errors").get(0);
        assertTrue(firstError != null && !firstError.isNull());

        JsonNode details = firstError.findValue("details");
        assertTrue(details != null && !details.isNull());

        JsonNode firstSource = firstError.get("source");
        String firstSourceId = firstSource.get("_sourceId-invalid").asText();
        String firstPath = firstSource.get("path").asText();

        assertEquals("", firstSourceId);
        assertEquals("relatedPartyRef[1].referredType", firstPath);

    }

    @Test
    public void testUnwrapWithInvalidInstanceSourceIdPath() throws IOException {
        JsonSchema jsonSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4)
                .getSchema(JsonTestData.CUSTOMER_PRODUCT_SCHEMA);
        JsonNode json = new ObjectMapper().readTree(JsonTestData.MEDIUM_ACP_REQUEST_LOSTED_INNER_SOURCE_ID);

        JsonValidationHandler validationHandler =
                new JsonValidationHandler(json,
                        new ProcessorProperty(
                                "_businessEntityType",
                                "_sourceId",
                                "ME-JV-0002",
                                jsonSchema,
                                "(\\$\\._items\\[[0-9]+]\\.)"),
                        nopCopLogger);
        ValidationContext context = validationHandler.process();
        JsonNode validatedJson = context.getValidatedJson();

        JsonNode firstError = validatedJson.get("_errors").get(0);
        assertTrue(firstError != null && !firstError.isNull());

        JsonNode details = firstError.findValue("details");
        assertTrue(details != null && !details.isNull());

        JsonNode firstSource = firstError.get("source");
        String firstSourceId = firstSource.get("_sourceId").asText();
        String firstPath = firstSource.get("path").asText();

        assertEquals("CUST#00000002", firstSourceId);
        assertEquals("_sourceId", firstPath);
    }

    @Test
    public void additionalPropertyPathEndsWithTheExtraProperty() throws IOException {
        String schema = "{\"$schema\": \"http://json-schema.org/draft-04/schema#\", \"type\": \"object\","
                + " \"properties\": {\"o\": {\"type\": \"object\", \"properties\": {\"id\": {}},"
                + " \"additionalProperties\": false}}}";

        assertEquals("o.extra", firstErrorPath(schema, "{\"o\": {\"id\": 1, \"extra\": 2}}"));
    }

    /**
     * The library reports the property that triggered the dependency, not the missing one, so the path stays at the
     * object that holds both.
     */
    @Test
    public void dependencyPathIsTheObjectHoldingTheTriggerProperty() throws IOException {
        String schema = "{\"$schema\": \"http://json-schema.org/draft-04/schema#\", \"type\": \"object\","
                + " \"properties\": {\"o\": {\"type\": \"object\", \"dependencies\": {\"card\": [\"billing\"]}}}}";

        assertEquals("o", firstErrorPath(schema, "{\"o\": {\"card\": 1}}"));
    }

    @Test
    public void typeMismatchPathIsTheMismatchedValue() throws IOException {
        String schema = "{\"$schema\": \"http://json-schema.org/draft-04/schema#\", \"type\": \"object\","
                + " \"properties\": {\"o\": {\"type\": \"string\"}}}";

        assertEquals("o", firstErrorPath(schema, "{\"o\": 1}"));
    }

    private String firstErrorPath(String schema, String json) throws IOException {
        JsonSchema jsonSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4).getSchema(schema);
        JsonValidationHandler validationHandler = new JsonValidationHandler(
                new ObjectMapper().readTree(json),
                new ProcessorProperty("_businessEntityType", "_sourceId", "ME-JV-0002", jsonSchema, null),
                nopCopLogger);

        JsonNode errors = validationHandler.process().getValidatedJson().get("_errors");
        assertEquals(1, errors.size(), "errors: " + errors);
        return errors.get(0).get("source").get("path").asText();
    }
}

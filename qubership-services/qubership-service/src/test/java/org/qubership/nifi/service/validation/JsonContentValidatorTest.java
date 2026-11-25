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

package org.qubership.nifi.service.validation;

import com.networknt.schema.JsonSchemaException;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.NoOpProcessor;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JsonContentValidatorTest {
    private static final String ENTITY_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "id": {"type": "integer"},
                "status": {"enum": ["Active", "Closed"]},
                "updated": {"type": "string", "format": "date-time"}
              },
              "required": ["id"]
            }""";

    private TestRunner testRunner;

    @BeforeEach
    public void init() {
        testRunner = TestRunners.newTestRunner(NoOpProcessor.class);
    }

    private JsonContentValidator enabledValidator(String schema) throws InitializationException {
        JsonContentValidator validator = new JsonContentValidator();
        testRunner.addControllerService("validator", validator);
        testRunner.setProperty(validator, JsonContentValidator.SCHEMA, schema);
        testRunner.enableControllerService(validator);
        return validator;
    }

    private boolean validate(String schema, String json) throws InitializationException, IOException {
        return enabledValidator(schema).validate(json, Map.of());
    }

    @Test
    public void documentMatchingTheSchemaIsValid() throws Exception {
        assertEquals(true, validate(ENTITY_SCHEMA, """
                {"id": 1, "status": "Active", "updated": "2018-02-11T11:27:38.795Z"}"""),
                "validate(id 1, status Active)");
    }

    @Test
    public void wrongTypeIsInvalid() throws Exception {
        assertEquals(false, validate(ENTITY_SCHEMA, """
                {"id": "1"}"""), "validate(id \"1\")");
    }

    @Test
    public void missingRequiredPropertyIsInvalid() throws Exception {
        assertEquals(false, validate(ENTITY_SCHEMA, """
                {"status": "Active"}"""), "validate(no id)");
    }

    @Test
    public void valueOutsideTheEnumIsInvalid() throws Exception {
        assertEquals(false, validate(ENTITY_SCHEMA, """
                {"id": 1, "status": "Open"}"""), "validate(status Open)");
    }

    @Test
    public void invalidDateTimeIsInvalid() throws Exception {
        assertEquals(false, validate(ENTITY_SCHEMA, """
                {"id": 1, "updated": "2018-02-31T11:27:38.795Z"}"""),
                "2018-02-31 against format date-time, which json-schema-validator asserts");
    }

    @Test
    public void schemaWithoutDollarSchemaIsReadAsDraft4() throws Exception {
        assertEquals(false, validate("""
                {"properties": {"count": {"maximum": 10, "exclusiveMaximum": true}}}""", """
                {"count": 10}"""),
                "count 10 against a boolean exclusiveMaximum of 10, which is draft 4 syntax");
    }

    @Test
    public void schemaDeclaringDraft07FailsToEnable() throws InitializationException {
        JsonContentValidator validator = new JsonContentValidator();
        testRunner.addControllerService("validator", validator);
        testRunner.setProperty(validator, JsonContentValidator.SCHEMA, """
                {"$schema": "http://json-schema.org/draft-07/schema#", "type": "object"}""");

        AssertionError error = assertThrows(AssertionError.class,
                () -> testRunner.enableControllerService(validator), "enabling with a draft-07 schema");

        // The test runner reports an exception from the @OnEnabled method only in the message of its AssertionError.
        String expectedReason = JsonSchemaException.class.getName()
                + ": Unknown MetaSchema: https://json-schema.org/draft-07/schema";
        assertTrue(error.getMessage().contains(expectedReason),
                () -> "expected enable failure to contain <%s>, but was <%s>"
                        .formatted(expectedReason, error.getMessage()));
        assertFalse(testRunner.isControllerServiceEnabled(validator), "isControllerServiceEnabled(validator)");
    }

    @Test
    public void schemaIsReadFromAnEnvironmentVariable() throws Exception {
        testRunner.setEnvironmentVariableValue("ENTITY_SCHEMA", ENTITY_SCHEMA);

        assertEquals(false, validate("${ENTITY_SCHEMA}", """
                {"id": "1"}"""), "validate(id \"1\") against ${ENTITY_SCHEMA}");
    }

    @Test
    public void contentThatIsNotJsonThrows() throws Exception {
        JsonContentValidator validator = enabledValidator(ENTITY_SCHEMA);

        assertThrows(IOException.class, () -> validator.validate("not json", Map.of()));
    }
}

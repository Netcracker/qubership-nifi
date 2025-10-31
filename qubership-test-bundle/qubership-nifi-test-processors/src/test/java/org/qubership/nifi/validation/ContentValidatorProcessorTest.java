package org.qubership.nifi.validation;

import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.processor.validation.ContentValidatorProcessor;
import org.qubership.nifi.service.validation.ContentValidator;
import org.qubership.nifi.service.validation.JsonContentValidator;

import java.util.List;

public class ContentValidatorProcessorTest {
    private static final ContentValidator VALIDATOR = new JsonContentValidator();
    private TestRunner testRunner;

    private static final String JSON_SCHEMA = """
            {
              "$id": "https://example.com/test.schema.json",
              "title": "TestEntity",
              "description": "Test entity schema",
              "type": "object",
              "properties": {
                "entityId": {
                  "description": "The unique identifier for an entity",
                  "type": "integer"
                },
                "entityName": {
                  "description": "Name of the entity",
                  "type": "string"
                }
              },
              "required": [ "entityId", "entityName" ]
            }
            """;

    @BeforeEach
    public void setUp() throws InitializationException {
        testRunner = TestRunners.newTestRunner(ContentValidatorProcessor.class);
        testRunner.addControllerService("validator", VALIDATOR);
        testRunner.setProperty(VALIDATOR, JsonContentValidator.SCHEMA, JSON_SCHEMA);
        testRunner.enableControllerService(VALIDATOR);
        testRunner.assertValid(VALIDATOR);
        testRunner.setProperty(ContentValidatorProcessor.CONTENT_VALIDATOR_SERVICE, "validator");
    }

    @AfterEach
    public void tearDown() {
        testRunner.disableControllerService(VALIDATOR);
    }

    @Test
    public void testValid() {
        testRunner.enqueue("""
                {"entityId":1,"entityName":"Name1"}
                """);
        testRunner.run();
        List<MockFlowFile> listSuccess = testRunner.getFlowFilesForRelationship(ContentValidatorProcessor.REL_SUCCESS);
        List<MockFlowFile> listFailure = testRunner.getFlowFilesForRelationship(ContentValidatorProcessor.REL_FAILURE);
        Assertions.assertEquals(0, listFailure.size());
        Assertions.assertEquals(1, listSuccess.size());
    }

    @Test
    public void testInvalid() {
        testRunner.enqueue("""
                {"entityId111":1,"entityName222":"Name1"}
                """);
        testRunner.run();
        List<MockFlowFile> listSuccess = testRunner.getFlowFilesForRelationship(ContentValidatorProcessor.REL_SUCCESS);
        List<MockFlowFile> listFailure = testRunner.getFlowFilesForRelationship(ContentValidatorProcessor.REL_FAILURE);
        Assertions.assertEquals(1, listFailure.size());
        Assertions.assertEquals(0, listSuccess.size());
    }

    @Test
    public void testNonJson() {
        testRunner.enqueue("""
                Some text
                """);
        testRunner.run();
        List<MockFlowFile> listSuccess = testRunner.getFlowFilesForRelationship(ContentValidatorProcessor.REL_SUCCESS);
        List<MockFlowFile> listFailure = testRunner.getFlowFilesForRelationship(ContentValidatorProcessor.REL_FAILURE);
        Assertions.assertEquals(1, listFailure.size());
        Assertions.assertEquals(0, listSuccess.size());
    }
}

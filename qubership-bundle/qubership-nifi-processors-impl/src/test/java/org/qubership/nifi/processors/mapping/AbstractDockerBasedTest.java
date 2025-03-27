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

package org.qubership.nifi.processors.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.qubership.nifi.NiFiUtils.MAPPER;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@Slf4j
public abstract class AbstractDockerBasedTest {
    protected static final String POSTGRES_IMAGE = "postgres:16.8";
    private TestRunner testRunner;

    protected void testJsonContentFromRelationship(String[] idealFiles, Relationship relationship) {
        List<MockFlowFile> outs = testRunner.getFlowFilesForRelationship(relationship);
        if (idealFiles == null || idealFiles.length == 0) {
            //nothing to check
            return;
        }
        assertTrue(outs != null, "No FlowFiles in relationship " + relationship.getName());
        assertEquals(outs.size(), idealFiles.length);
        for (int i = 0; i < outs.size(); i++) {
            try {
                Path path = Paths.get(getClass().getResource(idealFiles[i]).toURI());
                if (!path.toFile().exists()) {
                    fail("Expected content file doesn't exist: " + idealFiles[i]);
                }
                byte[] fileBytes = Files.readAllBytes(path);
                JsonNode expectedResult = MAPPER.readTree(new String(fileBytes));
                JsonNode actualResult = MAPPER.readTree(testRunner.getContentAsByteArray(outs.get(0)));
                assertEquals(expectedResult, actualResult);
            } catch (IOException | URISyntaxException e) {
                log.error("Couldn't read an ideal file: " + idealFiles[i], e);
                fail("Couldn't read an ideal file: " + idealFiles[i]);
            }
        }
    }

    protected void testContentFromRelationship(String[] idealFiles, Relationship relationship) {
        List<MockFlowFile> outs = testRunner.getFlowFilesForRelationship(relationship);
        assertEquals(outs.size(), idealFiles.length);
        for (int i = 0; i < outs.size(); i++) {
            try {
                outs.get(i).assertContentEquals(Paths.get(getClass().getResource(idealFiles[i]).toURI()));
            } catch (IOException | URISyntaxException e) {
                log.error("Couldn't read an ideal file: " + idealFiles[i], e);
                fail("Couldn't read an ideal file: " + idealFiles[i]);
            } catch (AssertionError ex) {
                log.error("Assertion failed. Expected file = " + idealFiles[i], ex);
                //log FF content:
                String actualFFContent = new String(outs.get(i).toByteArray(), StandardCharsets.UTF_8);
                log.error("Actual FF content: {}", actualFFContent);
                throw new AssertionError("FlowFile content doesn't match", ex);
            }
        }
    }

    protected void setProperty(PropertyDescriptor propertyName, String valueFilename) {
        try {
            Path path = Paths.get(getClass().getResource(valueFilename).toURI());
            byte[] fileBytes = Files.readAllBytes(path);
            String value = new String(fileBytes);
            testRunner.setProperty(propertyName, value);
        } catch (URISyntaxException | IOException e) {
            fail("Couldn't read a config file: " + valueFilename);
        }
    }

    protected void setProperty(ControllerService service, PropertyDescriptor propertyName, String valueFileName) {
        try {
            Path path = Paths.get(getClass().getResource(valueFileName).toURI());
            byte[] fileBytes = Files.readAllBytes(path);
            String value = new String(fileBytes);
            testRunner.setProperty(service, propertyName, value);
        } catch (URISyntaxException | IOException e) {
            fail("Couldn't read a config file: " + valueFileName);
        }
    }

    protected void putInQueue(String contentFileName, Map<String, String> attributes) {
        try {
            Path path = Paths.get(getClass().getResource(contentFileName).toURI());
            byte[] fileBytes = Files.readAllBytes(path);
            String ffContent = new String(fileBytes);
            testRunner.enqueue(ffContent, attributes);
        } catch (URISyntaxException | IOException e) {
            fail("Couldn't read a config file: " + contentFileName);
        }
    }

    public static class MockDBCPService extends AbstractControllerService implements DBCPService {
        private final DataSource ds;

        public MockDBCPService(final DataSource newDs) {
            this.ds = newDs;
        }

        @Override
        public String getIdentifier() {
            return "dbcp";
        }

        @Override
        public Connection getConnection() throws ProcessException {
            try {
                return ds.getConnection();
            } catch (final Exception e) {
                throw new ProcessException("getConnection failed: " + e);
            }
        }
    }
}

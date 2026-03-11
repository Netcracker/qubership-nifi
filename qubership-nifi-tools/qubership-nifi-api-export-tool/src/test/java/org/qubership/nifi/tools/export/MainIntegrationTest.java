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

package org.qubership.nifi.tools.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that spin up a real NiFi container via TestContainers,
 * run the full Main pipeline, and verify the output file structure.
 *
 * These tests require Docker and take several minutes each.
 * Run selectively: mvn test -pl qubership-nifi-api-export-tool -Dgroups=docker
 */
@Tag("docker")
class MainIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Temporary directory provided by JUnit for each test. */
    @TempDir
    private Path tempDir;

    @Test
    void testMainNifi2xProducesValidOutput() throws Exception {
        Main.main(new String[]{
            "--version", "2.7.2",
            "--output-dir", tempDir.toString(),
            "--timeout", "90",
            "--port", "18443"
        });

        assertOutputStructure(tempDir.toFile());
    }

    @Test
    void testMainNifi1xProducesValidOutput() throws Exception {
        Main.main(new String[]{
            "--version", "1.28.1",
            "--output-dir", tempDir.toString(),
            "--timeout", "90",
            "--port", "18444"
        });

        assertOutputStructure(tempDir.toFile());
    }

    private void assertOutputStructure(File outDir) throws Exception {
        File processorsDir = new File(outDir, "processors");
        File csDir = new File(outDir, "controllerService");
        File rtDir = new File(outDir, "reportingTask");

        assertTrue(processorsDir.isDirectory(), "processors/ directory must exist");
        assertTrue(csDir.isDirectory(), "controllerService/ directory must exist");
        assertTrue(rtDir.isDirectory(), "reportingTask/ directory must exist");

        assertTrue(processorsDir.list().length > 0, "processors/ must contain at least one file");
        assertTrue(csDir.list().length > 0, "controllerService/ must contain at least one file");
        assertTrue(rtDir.list().length > 0, "reportingTask/ must contain at least one file");

        assertValidJsonFile(processorsDir.listFiles()[0]);
        assertValidJsonFile(csDir.listFiles()[0]);
        assertValidJsonFile(rtDir.listFiles()[0]);
    }

    private void assertValidJsonFile(File jsonFile) throws Exception {
        assertTrue(jsonFile.getName().endsWith(".json"),
                "Output file must have .json extension: " + jsonFile.getName());

        JsonNode root = MAPPER.readTree(jsonFile);

        assertTrue(root.has("type"), "JSON must have 'type' field in: " + jsonFile.getName());
        assertFalse(root.get("type").asText().isEmpty(),
                "'type' must be a non-empty FQCN in: " + jsonFile.getName());

        assertTrue(root.has("propertyDescriptors"),
                "JSON must have 'propertyDescriptors' field in: " + jsonFile.getName());
        assertTrue(root.get("propertyDescriptors").isObject(),
                "'propertyDescriptors' must be a JSON object in: " + jsonFile.getName());
    }
}

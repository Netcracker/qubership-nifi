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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Temporary directory provided by JUnit for each test. */
    @TempDir
    private Path tempDir;

    @Test
    void writeCreatesProcessorsSubdirAndJsonFiles() throws Exception {
        OutputWriter writer = new OutputWriter(tempDir.toString());
        writer.write(ComponentKind.PROCESSOR, buildComponents("org.foo.Foo", "org.foo.Bar"));

        File dir = new File(tempDir.toFile(), "processors");
        assertTrue(dir.exists() && dir.isDirectory());
        assertTrue(new File(dir, "Foo.json").exists());
        assertTrue(new File(dir, "Bar.json").exists());
    }

    @Test
    void writeUsesExactSubdirNamesForAllKinds() throws Exception {
        OutputWriter writer = new OutputWriter(tempDir.toString());
        List<Map<String, Object>> one = buildComponents("org.foo.X");

        writer.write(ComponentKind.PROCESSOR, one);
        writer.write(ComponentKind.CONTROLLER_SERVICE, one);
        writer.write(ComponentKind.REPORTING_TASK, one);

        assertTrue(new File(tempDir.toFile(), "processors").isDirectory());
        assertTrue(new File(tempDir.toFile(), "controllerService").isDirectory());
        assertTrue(new File(tempDir.toFile(), "reportingTask").isDirectory());
    }

    @Test
    void writeFilenameIsSimpleNameOfFqcn() throws Exception {
        OutputWriter writer = new OutputWriter(tempDir.toString());
        writer.write(ComponentKind.PROCESSOR, buildComponents("org.apache.nifi.GenerateFlowFile"));
        writer.write(ComponentKind.CONTROLLER_SERVICE, buildComponents("SimpleName"));

        assertTrue(new File(tempDir.toFile(), "processors/GenerateFlowFile.json").exists());
        assertTrue(new File(tempDir.toFile(), "controllerService/SimpleName.json").exists());
    }

    @Test
    void writeJsonContainsTypeAndPropertyDescriptors() throws Exception {
        OutputWriter writer = new OutputWriter(tempDir.toString());
        JsonNode descriptors = MAPPER.readTree("{\"myProp\":{\"name\":\"myProp\",\"displayName\":\"My Property\"}}");

        Map<String, Object> component = new HashMap<>();
        component.put("type", "org.foo.MyProcessor");
        component.put("propertyDescriptors", descriptors);
        writer.write(ComponentKind.PROCESSOR, List.of(component));

        File jsonFile = new File(tempDir.toFile(), "processors/MyProcessor.json");
        assertTrue(jsonFile.exists());

        JsonNode written = MAPPER.readTree(jsonFile);
        assertEquals("org.foo.MyProcessor", written.get("type").asText());
        assertEquals("myProp", written.path("propertyDescriptors").path("myProp").path("name").asText());
        assertEquals("My Property", written.path("propertyDescriptors").path("myProp").path("displayName").asText());
    }

    @Test
    void writeEmptyListCreatesDirectoryWithNoFiles() throws Exception {
        OutputWriter writer = new OutputWriter(tempDir.toString());
        writer.write(ComponentKind.PROCESSOR, new ArrayList<>());

        File dir = new File(tempDir.toFile(), "processors");
        assertTrue(dir.exists());
        assertEquals(0, dir.list().length);
    }

    @Test
    void writeMultipleKindsToSeparateSubdirs() throws Exception {
        OutputWriter writer = new OutputWriter(tempDir.toString());
        writer.write(ComponentKind.PROCESSOR, buildComponents("org.foo.Proc"));
        writer.write(ComponentKind.CONTROLLER_SERVICE, buildComponents("org.foo.Svc"));
        writer.write(ComponentKind.REPORTING_TASK, buildComponents("org.foo.Task"));

        assertTrue(new File(tempDir.toFile(), "processors/Proc.json").exists());
        assertTrue(new File(tempDir.toFile(), "controllerService/Svc.json").exists());
        assertTrue(new File(tempDir.toFile(), "reportingTask/Task.json").exists());
    }

    // --- helpers ---

    private List<Map<String, Object>> buildComponents(String... fqcns) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String fqcn : fqcns) {
            Map<String, Object> c = new HashMap<>();
            c.put("type", fqcn);
            c.put("propertyDescriptors", MAPPER.readTree("{}"));
            list.add(c);
        }
        return list;
    }
}

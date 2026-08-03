package org.qubership.nifi.tools.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonMappingGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Temporary directory provided by JUnit for each test. */
    @TempDir
    private Path tempDir;

    @Test
    void getOutputPathContainsFileName() {
        JsonMappingGenerator gen = new JsonMappingGenerator(tempDir);
        assertTrue(gen.getOutputPath().endsWith("csPropConfig.json"));
    }

    @Test
    void generateEmptyMapsCreatesEmptyJsonFile() throws IOException {
        JsonMappingGenerator gen = new JsonMappingGenerator(tempDir);
        gen.generate(Map.of(), Map.of());

        assertTrue(Files.exists(Path.of(gen.getOutputPath())),
                "File should be created even when there are no changes");
        JsonNode root = MAPPER.readTree(Path.of(gen.getOutputPath()).toFile());
        assertEquals(0, root.size(), "JSON should be empty object when there are no changes");
    }

    @Test
    void generateIncludesControllerService() throws IOException {
        JsonMappingGenerator gen = new JsonMappingGenerator(tempDir);

        Map<String, Map<String, String>> renames = new HashMap<>();
        renames.put("org.example.MyService", Map.of("old-api", "new-api"));

        Map<String, String> folderMap = Map.of("org.example.MyService", "controllerService");

        gen.generate(renames, folderMap);

        JsonNode root = MAPPER.readTree(Path.of(gen.getOutputPath()).toFile());
        assertTrue(root.has("org.example.MyService"));
        assertEquals("new-api", root.get("org.example.MyService").get("old-api").asText());
    }

    @Test
    void generateIncludesReportingTask() throws IOException {
        JsonMappingGenerator gen = new JsonMappingGenerator(tempDir);

        Map<String, Map<String, String>> renames = new HashMap<>();
        renames.put("org.example.MyTask", Map.of("old-api", "new-api"));

        Map<String, String> folderMap = Map.of("org.example.MyTask", "reportingTask");

        gen.generate(renames, folderMap);

        JsonNode root = MAPPER.readTree(Path.of(gen.getOutputPath()).toFile());
        assertTrue(root.has("org.example.MyTask"));
    }

    @Test
    void generateExcludesProcessors() throws IOException {
        JsonMappingGenerator gen = new JsonMappingGenerator(tempDir);

        Map<String, Map<String, String>> renames = new HashMap<>();
        renames.put("org.example.MyProc", Map.of("old-api", "new-api"));
        renames.put("org.example.MyService", Map.of("s-old", "s-new"));

        Map<String, String> folderMap = Map.of(
                "org.example.MyProc", "processors",
                "org.example.MyService", "controllerService"
        );

        gen.generate(renames, folderMap);

        JsonNode root = MAPPER.readTree(Path.of(gen.getOutputPath()).toFile());
        assertFalse(root.has("org.example.MyProc"),
                "Processors should be excluded from type mapping JSON");
    }

    @Test
    void generateMixedTypesFiltersCorrectly() throws IOException {
        JsonMappingGenerator gen = new JsonMappingGenerator(tempDir);

        Map<String, Map<String, String>> renames = new HashMap<>();
        renames.put("org.example.MyProc", Map.of("p-old", "p-new"));
        renames.put("org.example.MyService", Map.of("s-old", "s-new"));
        renames.put("org.example.MyTask", Map.of("t-old", "t-new"));

        Map<String, String> folderMap = Map.of(
                "org.example.MyProc", "processors",
                "org.example.MyService", "controllerService",
                "org.example.MyTask", "reportingTask"
        );

        gen.generate(renames, folderMap);

        JsonNode root = MAPPER.readTree(Path.of(gen.getOutputPath()).toFile());
        assertEquals(2, root.size(), "Only controllerService and reportingTask expected");
        assertFalse(root.has("org.example.MyProc"));
        assertTrue(root.has("org.example.MyService"));
        assertTrue(root.has("org.example.MyTask"));
    }

    @Test
    void generateMultipleRenamesPerType() throws IOException {
        JsonMappingGenerator gen = new JsonMappingGenerator(tempDir);

        Map<String, String> serviceRenames = new HashMap<>();
        serviceRenames.put("old-a", "new-a");
        serviceRenames.put("old-b", "new-b");
        serviceRenames.put("old-c", "new-c");

        Map<String, Map<String, String>> renames = new HashMap<>();
        renames.put("org.example.Svc", serviceRenames);

        Map<String, String> folderMap = Map.of("org.example.Svc", "controllerService");

        gen.generate(renames, folderMap);

        JsonNode root = MAPPER.readTree(Path.of(gen.getOutputPath()).toFile());
        JsonNode svcNode = root.get("org.example.Svc");
        assertEquals(3, svcNode.size());
        assertEquals("new-a", svcNode.get("old-a").asText());
        assertEquals("new-b", svcNode.get("old-b").asText());
        assertEquals("new-c", svcNode.get("old-c").asText());
    }

    @Test
    void generateTypeWithoutFolderMappingIsIncluded() throws IOException {
        JsonMappingGenerator gen = new JsonMappingGenerator(tempDir);

        Map<String, Map<String, String>> renames = new HashMap<>();
        renames.put("org.example.Unknown", Map.of("old", "new"));

        // empty folder map - folder is null
        gen.generate(renames, Map.of());

        JsonNode root = MAPPER.readTree(Path.of(gen.getOutputPath()).toFile());
        // folder == null -> condition (folder != null && !INCLUDED) is false -> type is included
        assertTrue(root.has("org.example.Unknown"));
    }

    @Test
    void processorGeneratorOutputPathContainsProcessorFileName() {
        JsonMappingGenerator gen = new JsonMappingGenerator(
                tempDir, "procPropConfig.json", Set.of("processors"));
        assertTrue(gen.getOutputPath().endsWith("procPropConfig.json"));
    }

    @Test
    void processorGeneratorIncludesProcessorRenamesAndDeletions() throws IOException {
        JsonMappingGenerator gen = new JsonMappingGenerator(
                tempDir, "procPropConfig.json", Set.of("processors"));

        Map<String, String> changes = new HashMap<>();
        changes.put("old-api", "new-api");
        changes.put("gone-api", null);
        Map<String, Map<String, String>> renames = Map.of("org.example.MyProc", changes);
        Map<String, String> folderMap = Map.of("org.example.MyProc", "processors");

        gen.generate(renames, folderMap);

        JsonNode root = MAPPER.readTree(Path.of(gen.getOutputPath()).toFile());
        JsonNode procNode = root.get("org.example.MyProc");
        assertTrue(procNode != null && procNode.has("old-api"));
        assertEquals("new-api", procNode.get("old-api").asText());
        assertTrue(procNode.get("gone-api").isNull());
    }

    @Test
    void processorGeneratorExcludesControllerServiceAndReportingTask() throws IOException {
        JsonMappingGenerator gen = new JsonMappingGenerator(
                tempDir, "procPropConfig.json", Set.of("processors"));

        Map<String, Map<String, String>> renames = new HashMap<>();
        renames.put("org.example.MyProc", Map.of("p-old", "p-new"));
        renames.put("org.example.MyService", Map.of("s-old", "s-new"));
        renames.put("org.example.MyTask", Map.of("t-old", "t-new"));

        Map<String, String> folderMap = Map.of(
                "org.example.MyProc", "processors",
                "org.example.MyService", "controllerService",
                "org.example.MyTask", "reportingTask"
        );

        gen.generate(renames, folderMap);

        JsonNode root = MAPPER.readTree(Path.of(gen.getOutputPath()).toFile());
        assertEquals(1, root.size(), "Only processors expected");
        assertTrue(root.has("org.example.MyProc"));
        assertFalse(root.has("org.example.MyService"));
        assertFalse(root.has("org.example.MyTask"));
    }
}

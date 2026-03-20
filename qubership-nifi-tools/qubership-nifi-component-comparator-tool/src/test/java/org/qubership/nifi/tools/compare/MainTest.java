package org.qubership.nifi.tools.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link Main}.
 * Builds file structures, invokes main(), verifies output files.
 */
class MainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Temporary directory provided by JUnit for each test. */
    @TempDir
    private Path tempDir;

    private Path sourceDir;
    private Path targetDir;
    private Path outputDir;

    @BeforeEach
    void setUp() throws IOException {
        sourceDir = tempDir.resolve("source");
        targetDir = tempDir.resolve("target");
        outputDir = tempDir.resolve("output");
        for (String sub : List.of("processors", "controllerService", "reportingTask")) {
            Files.createDirectories(sourceDir.resolve(sub));
            Files.createDirectories(targetDir.resolve(sub));
        }
    }

    private void writeJson(Path root, String subfolder, String fileName,
                           String type, String propsJson) throws IOException {
        String json = String.format(
                "{\"type\":\"%s\",\"propertyDescriptors\":{%s}}", type, propsJson);
        Files.writeString(root.resolve(subfolder).resolve(fileName), json);
    }

    private String prop(String apiName, String displayName) {
        return String.format(
                "\"%s\":{\"name\":\"%s\",\"displayName\":\"%s\",\"description\":\"\"}",
                apiName, apiName, displayName);
    }

    private void runMain(String... extraArgs) {
        String[] baseArgs = {
                "--sourceDir", sourceDir.toString(),
                "--targetDir", targetDir.toString(),
                "--outputPath", outputDir.toString()
        };
        String[] allArgs = new String[baseArgs.length + extraArgs.length];
        System.arraycopy(baseArgs, 0, allArgs, 0, baseArgs.length);
        System.arraycopy(extraArgs, 0, allArgs, baseArgs.length, extraArgs.length);
        Main.main(allArgs);
    }

    private Path csvPath() {
        return outputDir.resolve("NiFiComponentsDelta.csv");
    }

    private Path jsonPath() {
        return outputDir.resolve("NiFiTypeMapping.json");
    }

    @Test
    void mainCreatesOutputDirectory() throws IOException {
        writeJson(sourceDir, "processors", "P.json", "org.example.P", prop("a", "A"));
        writeJson(targetDir, "processors", "P.json", "org.example.P", prop("a", "A"));

        runMain();

        assertTrue(Files.exists(outputDir));
        assertTrue(Files.exists(csvPath()));
        assertTrue(Files.exists(jsonPath()));
    }

    @Test
    void mainIdenticalComponentsHeaderOnlyCsv() throws IOException {
        String props = prop("p1", "Prop");
        writeJson(sourceDir, "processors", "P.json", "org.example.P", props);
        writeJson(targetDir, "processors", "P.json", "org.example.P", props);

        runMain();

        List<String> lines = Files.readAllLines(csvPath());
        assertEquals(1, lines.size(), "Only header expected");
    }

    @Test
    void mainRenamedApiNameAppearsInCsvAndJson() throws IOException {
        writeJson(sourceDir, "controllerService", "Svc.json",
                "org.example.Svc", prop("old-api", "Display"));
        writeJson(targetDir, "controllerService", "Svc.json",
                "org.example.Svc", prop("new-api", "Display"));

        runMain();

        // CSV check
        String csv = Files.readString(csvPath());
        assertTrue(csv.contains("rename"));
        assertTrue(csv.contains("old-api"));
        assertTrue(csv.contains("new-api"));
        assertTrue(csv.contains("controllerService"));

        // JSON check
        JsonNode json = MAPPER.readTree(jsonPath().toFile());
        assertTrue(json.has("org.example.Svc"));
        assertEquals("new-api", json.get("org.example.Svc").get("old-api").asText());
    }

    @Test
    void mainProcessorsRenameInCsvButNotInJson() throws IOException {
        writeJson(sourceDir, "processors", "Proc.json",
                "org.example.Proc", prop("old-api", "Display"));
        writeJson(targetDir, "processors", "Proc.json",
                "org.example.Proc", prop("new-api", "Display"));

        runMain();

        // CSV should contain the rename
        String csv = Files.readString(csvPath());
        assertTrue(csv.contains("rename"));
        assertTrue(csv.contains("processors"));

        // JSON should NOT contain processors
        JsonNode json = MAPPER.readTree(jsonPath().toFile());
        assertFalse(json.has("org.example.Proc"),
                "Processors must be excluded from NiFiTypeMapping.json");
    }

    @Test
    void mainDeletedPropertyAppearsInCsv() throws IOException {
        writeJson(sourceDir, "processors", "P.json", "org.example.P",
                prop("keep", "Keep") + "," + prop("gone", "Gone"));
        writeJson(targetDir, "processors", "P.json", "org.example.P",
                prop("keep", "Keep"));

        runMain();

        String csv = Files.readString(csvPath());
        assertTrue(csv.contains("deleted"));
        assertTrue(csv.contains("gone"));
    }

    @Test
    void mainAddedPropertyAppearsInCsv() throws IOException {
        writeJson(sourceDir, "processors", "P.json", "org.example.P",
                prop("keep", "Keep"));
        writeJson(targetDir, "processors", "P.json", "org.example.P",
                prop("keep", "Keep") + "," + prop("added", "Added"));

        runMain();

        String csv = Files.readString(csvPath());
        assertTrue(csv.contains("added"));
    }

    @Test
    void mainWithDictionaryMatchesMappedNames() throws IOException {
        writeJson(sourceDir, "processors", "Proc.json",
                "org.example.Proc", prop("same-api", "Old Name"));
        writeJson(targetDir, "processors", "Proc.json",
                "org.example.Proc", prop("same-api", "New Name"));

        Path dictFile = tempDir.resolve("dict.yaml");
        Files.writeString(dictFile,
                """
                        displayNameMapping:
                        - Proc:
                          old name: New Name""");

        runMain("--dictionaryPath", dictFile.toString());

        List<String> lines = Files.readAllLines(csvPath());
        assertEquals(1, lines.size(), "Dictionary match should produce no changes");
    }

    @Test
    void mainEmptyDirectoriesProducesHeaderOnlyCsv() throws IOException {
        runMain();

        assertTrue(Files.exists(csvPath()));
        List<String> lines = Files.readAllLines(csvPath());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("Component Name"));
    }

    @Test
    void mainEmptyDirectoriesProducesEmptyJson() throws IOException {
        runMain();

        JsonNode json = MAPPER.readTree(jsonPath().toFile());
        assertEquals(0, json.size());
    }

    @Test
    void mainSllSubfoldersProcessedTogether() throws IOException {
        writeJson(sourceDir, "processors", "P.json",
                "org.example.P", prop("p-old", "PD"));
        writeJson(targetDir, "processors", "P.json",
                "org.example.P", prop("p-new", "PD"));

        writeJson(sourceDir, "controllerService", "S.json",
                "org.example.S", prop("s-old", "SD"));
        writeJson(targetDir, "controllerService", "S.json",
                "org.example.S", prop("s-new", "SD"));

        writeJson(sourceDir, "reportingTask", "T.json",
                "org.example.T", prop("t-old", "TD"));
        writeJson(targetDir, "reportingTask", "T.json",
                "org.example.T", prop("t-new", "TD"));

        runMain();

        String csv = Files.readString(csvPath());
        assertTrue(csv.contains("processors"));
        assertTrue(csv.contains("controllerService"));
        assertTrue(csv.contains("reportingTask"));

        // JSON should only have CS and RT
        JsonNode json = MAPPER.readTree(jsonPath().toFile());
        assertEquals(2, json.size());
        assertTrue(json.has("org.example.S"));
        assertTrue(json.has("org.example.T"));
        assertFalse(json.has("org.example.P"));
    }

}

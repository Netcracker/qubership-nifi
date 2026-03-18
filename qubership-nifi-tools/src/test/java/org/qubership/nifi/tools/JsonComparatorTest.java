package org.qubership.nifi.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.JsonComparator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


public class JsonComparatorTest {

    @TempDir
    Path tempDir;

    private static ObjectMapper objectMapper;
    private JsonComparator comparator;

    private Path sourceDir;
    private Path targetDir;
    private Path dictionaryFile;

    @BeforeAll
    static void setUpAll() {
        objectMapper = new ObjectMapper();
    }

    @BeforeEach
    void setUp() throws IOException {
        comparator = new JsonComparator();

        sourceDir = tempDir.resolve("source");
        targetDir = tempDir.resolve("target");

        Files.createDirectories(sourceDir.resolve("processors"));
        Files.createDirectories(sourceDir.resolve("controllerService"));
        Files.createDirectories(sourceDir.resolve("reportingTask"));

        Files.createDirectories(targetDir.resolve("processors"));
        Files.createDirectories(targetDir.resolve("controllerService"));
        Files.createDirectories(targetDir.resolve("reportingTask"));

        dictionaryFile = tempDir.resolve("dictionary.yaml");
    }

    @Test
    void loadWithValidPathsSucceeds() throws IOException {
        createTestJsonFile(sourceDir, "TestProcessor.json", "TestProcessor");
        createTestJsonFile(targetDir, "TestProcessor.json", "TestProcessor");

        assertThatCode(() -> comparator.load(
                sourceDir.toString(),
                targetDir.toString(),
                null
        )).doesNotThrowAnyException();

        assertThat(comparator.getSourceJsonMap()).hasSize(1);
        assertThat(comparator.getTargetJsonMap()).hasSize(1);
    }

    @Test
    void loadWithNonExistentDirectoryThrowsIOException() {
        assertThatThrownBy(() -> comparator.load(
                "/non/existent/path",
                targetDir.toString(),
                null
        )).isInstanceOf(IOException.class)
                .hasMessageContaining("Directory not found");
    }

    @Test
    void loadWithDictionaryFileSucceeds() throws IOException {
        createTestJsonFile(sourceDir, "TestProcessor.json", "TestProcessor");
        createTestJsonFile(targetDir, "TestProcessor.json", "TestProcessor");
        createDictionaryFile();

        assertThatCode(() -> comparator.load(
                sourceDir.toString(),
                targetDir.toString(),
                dictionaryFile.toString()
        )).doesNotThrowAnyException();

        assertThat(comparator.isLoaded()).isTrue();
    }

    @Test
    void loadWithNonExistentDictionaryThrowsIOException() throws IOException {
        createTestJsonFile(sourceDir, "TestProcessor.json", "TestProcessor");
        createTestJsonFile(targetDir, "TestProcessor.json", "TestProcessor");

        assertThatThrownBy(() -> comparator.load(
                sourceDir.toString(),
                targetDir.toString(),
                "/non/existent/dictionary.yaml"
        )).isInstanceOf(IOException.class)
                .hasMessageContaining("Dictionary file does not exist");
    }

    @Test
    void loadMultipleTimesClearsPreviousData() throws IOException {
        createTestJsonFile(sourceDir, "Processor1.json", "Processor1");
        createTestJsonFile(targetDir, "Processor1.json", "Processor1");

        comparator.load(sourceDir.toString(), targetDir.toString(), null);
        int firstSize = comparator.getSourceJsonMap().size();

        Files.delete(sourceDir.resolve("processors/Processor1.json"));
        createTestJsonFile(sourceDir, "Processor2.json", "Processor2");

        comparator.load(sourceDir.toString(), targetDir.toString(), null);

        assertThat(comparator.getSourceJsonMap()).hasSize(1);
        assertThat(comparator.getSourceJsonMap()).containsKey("Processor2.json");
    }

    @Test
    void compareWithoutLoadThrowsIllegalStateException() {
        assertThatThrownBy(() -> comparator.compare())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Data not loaded");
    }


    @Test
    void compareFindsDeletedFiles() throws IOException {
        createTestJsonFileWithProperty(
                sourceDir, "DeletedProcessor.json", "DeletedProcessor", "oldName", "Test Property");
        createTestJsonFileWithProperty(
                targetDir, "DeletedProcessor.json", "DeletedProcessor", "newName", "Test Property");

        comparator.load(sourceDir.toString(), targetDir.toString(), null);
        comparator.compare();

        String csvContent = Files.readString(Path.of("NiFiComponentsDelta.csv"));

        assertThat(csvContent).contains("DeletedProcessor");
        assertThat(csvContent).doesNotContain("DeletedProcessor.json");

        assertThat(csvContent).contains("rename");
        assertThat(csvContent).contains("oldName");
        assertThat(csvContent).contains("newName");
    }

    @Test
    void compareFindsNewFiles() throws IOException {
        createTestJsonFileWithProperty(
                sourceDir, "NewProcessor.json", "NewProcessor", "oldName", "Test Property");
        createTestJsonFileWithProperty(
                targetDir, "NewProcessor.json", "NewProcessor", "newName", "Test Property");

        comparator.load(sourceDir.toString(), targetDir.toString(), null);
        comparator.compare();

        String csvContent = Files.readString(Path.of("NiFiComponentsDelta.csv"));

        assertThat(csvContent).contains("NewProcessor");
        assertThat(csvContent).doesNotContain("NewProcessor.json");

        assertThat(csvContent).contains("rename");
        assertThat(csvContent).contains("oldName");
        assertThat(csvContent).contains("newName");
    }

    @Test
    void compareFindsRenamedProperties() throws IOException {
        createTestJsonFileWithProperty(sourceDir, "TestProcessor.json", "TestProcessor", "oldName", "Test Property");
        createTestJsonFileWithProperty(targetDir, "TestProcessor.json", "TestProcessor", "newName", "Test Property");

        comparator.load(sourceDir.toString(), targetDir.toString(), null);
        comparator.compare();

        String csvContent = Files.readString(Path.of("NiFiComponentsDelta.csv"));
        assertThat(csvContent).contains("rename");
        assertThat(csvContent).contains("oldName");
        assertThat(csvContent).contains("newName");
    }

    @Test
    void compareCsvFilenameWithoutJsonExtension() throws IOException {
        createTestJsonFileWithProperty(
                sourceDir, "MyProcessor.json", "MyProcessor", "oldName", "Test Property");
        createTestJsonFileWithProperty(
                targetDir, "MyProcessor.json", "MyProcessor", "newName", "Test Property");

        comparator.load(sourceDir.toString(), targetDir.toString(), null);
        comparator.compare();

        String csvContent = Files.readString(Path.of("NiFiComponentsDelta.csv"));

        assertThat(csvContent).contains("MyProcessor");
        assertThat(csvContent).doesNotContain("MyProcessor.json");

        assertThat(csvContent).contains("rename");
        assertThat(csvContent).contains("oldName");
        assertThat(csvContent).contains("newName");
    }

    @Test
    void generateJsonWithoutLoadThrowsIllegalStateException() {
        assertThatThrownBy(() -> comparator.generateTypeMappingJson())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Data not loaded");
    }

    @Test
    void generateJsonCreatesFile() throws IOException {
        createTestJsonFileWithProperty(sourceDir, "TestProcessor.json", "TestProcessor", "oldName", "Test Property");
        createTestJsonFileWithProperty(targetDir, "TestProcessor.json", "TestProcessor", "newName", "Test Property");

        comparator.load(sourceDir.toString(), targetDir.toString(), null);
        comparator.compare();
        comparator.generateTypeMappingJson();

        Path jsonFile = Path.of("NiFiTypeMapping.json");
        assertThat(jsonFile).exists();

        String jsonContent = Files.readString(jsonFile);
        assertThat(jsonContent).contains("oldName");
        assertThat(jsonContent).contains("newName");
    }

    @Test
    void generateJsonFromComparisonDataNotFromCsv() throws IOException {
        createTestJsonFileWithProperty(sourceDir, "TestProcessor.json", "TestProcessor", "oldName", "Test Property");
        createTestJsonFileWithProperty(targetDir, "TestProcessor.json", "TestProcessor", "newName", "Test Property");

        comparator.load(sourceDir.toString(), targetDir.toString(), null);
        comparator.compare();

        Files.deleteIfExists(Path.of("NiFiComponentsDelta.csv"));

        assertThatCode(() -> comparator.generateTypeMappingJson()).doesNotThrowAnyException();
        assertThat(Path.of("NiFiTypeMapping.json")).exists();
    }

    @Test
    void dictionaryConsideredInComparison() throws IOException {
        String dictionaryContent = """
                displayNameMapping:
                  - TestProcessor:
                      Old Display Name: New Display Name
                """;
        Files.writeString(dictionaryFile, dictionaryContent);

        createTestJsonFileWithProperty(sourceDir, "TestProcessor.json", "TestProcessor", "apiName1", "Old Display Name");
        createTestJsonFileWithProperty(targetDir, "TestProcessor.json", "TestProcessor", "apiName1", "New Display Name");

        comparator.load(sourceDir.toString(), targetDir.toString(), dictionaryFile.toString());
        comparator.compare();

        String csvContent = Files.readString(Path.of("NiFiComponentsDelta.csv"));
        assertThat(csvContent).doesNotContain("rename");
    }

    @Test
    void dictionaryWrongExtensionThrowsIOException() throws IOException {
        Path wrongDictFile = tempDir.resolve("dictionary.txt");
        Files.writeString(wrongDictFile, "content");

        createTestJsonFile(sourceDir, "TestProcessor.json", "TestProcessor");
        createTestJsonFile(targetDir, "TestProcessor.json", "TestProcessor");

        assertThatThrownBy(() -> comparator.load(
                sourceDir.toString(),
                targetDir.toString(),
                wrongDictFile.toString()
        )).isInstanceOf(IOException.class)
                .hasMessageContaining("must have .yaml or .yml extension");
    }

    @Test
    void invalidJsonFileLoggedAsWarning() throws IOException {
        Path invalidJson = sourceDir.resolve("processors/Invalid.json");
        Files.writeString(invalidJson, "{ invalid json content");

        assertThatCode(() -> comparator.load(
                sourceDir.toString(),
                targetDir.toString(),
                null
        )).doesNotThrowAnyException();
    }

    @Test
    void missingPropertyDescriptorsNoError() throws IOException {
        String jsonWithoutProps = """
                {
                    "type": "TestProcessor",
                    "otherField": "value"
                }
                """;

        Files.writeString(sourceDir.resolve("processors/Test.json"), jsonWithoutProps);
        Files.writeString(targetDir.resolve("processors/Test.json"), jsonWithoutProps);

        assertThatCode(() -> comparator.load(
                sourceDir.toString(),
                targetDir.toString(),
                null
        )).doesNotThrowAnyException();
    }

    @Test
    void missingTypeFieldNoError() throws IOException {
        String jsonWithoutType = """
                {
                    "propertyDescriptors": {
                        "Test Property": {
                            "name": "testName",
                            "displayName": "Test Property"
                        }
                    }
                }
                """;

        Files.writeString(sourceDir.resolve("processors/Test.json"), jsonWithoutType);
        Files.writeString(targetDir.resolve("processors/Test.json"), jsonWithoutType);

        assertThatCode(() -> comparator.load(
                sourceDir.toString(),
                targetDir.toString(),
                null
        )).doesNotThrowAnyException();
    }

    @AfterAll
    static void tearDownAll() {
        try {
            Files.deleteIfExists(Path.of("NiFiComponentsDelta.csv"));
            Files.deleteIfExists(Path.of("NiFiTypeMapping.json"));
        } catch (IOException e) {
            System.err.println("Warning: Could not delete test output files: " + e.getMessage());
        }
    }


    private void createTestJsonFile(Path rootDir, String fileName, String componentType) throws IOException {
        String jsonContent = """
            {
                "type": "%s",
                "propertyDescriptors": {
                    "Test Property": {
                        "name": "testName",
                        "displayName": "Test Property"
                    }
                }
            }
            """.formatted(componentType);

        Path processorsDir = rootDir.resolve("processors");
        Files.createDirectories(processorsDir);
        Files.writeString(processorsDir.resolve(fileName), jsonContent);
    }

    private void createTestJsonFileWithProperty(Path rootDir, String fileName, String componentType,
                                                String propertyName, String displayName) throws IOException {
        String jsonContent = """
            {
                "type": "%s",
                "propertyDescriptors": {
                    "%s": {
                        "name": "%s",
                        "displayName": "%s"
                    }
                }
            }
            """.formatted(componentType, displayName, propertyName, displayName);

        Path processorsDir = rootDir.resolve("processors");
        Files.createDirectories(processorsDir);
        Files.writeString(processorsDir.resolve(fileName), jsonContent);
    }

    private void createDictionaryFile() throws IOException {
        String dictionaryContent = """
            displayNameMapping:
              - TestProcessor:
                  Old Name: New Name
            """;
        Files.writeString(dictionaryFile, dictionaryContent);
    }
}

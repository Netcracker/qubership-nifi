package org.qubership.nifi.utils;

import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.ComponentType;
import org.qubership.nifi.CustomComponentEntity;
import org.qubership.nifi.PropertyDescriptorEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Tests for {@link MarkdownUtils}. */
class MarkdownUtilsTest {

    private static final String ALL_MARKERS_TEMPLATE =
            "# User Guide\n"
            + "\n"
            + "## Processors\n"
            + "\n"
            + "<!-- Table for additional processors. DO NOT REMOVE. -->\n"
            + "\n"
            + "## Processors Properties\n"
            + "\n"
            + "<!-- Additional processors properties description. DO NOT REMOVE. -->\n"
            + "<!-- End of additional processors properties description. DO NOT REMOVE. -->\n"
            + "\n"
            + "## Controller Services\n"
            + "\n"
            + "<!-- Table for additional controller services. DO NOT REMOVE. -->\n"
            + "\n"
            + "## Controller Services Properties\n"
            + "\n"
            + "<!-- Additional controller services description. DO NOT REMOVE. -->\n"
            + "<!-- End of additional controller services description. DO NOT REMOVE. -->\n"
            + "\n"
            + "## Reporting Tasks\n"
            + "\n"
            + "<!-- Table for additional reporting tasks. DO NOT REMOVE. -->\n"
            + "\n"
            + "## Reporting Tasks Properties\n"
            + "\n"
            + "<!-- Additional reporting tasks description. DO NOT REMOVE. -->\n"
            + "<!-- End of additional reporting tasks description. DO NOT REMOVE. -->\n";

    @TempDir
    private Path tempDir;

    /** Returns the temporary directory used by this test. */
    Path getTempDir() {
        return tempDir;
    }

    private Log mockLog() {
        return mock(Log.class);
    }

    private Path writeTemplate(String content) throws IOException {
        Path file = tempDir.resolve("template.md");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    // --- Sunny day tests ---

    /** Verifies construction with a valid path does not throw. */
    @Test
    void testConstructorWithValidPath() {
        Path path = tempDir.resolve("test.md");
        assertDoesNotThrow(() -> new MarkdownUtils(path, mockLog()));
    }

    /** Verifies readFile() succeeds for an existing file. */
    @Test
    void testReadFileReadsExistingFile() throws Exception {
        Path file = writeTemplate(ALL_MARKERS_TEMPLATE);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        assertDoesNotThrow(utils::readFile);
    }

    /** Verifies generateTable() inserts a header row and a data row for a processor. */
    @Test
    void testGenerateTableForProcessorInsertsHeaderAndRows() throws Exception {
        Path file = writeTemplate(ALL_MARKERS_TEMPLATE);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        utils.readFile();

        CustomComponentEntity entity = new CustomComponentEntity(
                "MyProcessor", ComponentType.PROCESSOR, "my-nar", "A processor", Collections.emptyList());
        utils.generateTable(Collections.singletonList(entity), ComponentType.PROCESSOR);
        utils.writeToFile();

        String result = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(result.contains("| Processor "), "Should contain processor header");
        assertTrue(result.contains("MyProcessor"), "Should contain processor name");
        assertTrue(result.contains("my-nar"), "Should contain nar name");
    }

    /** Verifies generateTable() appends new rows to an existing table. */
    @Test
    void testGenerateTableForProcessorAppendsRowsToExistingTable() throws Exception {
        String templateWithTable =
                "## Processors\n"
                + "\n"
                + "<!-- Table for additional processors. DO NOT REMOVE. -->\n"
                + "\n"
                + "| Processor  | NAR                 | Description        |\n"
                + "|----------------------|--------------------|--------------------|\n"
                + "| ExistingProcessor | existing-nar | Existing description |\n"
                + "\n"
                + "<!-- Additional processors properties description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional processors properties description. DO NOT REMOVE. -->\n"
                + "<!-- Table for additional controller services. DO NOT REMOVE. -->\n"
                + "<!-- Additional controller services description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional controller services description. DO NOT REMOVE. -->\n"
                + "<!-- Table for additional reporting tasks. DO NOT REMOVE. -->\n"
                + "<!-- Additional reporting tasks description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional reporting tasks description. DO NOT REMOVE. -->\n";

        Path file = writeTemplate(templateWithTable);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        utils.readFile();

        CustomComponentEntity entity = new CustomComponentEntity(
                "NewProcessor", ComponentType.PROCESSOR, "new-nar", "New description", Collections.emptyList());
        utils.generateTable(Collections.singletonList(entity), ComponentType.PROCESSOR);
        utils.writeToFile();

        String result = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(result.contains("ExistingProcessor"), "Should still contain existing processor");
        assertTrue(result.contains("NewProcessor"), "Should contain new processor");
    }

    /** Verifies generateTable() inserts a controller service table. */
    @Test
    void testGenerateTableForControllerServiceInsertsTable() throws Exception {
        Path file = writeTemplate(ALL_MARKERS_TEMPLATE);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        utils.readFile();

        CustomComponentEntity entity = new CustomComponentEntity(
                "MyService", ComponentType.CONTROLLER_SERVICE, "my-service-nar", "A service", Collections.emptyList());
        utils.generateTable(Collections.singletonList(entity), ComponentType.CONTROLLER_SERVICE);
        utils.writeToFile();

        String result = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(result.contains("| Controller Service "), "Should contain controller service header");
        assertTrue(result.contains("MyService"), "Should contain service name");
    }

    /** Verifies generateTable() inserts a reporting task table. */
    @Test
    void testGenerateTableForReportingTaskInsertsTable() throws Exception {
        Path file = writeTemplate(ALL_MARKERS_TEMPLATE);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        utils.readFile();

        CustomComponentEntity entity = new CustomComponentEntity(
                "MyTask", ComponentType.REPORTING_TASK, "my-task-nar", "A task", Collections.emptyList());
        utils.generateTable(Collections.singletonList(entity), ComponentType.REPORTING_TASK);
        utils.writeToFile();

        String result = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(result.contains("| Reporting Task "), "Should contain reporting task header");
        assertTrue(result.contains("MyTask"), "Should contain task name");
    }

    /** Verifies generateTable() with an empty component list inserts only the header row. */
    @Test
    void testGenerateTableWithEmptyComponentListInsertsHeaderOnly() throws Exception {
        Path file = writeTemplate(ALL_MARKERS_TEMPLATE);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        utils.readFile();

        utils.generateTable(Collections.emptyList(), ComponentType.PROCESSOR);
        utils.writeToFile();

        String result = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(result.contains("| Processor "), "Should contain processor header");
        assertTrue(result.contains("|----------------------|"), "Should contain separator row");
    }

    /** Verifies generatePropertyDescription() inserts component heading and property details. */
    @Test
    void testGeneratePropertyDescriptionForProcessorInsertsContent() throws Exception {
        Path file = writeTemplate(ALL_MARKERS_TEMPLATE);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        utils.readFile();

        PropertyDescriptorEntity prop = new PropertyDescriptorEntity(
                "My Property", "my-property", "default", "Property description",
                null, "Component overall description");
        CustomComponentEntity entity = new CustomComponentEntity(
                "MyProcessor", ComponentType.PROCESSOR, "my-nar", "A processor",
                Collections.singletonList(prop));

        utils.generatePropertyDescription(Collections.singletonList(entity), ComponentType.PROCESSOR);
        utils.writeToFile();

        String result = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(result.contains("### MyProcessor"), "Should contain component heading");
        assertTrue(result.contains("My Property"), "Should contain property display name");
        assertTrue(result.contains("Component overall description"), "Should contain component description");
    }

    /** Verifies generatePropertyDescription() uses the custom header level when specified. */
    @Test
    void testGeneratePropertyDescriptionWithCustomHeaderLevel() throws Exception {
        Path file = writeTemplate(ALL_MARKERS_TEMPLATE);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog(), 2);
        utils.readFile();

        PropertyDescriptorEntity prop = new PropertyDescriptorEntity(
                "My Property", "my-property", "default", "Property description",
                null, "Component description");
        CustomComponentEntity entity = new CustomComponentEntity(
                "MyProcessor", ComponentType.PROCESSOR, "my-nar", "A processor",
                Collections.singletonList(prop));

        utils.generatePropertyDescription(Collections.singletonList(entity), ComponentType.PROCESSOR);
        utils.writeToFile();

        String result = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(result.contains("## MyProcessor"), "Should use level-2 heading");
        assertFalse(result.contains("### MyProcessor"), "Should not use default level-3 heading");
    }

    /** Verifies writeToFile() persists in-memory changes to disk. */
    @Test
    void testWriteToFilePersistsModifiedContent() throws Exception {
        Path file = writeTemplate(ALL_MARKERS_TEMPLATE);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        utils.readFile();

        CustomComponentEntity entity = new CustomComponentEntity(
                "PersistProcessor", ComponentType.PROCESSOR, "persist-nar", "Persisted", Collections.emptyList());
        utils.generateTable(Collections.singletonList(entity), ComponentType.PROCESSOR);
        utils.writeToFile();

        // Re-read from disk to verify persistence
        String result = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(result.contains("PersistProcessor"), "Should contain written processor name");
    }

    // --- Rainy day tests ---

    /** Verifies construction with null path throws {@link IllegalArgumentException}. */
    @Test
    void testConstructorWithNullPathThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new MarkdownUtils(null, mockLog()));
    }

    /** Verifies readFile() throws {@link IOException} for a non-existent file. */
    @Test
    void testReadFileWithNonExistentFileThrowsIOException() {
        Path nonExistent = tempDir.resolve("nonexistent.md");
        MarkdownUtils utils = new MarkdownUtils(nonExistent, mockLog());
        assertThrows(IOException.class, utils::readFile);
    }

    /** Verifies generateTable() throws {@link IllegalStateException} when the processor marker is absent. */
    @Test
    void testGenerateTableMissingProcessorMarkerThrowsIllegalStateException() throws Exception {
        String templateWithoutProcessorMarker =
                "## No processors here\n"
                + "<!-- Table for additional controller services. DO NOT REMOVE. -->\n"
                + "<!-- Additional controller services description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional controller services description. DO NOT REMOVE. -->\n"
                + "<!-- Table for additional reporting tasks. DO NOT REMOVE. -->\n"
                + "<!-- Additional reporting tasks description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional reporting tasks description. DO NOT REMOVE. -->\n"
                + "<!-- Additional processors properties description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional processors properties description. DO NOT REMOVE. -->\n";

        Path file = writeTemplate(templateWithoutProcessorMarker);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        utils.readFile();

        assertThrows(IllegalStateException.class,
                () -> utils.generateTable(Collections.emptyList(), ComponentType.PROCESSOR));
    }

    /** Verifies generateTable() throws {@link IllegalStateException} when the controller service marker is absent. */
    @Test
    void testGenerateTableMissingControllerServiceMarkerThrowsIllegalStateException() throws Exception {
        String template =
                "<!-- Table for additional processors. DO NOT REMOVE. -->\n"
                + "<!-- Additional processors properties description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional processors properties description. DO NOT REMOVE. -->\n"
                + "<!-- Table for additional reporting tasks. DO NOT REMOVE. -->\n"
                + "<!-- Additional reporting tasks description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional reporting tasks description. DO NOT REMOVE. -->\n"
                + "<!-- Additional controller services description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional controller services description. DO NOT REMOVE. -->\n";

        Path file = writeTemplate(template);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        utils.readFile();

        assertThrows(IllegalStateException.class,
                () -> utils.generateTable(Collections.emptyList(), ComponentType.CONTROLLER_SERVICE));
    }

    /** Verifies generateTable() throws {@link IllegalStateException} when the reporting task marker is absent. */
    @Test
    void testGenerateTableMissingReportingTaskMarkerThrowsIllegalStateException() throws Exception {
        String template =
                "<!-- Table for additional processors. DO NOT REMOVE. -->\n"
                + "<!-- Additional processors properties description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional processors properties description. DO NOT REMOVE. -->\n"
                + "<!-- Table for additional controller services. DO NOT REMOVE. -->\n"
                + "<!-- Additional controller services description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional controller services description. DO NOT REMOVE. -->\n"
                + "<!-- Additional reporting tasks description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional reporting tasks description. DO NOT REMOVE. -->\n";

        Path file = writeTemplate(template);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        utils.readFile();

        assertThrows(IllegalStateException.class,
                () -> utils.generateTable(Collections.emptyList(), ComponentType.REPORTING_TASK));
    }

    /** Verifies generatePropertyDescription() throws {@link IllegalStateException} when the start marker is absent. */
    @Test
    void testGeneratePropertyDescriptionMissingStartMarkerThrowsIllegalStateException() throws Exception {
        String template =
                "<!-- Table for additional processors. DO NOT REMOVE. -->\n"
                + "<!-- Table for additional controller services. DO NOT REMOVE. -->\n"
                + "<!-- Additional controller services description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional controller services description. DO NOT REMOVE. -->\n"
                + "<!-- Table for additional reporting tasks. DO NOT REMOVE. -->\n"
                + "<!-- Additional reporting tasks description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional reporting tasks description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional processors properties description. DO NOT REMOVE. -->\n";

        Path file = writeTemplate(template);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        utils.readFile();

        assertThrows(IllegalStateException.class,
                () -> utils.generatePropertyDescription(Collections.emptyList(), ComponentType.PROCESSOR));
    }

    /** Verifies generatePropertyDescription() throws {@link IllegalStateException} when the end marker is absent. */
    @Test
    void testGeneratePropertyDescriptionMissingEndMarkerThrowsIllegalStateException() throws Exception {
        String template =
                "<!-- Table for additional processors. DO NOT REMOVE. -->\n"
                + "<!-- Additional processors properties description. DO NOT REMOVE. -->\n"
                + "<!-- Table for additional controller services. DO NOT REMOVE. -->\n"
                + "<!-- Additional controller services description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional controller services description. DO NOT REMOVE. -->\n"
                + "<!-- Table for additional reporting tasks. DO NOT REMOVE. -->\n"
                + "<!-- Additional reporting tasks description. DO NOT REMOVE. -->\n"
                + "<!-- End of additional reporting tasks description. DO NOT REMOVE. -->\n";

        Path file = writeTemplate(template);
        MarkdownUtils utils = new MarkdownUtils(file, mockLog());
        utils.readFile();

        assertThrows(IllegalStateException.class,
                () -> utils.generatePropertyDescription(Collections.emptyList(), ComponentType.PROCESSOR));
    }
}

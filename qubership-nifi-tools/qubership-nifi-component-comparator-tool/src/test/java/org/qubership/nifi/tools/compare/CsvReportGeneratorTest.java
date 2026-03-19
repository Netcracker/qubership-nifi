package org.qubership.nifi.tools.compare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvReportGeneratorTest {

    /** Temporary directory provided by JUnit for each test. */
    @TempDir
    private Path tempDir;

    @Test
    void getOutputPathContainsFileName() {
        CsvReportGenerator gen = new CsvReportGenerator(tempDir);
        assertTrue(gen.getOutputPath().endsWith("NiFiComponentsDelta.csv"));
    }

    @Test
    void getOutputPathContainsOutputDir() {
        CsvReportGenerator gen = new CsvReportGenerator(tempDir);
        assertTrue(gen.getOutputPath().contains(tempDir.toString()));
    }

    @Test
    void generateEmptyRecordsProducesHeaderOnly() throws IOException {
        CsvReportGenerator gen = new CsvReportGenerator(tempDir);
        gen.generate(List.of());

        Path csvFile = Path.of(gen.getOutputPath());
        assertTrue(Files.exists(csvFile));

        List<String> lines = Files.readAllLines(csvFile);
        assertEquals(1, lines.size(), "Only header expected for empty records");
        assertTrue(lines.get(0).contains("Component Name"));
        assertTrue(lines.get(0).contains("Change Type"));
    }

    @Test
    void generateWritesAllRecords() throws IOException {
        CsvReportGenerator gen = new CsvReportGenerator(tempDir);

        List<String[]> records = new ArrayList<>();
        records.add(new String[]{"CompA", "processors", "rename", "Old D", "New D", "old-api", "new-api"});
        records.add(new String[]{"CompB", "controllerService", "deleted", "Removed", "", "rm-api", ""});
        records.add(new String[]{"CompC", "reportingTask", "added", "", "New Prop", "", "add-api"});
        gen.generate(records);

        List<String> lines = Files.readAllLines(Path.of(gen.getOutputPath()));
        assertEquals(4, lines.size(), "Header + 3 data lines");
    }

    @Test
    void generateCsvContainsExpectedValues() throws IOException {
        CsvReportGenerator gen = new CsvReportGenerator(tempDir);

        List<String[]> records = Collections.singletonList(
                new String[]{"MyComponent", "processors", "rename",
                        "Old Display", "New Display", "old-api", "new-api"}
        );
        gen.generate(records);

        String content = Files.readString(Path.of(gen.getOutputPath()));
        assertTrue(content.contains("MyComponent"));
        assertTrue(content.contains("processors"));
        assertTrue(content.contains("rename"));
        assertTrue(content.contains("Old Display"));
        assertTrue(content.contains("new-api"));
    }

    @Test
    void generateEscapesCommasInValues() throws IOException {
        CsvReportGenerator gen = new CsvReportGenerator(tempDir);

        List<String[]> records = Collections.singletonList(
                new String[]{"Name,With,Commas", "type", "rename", "a", "b", "c", "d"}
        );
        gen.generate(records);

        String content = Files.readString(Path.of(gen.getOutputPath()));
        // commons-csv wraps values with commas in quotes
        assertTrue(content.contains("\"Name,With,Commas\""));
    }

    @Test
    void generateEscapesQuotesInValues() throws IOException {
        CsvReportGenerator gen = new CsvReportGenerator(tempDir);

        List<String[]> records = Collections.singletonList(
                new String[]{"Name\"Quoted", "type", "rename", "a", "b", "c", "d"}
        );
        gen.generate(records);

        String content = Files.readString(Path.of(gen.getOutputPath()));
        // commons-csv escapes quotes by doubling them
        assertTrue(content.contains("\"\""));
    }

    @Test
    void generateOverwritesExistingFile() throws IOException {
        CsvReportGenerator gen = new CsvReportGenerator(tempDir);

        // First write
        gen.generate(Collections.singletonList(
                new String[]{"First", "t", "rename", "a", "b", "c", "d"}
        ));

        // Second write should overwrite
        gen.generate(Collections.singletonList(
                new String[]{"Second", "t", "added", "a", "b", "c", "d"}
        ));

        String content = Files.readString(Path.of(gen.getOutputPath()));
        assertTrue(content.contains("Second"));
        // "First" from the first generation should be gone
        assertTrue(!content.contains("First"));
    }
}

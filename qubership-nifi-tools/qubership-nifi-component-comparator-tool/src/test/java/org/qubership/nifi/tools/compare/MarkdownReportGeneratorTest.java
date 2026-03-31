package org.qubership.nifi.tools.compare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownReportGeneratorTest {

    @TempDir
    private Path tempDir;

    @Test
    void getOutputPathContainsFileName() {
        MarkdownReportGenerator gen = new MarkdownReportGenerator(tempDir);
        assertTrue(gen.getOutputPath().endsWith("NiFiComponentsDelta.md"));
    }

    @Test
    void getOutputPathContainsOutputDir() {
        MarkdownReportGenerator gen = new MarkdownReportGenerator(tempDir);
        assertTrue(gen.getOutputPath().contains(tempDir.toString()));
    }

    @Test
    void generateEmptyRecordsProducesSummaryAndEmptySections() throws IOException {
        MarkdownReportGenerator gen = new MarkdownReportGenerator(tempDir);
        gen.generate(List.of());

        Path mdFile = Path.of(gen.getOutputPath());
        assertTrue(Files.exists(mdFile));

        String content = Files.readString(mdFile);
        assertTrue(content.contains("# NiFi Component Properties Delta Report"));
        assertTrue(content.contains("| Total changes | 0 |"));
        assertTrue(content.contains("| Renamed properties | 0 |"));
        assertTrue(content.contains("| Deleted properties | 0 |"));
        assertTrue(content.contains("| Added properties | 0 |"));
        assertTrue(content.contains("| Affected components | 0 |"));
        assertTrue(content.contains("## Processors"));
        assertTrue(content.contains("## Controller Services"));
        assertTrue(content.contains("## Reporting Tasks"));
        assertTrue(content.contains("_No changes detected._"));
    }

    @Test
    void generateWritesAllRecords() throws IOException {
        MarkdownReportGenerator gen = new MarkdownReportGenerator(tempDir);

        List<String[]> records = new ArrayList<>();
        records.add(new String[]{"CompA", "processors", "rename", "Old D", "New D", "old-api", "new-api"});
        records.add(new String[]{"CompB", "controllerService", "deleted", "Removed", "", "rm-api", ""});
        records.add(new String[]{"CompC", "reportingTask", "added", "", "New Prop", "", "add-api"});
        gen.generate(records);

        String content = Files.readString(Path.of(gen.getOutputPath()));
        assertTrue(content.contains("CompA"));
        assertTrue(content.contains("CompB"));
        assertTrue(content.contains("CompC"));
        assertTrue(content.contains("old-api"));
        assertTrue(content.contains("new-api"));
        assertTrue(content.contains("rm-api"));
        assertTrue(content.contains("add-api"));
    }

    @Test
    void generateGroupsByComponentType() throws IOException {
        MarkdownReportGenerator gen = new MarkdownReportGenerator(tempDir);

        List<String[]> records = new ArrayList<>();
        records.add(new String[]{"ProcA", "processors", "rename", "A", "B", "a", "b"});
        records.add(new String[]{"SvcA", "controllerService", "deleted", "C", "", "c", ""});
        gen.generate(records);

        String content = Files.readString(Path.of(gen.getOutputPath()));

        // ProcA should appear after "## Processors" but before "## Controller Services"
        int processorsIdx = content.indexOf("## Processors");
        int csIdx = content.indexOf("## Controller Services");
        int procAIdx = content.indexOf("### ProcA");
        int svcAIdx = content.indexOf("### SvcA");

        assertTrue(procAIdx > processorsIdx && procAIdx < csIdx,
                "ProcA should be under Processors section");
        assertTrue(svcAIdx > csIdx,
                "SvcA should be under Controller Services section");
    }

    @Test
    void generateGroupsByComponentName() throws IOException {
        MarkdownReportGenerator gen = new MarkdownReportGenerator(tempDir);

        List<String[]> records = new ArrayList<>();
        records.add(new String[]{"Alpha", "processors", "rename", "A", "B", "a", "b"});
        records.add(new String[]{"Beta", "processors", "added", "", "C", "", "c"});
        gen.generate(records);

        String content = Files.readString(Path.of(gen.getOutputPath()));
        assertTrue(content.contains("### Alpha"));
        assertTrue(content.contains("### Beta"));

        // Alpha should come before Beta (alphabetical sort)
        assertTrue(content.indexOf("### Alpha") < content.indexOf("### Beta"));
    }

    @Test
    void generateSummaryCountsAreCorrect() throws IOException {
        MarkdownReportGenerator gen = new MarkdownReportGenerator(tempDir);

        List<String[]> records = new ArrayList<>();
        records.add(new String[]{"C1", "processors", "rename", "A", "B", "a", "b"});
        records.add(new String[]{"C1", "processors", "rename", "C", "D", "c", "d"});
        records.add(new String[]{"C2", "processors", "deleted", "E", "", "e", ""});
        records.add(new String[]{"C3", "controllerService", "added", "", "F", "", "f"});
        gen.generate(records);

        String content = Files.readString(Path.of(gen.getOutputPath()));
        assertTrue(content.contains("| Total changes | 4 |"));
        assertTrue(content.contains("| Renamed properties | 2 |"));
        assertTrue(content.contains("| Deleted properties | 1 |"));
        assertTrue(content.contains("| Added properties | 1 |"));
        assertTrue(content.contains("| Affected components | 3 |"));
    }

    @Test
    void generateComponentTypeSummaryCountsAreCorrect() throws IOException {
        MarkdownReportGenerator gen = new MarkdownReportGenerator(tempDir);

        List<String[]> records = new ArrayList<>();
        records.add(new String[]{"P1", "processors", "rename", "A", "B", "a", "b"});
        records.add(new String[]{"P1", "processors", "deleted", "C", "", "c", ""});
        records.add(new String[]{"S1", "controllerService", "added", "", "D", "", "d"});
        gen.generate(records);

        String content = Files.readString(Path.of(gen.getOutputPath()));
        assertTrue(content.contains("| Processors | 1 | 1 | 0 | 2 |"));
        assertTrue(content.contains("| Controller Services | 0 | 0 | 1 | 1 |"));
        assertTrue(content.contains("| Reporting Tasks | 0 | 0 | 0 | 0 |"));
    }

    @Test
    void generateEscapesPipeInValues() throws IOException {
        MarkdownReportGenerator gen = new MarkdownReportGenerator(tempDir);

        List<String[]> records = Collections.singletonList(
                new String[]{"Comp", "processors", "rename",
                        "Old|Name", "New|Name", "old|api", "new|api"}
        );
        gen.generate(records);

        String content = Files.readString(Path.of(gen.getOutputPath()));
        assertTrue(content.contains("Old\\|Name"));
        assertTrue(content.contains("New\\|Name"));
        assertTrue(content.contains("old\\|api"));
        assertTrue(content.contains("new\\|api"));
    }

    @Test
    void generateOverwritesExistingFile() throws IOException {
        MarkdownReportGenerator gen = new MarkdownReportGenerator(tempDir);

        gen.generate(Collections.singletonList(
                new String[]{"First", "processors", "rename", "a", "b", "c", "d"}
        ));

        gen.generate(Collections.singletonList(
                new String[]{"Second", "processors", "added", "a", "b", "c", "d"}
        ));

        String content = Files.readString(Path.of(gen.getOutputPath()));
        assertTrue(content.contains("Second"));
        assertFalse(content.contains("First"));
    }

    @Test
    void generateMixedChangeTypes() throws IOException {
        MarkdownReportGenerator gen = new MarkdownReportGenerator(tempDir);

        List<String[]> records = new ArrayList<>();
        records.add(new String[]{"Comp", "processors", "rename", "Old", "New", "old-api", "new-api"});
        records.add(new String[]{"Comp", "processors", "deleted", "Gone", "", "gone-api", ""});
        records.add(new String[]{"Comp", "processors", "added", "", "Fresh", "", "fresh-api"});
        gen.generate(records);

        String content = Files.readString(Path.of(gen.getOutputPath()));
        assertTrue(content.contains("| rename"));
        assertTrue(content.contains("| deleted"));
        assertTrue(content.contains("| added"));
    }
}

package org.qubership.nifi.tools.compare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates comparison report in markdown format.
 */
public class MarkdownReportGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarkdownReportGenerator.class);

    private static final String MD_OUTPUT_FILE = "NiFiComponentsDelta.md";

    private static final List<String> FOLDER_ORDER = List.of(
            "processors", "controllerService", "reportingTask"
    );

    private static final Map<String, String> FOLDER_DISPLAY_NAMES = Map.of(
            "processors", "Processors",
            "controllerService", "Controller Services",
            "reportingTask", "Reporting Tasks"
    );

    private static final String TABLE_HEADER =
            "| Change Type | Old Display Name | New Display Name | Old Api Name | New Api Name |";
    private static final String TABLE_SEPARATOR =
            "|-------------|------------------|------------------|--------------|--------------|";

    private static final int IDX_COMPONENT_NAME = 0;
    private static final int IDX_COMPONENT_TYPE = 1;
    private static final int IDX_CHANGE_TYPE = 2;
    private static final int IDX_OLD_DISPLAY_NAME = 3;
    private static final int IDX_NEW_DISPLAY_NAME = 4;
    private static final int IDX_OLD_API_NAME = 5;
    private static final int IDX_NEW_API_NAME = 6;

    private final Path outputDir;

    /**
     * Creates a new Markdown report generator.
     *
     * @param outputDirValue directory where the Markdown file will be written
     */
    public MarkdownReportGenerator(final Path outputDirValue) {
        this.outputDir = outputDirValue;
    }

    /**
     * Writes the Markdown report from the provided comparison records.
     *
     * @param csvRecords list of record arrays, each containing values
     *                   for Component Name, Component Type, Change Type,
     *                   Old Display Name, New Display Name, Old Api Name, New Api Name
     */
    public void generate(List<String[]> csvRecords) {
        LOGGER.info("Generating Markdown report...");

        StringBuilder sb = new StringBuilder();
        sb.append("# NiFi Component Properties Delta Report\n\n");

        Map<String, Map<String, List<String[]>>> grouped = groupRecords(csvRecords);

        appendSummary(sb, csvRecords, grouped);
        appendComponentTypeSections(sb, grouped);

        try (FileWriter writer = new FileWriter(getOutputPath())) {
            writer.write(sb.toString());
            LOGGER.info("Markdown report written to: {}", getOutputPath());
            LOGGER.info("Total records: {}", csvRecords.size());
        } catch (IOException e) {
            LOGGER.error("Error writing Markdown file: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns the absolute path of the Markdown output file.
     *
     * @return absolute path to NiFiComponentsDelta.md
     */
    public String getOutputPath() {
        return outputDir.resolve(MD_OUTPUT_FILE).toAbsolutePath().toString();
    }

    /**
     * Groups records by component type folder, then by component name (sorted).
     * @param csvRecords list of repords to group
     * @return Map with grouped records
     */
    private Map<String, Map<String, List<String[]>>> groupRecords(List<String[]> csvRecords) {
        Map<String, Map<String, List<String[]>>> grouped = new LinkedHashMap<>();
        for (String folder : FOLDER_ORDER) {
            grouped.put(folder, new TreeMap<>());
        }

        for (String[] csvRecord : csvRecords) {
            String folder = csvRecord[IDX_COMPONENT_TYPE];
            String componentName = csvRecord[IDX_COMPONENT_NAME];

            grouped.computeIfAbsent(folder, k -> new TreeMap<>())
                    .computeIfAbsent(componentName, k -> new ArrayList<>())
                    .add(csvRecord);
        }
        return grouped;
    }

    private void appendSummary(StringBuilder sb,
                               List<String[]> csvRecords,
                               Map<String, Map<String, List<String[]>>> grouped) {
        long renamed = countByChangeType(csvRecords, "rename");
        long deleted = countByChangeType(csvRecords, "deleted");
        long added = countByChangeType(csvRecords, "added");
        long affectedComponents = csvRecords.stream()
                .map(r -> r[IDX_COMPONENT_NAME])
                .distinct()
                .count();

        sb.append("## Summary\n\n");
        sb.append("| Metric | Count |\n");
        sb.append("|--------|------:|\n");
        sb.append("| Total changes | ").append(csvRecords.size()).append(" |\n");
        sb.append("| Renamed properties | ").append(renamed).append(" |\n");
        sb.append("| Deleted properties | ").append(deleted).append(" |\n");
        sb.append("| Added properties | ").append(added).append(" |\n");
        sb.append("| Affected components | ").append(affectedComponents).append(" |\n");
        sb.append("\n");

        appendComponentTypeSummary(sb, grouped);
    }

    private void appendComponentTypeSummary(StringBuilder sb,
                                            Map<String, Map<String, List<String[]>>> grouped) {
        sb.append("### Changes by Component Type\n\n");
        sb.append("| Component Type | Renamed | Deleted | Added | Total |\n");
        sb.append("|----------------|--------:|--------:|------:|------:|\n");

        for (String folder : FOLDER_ORDER) {
            Map<String, List<String[]>> components = grouped.get(folder);
            List<String[]> allRecords = flattenRecords(components);

            long renamed = countByChangeType(allRecords, "rename");
            long deletedCount = countByChangeType(allRecords, "deleted");
            long addedCount = countByChangeType(allRecords, "added");
            long total = allRecords.size();

            String displayName = FOLDER_DISPLAY_NAMES.getOrDefault(folder, folder);
            sb.append("| ").append(displayName)
                    .append(" | ").append(renamed)
                    .append(" | ").append(deletedCount)
                    .append(" | ").append(addedCount)
                    .append(" | ").append(total)
                    .append(" |\n");
        }
        sb.append("\n");
    }

    private void appendComponentTypeSections(StringBuilder sb,
                                             Map<String, Map<String, List<String[]>>> grouped) {
        for (String folder : FOLDER_ORDER) {
            String displayName = FOLDER_DISPLAY_NAMES.getOrDefault(folder, folder);
            sb.append("## ").append(displayName).append("\n\n");

            Map<String, List<String[]>> components = grouped.get(folder);
            if (components == null || components.isEmpty()) {
                sb.append("_No changes detected._\n\n");
                continue;
            }

            for (Map.Entry<String, List<String[]>> entry : components.entrySet()) {
                sb.append("### ").append(entry.getKey()).append("\n\n");
                sb.append(TABLE_HEADER).append("\n");
                sb.append(TABLE_SEPARATOR).append("\n");

                for (String[] csvRecord : entry.getValue()) {
                    sb.append("| ")
                            .append(escapeCell(csvRecord[IDX_CHANGE_TYPE])).append(" | ")
                            .append(escapeCell(csvRecord[IDX_OLD_DISPLAY_NAME])).append(" | ")
                            .append(escapeCell(csvRecord[IDX_NEW_DISPLAY_NAME])).append(" | ")
                            .append(escapeCell(csvRecord[IDX_OLD_API_NAME])).append(" | ")
                            .append(escapeCell(csvRecord[IDX_NEW_API_NAME])).append(" |\n");
                }
                sb.append("\n");
            }
        }
    }

    private long countByChangeType(List<String[]> records, String changeType) {
        return records.stream()
                .filter(r -> changeType.equals(r[IDX_CHANGE_TYPE]))
                .count();
    }

    private List<String[]> flattenRecords(Map<String, List<String[]>> components) {
        if (components == null) {
            return List.of();
        }
        List<String[]> result = new ArrayList<>();
        for (List<String[]> records : components.values()) {
            result.addAll(records);
        }
        return result;
    }

    private String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|");
    }
}

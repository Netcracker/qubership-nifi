package org.qubership.nifi.utils;

import org.qubership.nifi.CustomComponentEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MarkdownUtils {

    private static final String TABLE_PROCESSOR = "<!-- Table for additional processor. DO NOT REMOVE. -->";
    private static final String TABLE_CONTROLLER_SERVICES =
            "<!-- Table additional controller services. DO NOT REMOVE. -->";
    private static final String TABLE_REPORTING_TASK =
            "<!-- Table additional reporting tasks. DO NOT REMOVE. -->";

    private static final String PROPERTIES_DESCRIPTION_PROCESSOR =
            "<!-- Additional processors properties description. DO NOT REMOVE -->";
    private static final String PROPERTIES_DESCRIPTION_CONTROLLER_SERVICES =
            "<!-- Additional controller services description. DO NOT REMOVE -->";
    private static final String PROPERTIES_DESCRIPTION_REPORTING_TASK =
            "<!-- Additional reporting tasks description. DO NOT REMOVE -->";

    private final Path templateFile;

    /**
     * Constructor for class MarkdownUtils.
     *
     * @param templateFileValue File to write
     */
    public MarkdownUtils(final Path templateFileValue) {
        if (templateFileValue == null) {
            throw new IllegalArgumentException("Output file path cannot be null");
        }
        this.templateFile = templateFileValue;
    }

    public void generateTable(String[][] processorRows, String componentType) throws IOException {
        List<String> lines = Files.readAllLines(templateFile);

        List<String> updatedLines = new ArrayList<>();
        boolean headerFound = false;
        int afterHeaderIndex = -1;
        int tableStartIndex = -1;
        int tableEndIndex = -1;

        String strTemplate = switch (componentType) {
            case "processor" -> TABLE_PROCESSOR;
            case "controller_service" -> TABLE_CONTROLLER_SERVICES;
            case "reporting_task" -> TABLE_REPORTING_TASK;
            default -> throw new IllegalStateException("Unexpected value: " + componentType);
        };

        String headerTemplate = switch (componentType) {
            case "processor" -> "Processor";
            case "controller_service" -> "Controller Service";
            case "reporting_task" -> "Reporting Task";
            default -> throw new IllegalStateException("Unexpected value: " + componentType);
        };

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.equals(strTemplate)) {
                headerFound = true;
                afterHeaderIndex = i + 1;
                break;
            }
        }

        if (!headerFound) {
            throw new IllegalStateException("Line " + strTemplate + " not found in template file: " + templateFile);
        }

        for (int i = afterHeaderIndex; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("|") && line.contains(headerTemplate)
                    && line.contains("NAR") && line.contains("Description")) {
                tableStartIndex = i;
                int j = i + 1;
                if (j < lines.size() && lines.get(j).trim().startsWith("|") && lines.get(j).trim().contains("---")) {
                    j++;
                }
                while (j < lines.size()) {
                    String nextLine = lines.get(j).trim();
                    if (!nextLine.startsWith("|")) {
                        break;
                    }
                    j++;
                }
                tableEndIndex = j;
                break;
            }
        }

        List<String> newTableRows = new ArrayList<>();
        if (processorRows != null) {
            for (String[] row : processorRows) {
                if (row != null && row.length >= 3) {
                    String tableRow = "| " + row[0] + " | " + row[1] + " | " + row[2] + " |";
                    newTableRows.add(tableRow);
                } else {
                    System.err.println("Warning: Skipping invalid row data: " + Arrays.toString(row));
                }
            }
        }

        boolean tableProcessed = false;

        for (int i = 0; i < lines.size(); i++) {
            updatedLines.add(lines.get(i));

            if (i == afterHeaderIndex - 1) {
                if (tableStartIndex == -1) {
                    updatedLines.add("");
                    updatedLines.add("| " + headerTemplate + " | NAR                 | Description        |");
                    updatedLines.add("|------------------------|---------------------|--------------------|");
                    updatedLines.addAll(newTableRows);
                    tableProcessed = true;
                }
            }

            if (tableStartIndex != -1 && i == tableEndIndex - 1 && !tableProcessed) {
                updatedLines.addAll(updatedLines.size(), newTableRows);
                tableProcessed = true;
            }
        }

        Files.write(templateFile, updatedLines);
    }

    public void generatePropertyDescription(
            Map<String, List<CustomComponentEntity>> componentEntityMap,
            String componentType) throws IOException {
        List<String> lines = Files.readAllLines(templateFile);

        String strTemplate = switch (componentType) {
            case "processor" -> PROPERTIES_DESCRIPTION_PROCESSOR;
            case "controller_service" -> PROPERTIES_DESCRIPTION_CONTROLLER_SERVICES;
            case "reporting_task" -> PROPERTIES_DESCRIPTION_REPORTING_TASK;
            default -> throw new IllegalStateException("Unexpected value: " + componentType);
        };

        List<String> updatedLines = new ArrayList<>();
        boolean markerFound = false;
        int sectionStartIndex = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().equals(strTemplate)) {
                markerFound = true;
                sectionStartIndex = i;
                break;
            }
        }

        if (!markerFound) {
            throw new IllegalStateException("Marker comment for " + componentType
                    + " properties not found in template file: " + templateFile);
        }

        List<String> descriptionLines = new ArrayList<>();
        if (componentEntityMap != null) {
            for (Map.Entry<String, List<CustomComponentEntity>> entry : componentEntityMap.entrySet()) {
                String componentName = entry.getKey();
                List<CustomComponentEntity> entities = entry.getValue();

                descriptionLines.add("### " + componentName);
                descriptionLines.add("");

                String componentDescription = null;
                if (entities != null && !entities.isEmpty()) {
                    CustomComponentEntity firstEntity = entities.get(0);
                    if (firstEntity != null) {
                        componentDescription = firstEntity.getComponentDescription();
                    }
                }

                if (componentDescription != null) {
                    descriptionLines.add(componentDescription);
                    descriptionLines.add("");
                }

                descriptionLines.add("| Display Name                      | API Name            | Default Value      "
                        + "| Allowable Values   | Description        |");
                descriptionLines.add("|-----------------------------------|---------------------|--------------------|"
                        + "--------------------|--------------------|");

                if (entities != null) {
                    for (CustomComponentEntity entity : entities) {
                        if (entity != null) {
                            String displayName = entity.getDisplayName() != null ? entity.getDisplayName() : "";
                            String apiName = entity.getApiName() != null ? entity.getApiName() : "";
                            String defaultValue = entity.getDefaultValueAsString() != null
                                    ? entity.getDefaultValueAsString() : "";
                            String allowableValuesStr = entity.getAllowableValuesAsString() != null
                                    ? entity.getAllowableValuesAsString() : "";
                            String description = entity.getDescriptioneAsString() != null
                                    ? entity.getDescriptioneAsString() : "";
                            descriptionLines.add("| " + displayName + " | " + apiName + " | " + defaultValue + " | "
                                    + allowableValuesStr + " | " + description + " |");
                        } else {
                            System.err.println("Warning: Found null entity in list for component '"
                                    + componentName + "'");
                        }
                    }
                }
                descriptionLines.add("");
            }
        }

        for (int i = 0; i <= sectionStartIndex; i++) {
            updatedLines.add(lines.get(i));
        }

        updatedLines.addAll(descriptionLines);

        for (int i = sectionStartIndex + 1; i < lines.size(); i++) {
            updatedLines.add(lines.get(i));
        }

        Files.write(templateFile, updatedLines);
    }

}

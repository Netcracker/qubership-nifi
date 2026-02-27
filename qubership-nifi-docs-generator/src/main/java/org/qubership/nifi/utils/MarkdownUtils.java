package org.qubership.nifi.utils;

import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.ComponentType;
import org.qubership.nifi.CustomComponentEntity;
import org.qubership.nifi.PropertyDescriptorEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MarkdownUtils {

    private static final String TABLE_PROCESSOR = "<!-- Table for additional processors. DO NOT REMOVE. -->";
    private static final String TABLE_CONTROLLER_SERVICES =
            "<!-- Table for additional controller services. DO NOT REMOVE. -->";
    private static final String TABLE_REPORTING_TASK =
            "<!-- Table for additional reporting tasks. DO NOT REMOVE. -->";

    private static final String PROPERTIES_DESCRIPTION_PROCESSOR =
            "<!-- Additional processors properties description. DO NOT REMOVE. -->";
    private static final String PROPERTIES_DESCRIPTION_CONTROLLER_SERVICES =
            "<!-- Additional controller services description. DO NOT REMOVE. -->";
    private static final String PROPERTIES_DESCRIPTION_REPORTING_TASK =
            "<!-- Additional reporting tasks description. DO NOT REMOVE. -->";

    private static final String PROPERTIES_DESCRIPTION_END_PROCESSOR =
            "<!-- End of additional processors properties description. DO NOT REMOVE. -->";
    private static final String PROPERTIES_DESCRIPTION_END_CONTROLLER_SERVICES =
            "<!-- End of additional controller services description. DO NOT REMOVE. -->";
    private static final String PROPERTIES_DESCRIPTION_END_REPORTING_TASK =
            "<!-- End of additional reporting tasks description. DO NOT REMOVE. -->";


    private static final String HEADER_BASE = " | NAR                 | Description        |";
    private static final String HEADER_PROCESSORS = "| Processor " + HEADER_BASE;
    private static final String HEADER_CONTROLLER_SERVICES = "| Controller Service " + HEADER_BASE;
    private static final String HEADER_REPORTING_TASKS = "| Reporting Task " + HEADER_BASE;
    private static final String TITLE_SEPARATOR = "|----------------------|--------------------|--------------------|";

    private static final String PROPERTIES_DESCRIPTION_HEADER = "| Display Name                      | API Name        "
            + "    | Default Value      | Allowable Values   | Description        |";

    private static final String PROPERTIES_DESCRIPTION_TITLE_SEPARATOR = "|-----------------------------------|------"
            + "---------------|--------------------|--------------------|--------------------|";

    private static final int DEFAULT_HEADER_LEVEL = 3;
    private static final int MIN_HEADER_LEVEL = 1;
    private static final int MAX_HEADER_LEVEL = 6;

    private List<String> lines;

    private final Path templateFile;
    private final Log log;
    private final String componentHeadingPrefix;

    /**
     * Constructor for class MarkdownUtils. Uses heading level 3 by default.
     *
     * @param templateFileValue File to write
     * @param logValue Logger
     */
    public MarkdownUtils(final Path templateFileValue, final Log logValue) {
        this(templateFileValue, logValue, DEFAULT_HEADER_LEVEL);
    }

    /**
     * Constructor for class MarkdownUtils.
     *
     * @param templateFileValue File to write
     * @param logValue Logger
     * @param headerLevel the Markdown heading level (1–6) used for component name headings
     */
    public MarkdownUtils(final Path templateFileValue, final Log logValue, final int headerLevel) {
        if (templateFileValue == null) {
            throw new IllegalArgumentException("Output file path cannot be null");
        }
        if (headerLevel < MIN_HEADER_LEVEL || headerLevel > MAX_HEADER_LEVEL) {
            throw new IllegalArgumentException("headerLevel must be between 1 and 6, got: " + headerLevel);
        }
        this.templateFile = templateFileValue;
        this.log = logValue;
        this.componentHeadingPrefix = "#".repeat(headerLevel) + " ";
    }

    private Log getLog() {
        return log;
    }

    /**
     * Reading the contents of a file.
     * @throws IOException
     */
    public void readFile() throws IOException {
        lines = Files.readAllLines(templateFile, StandardCharsets.UTF_8);
    }

    /**
     * Writing to a file.
     * @throws IOException
     */
    public void writeToFile() throws IOException {
        Files.write(templateFile, lines, StandardCharsets.UTF_8);
    }

    /**
     * Generates or updates a Markdown table in a template file with component information.
     *
     * @param customComponentList list of component for table
     * @param componentType the type of component table to generate; must be one of:
     *  *                      {@code "processor"}, {@code "controller_service"}, or {@code "reporting_task"}
     */
    public void generateTable(
            List<CustomComponentEntity> customComponentList,
            ComponentType componentType
    ) {

        List<String> updatedLines = new ArrayList<>();
        boolean headerFound = false;
        int afterHeaderIndex = -1;
        int tableStartIndex = -1;
        int tableEndIndex = -1;

        String strTemplate = switch (componentType) {
            case PROCESSOR -> TABLE_PROCESSOR;
            case CONTROLLER_SERVICE -> TABLE_CONTROLLER_SERVICES;
            case REPORTING_TASK -> TABLE_REPORTING_TASK;
        };

        String headerTemplate = switch (componentType) {
            case PROCESSOR -> HEADER_PROCESSORS;
            case CONTROLLER_SERVICE -> HEADER_CONTROLLER_SERVICES;
            case REPORTING_TASK -> HEADER_REPORTING_TASKS;
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
            if (line.trim().equals(headerTemplate)) {
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
        if (!customComponentList.isEmpty()) {
            for (CustomComponentEntity customComponentEntity : customComponentList) {
                String tableRow = "| "
                        + customComponentEntity.getComponentName()
                        + " | "
                        + customComponentEntity.getComponentNar()
                        + " | "
                        + customComponentEntity.getComponentDescription().replaceAll("\\r?\\n|\\r", "")
                        + " |";
                newTableRows.add(tableRow);
            }
        }

        boolean tableProcessed = false;

        for (int i = 0; i < lines.size(); i++) {
            updatedLines.add(lines.get(i));

            if (i == afterHeaderIndex - 1) {
                if (tableStartIndex == -1) {
                    updatedLines.add("");
                    updatedLines.add(headerTemplate);
                    updatedLines.add(TITLE_SEPARATOR);
                    updatedLines.addAll(newTableRows);
                    tableProcessed = true;
                }
            }

            if (tableStartIndex != -1 && i == tableEndIndex - 1 && !tableProcessed) {
                updatedLines.addAll(updatedLines.size(), newTableRows);
                tableProcessed = true;
            }
        }

        lines = updatedLines;
    }


    /**
     * Generates detailed property descriptions for components in markdown format
     * and inserts them into a template file.
     * The heading level for component names is controlled by the {@code headerLevel}
     * constructor parameter.
     *
     * @param customComponentList list of component for table
     * @param componentType the type of component properties to generate; must be one of:
     *                    {@code "processor"}, {@code "controller_service"}, or {@code "reporting_task"}
     */
    public void generatePropertyDescription(
            List<CustomComponentEntity> customComponentList,
            ComponentType componentType) {
        String strTemplate = switch (componentType) {
            case PROCESSOR -> PROPERTIES_DESCRIPTION_PROCESSOR;
            case CONTROLLER_SERVICE -> PROPERTIES_DESCRIPTION_CONTROLLER_SERVICES;
            case REPORTING_TASK -> PROPERTIES_DESCRIPTION_REPORTING_TASK;
        };

        String endMarker = switch (componentType) {
            case PROCESSOR -> PROPERTIES_DESCRIPTION_END_PROCESSOR;
            case CONTROLLER_SERVICE -> PROPERTIES_DESCRIPTION_END_CONTROLLER_SERVICES;
            case REPORTING_TASK -> PROPERTIES_DESCRIPTION_END_REPORTING_TASK;
        };

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

        int endIndex = -1;
        for (int i = sectionStartIndex + 1; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(endMarker)) {
                endIndex = i;
                break;
            }
        }
        if (endIndex == -1) {
            throw new IllegalStateException("End marker for " + componentType
                    + " properties not found in file: " + templateFile);
        }

        List<String> descriptionLines = new ArrayList<>();
        if (!customComponentList.isEmpty()) {
            for (CustomComponentEntity customComponentEntity : customComponentList) {
                String componentName = customComponentEntity.getComponentName();
                List<PropertyDescriptorEntity> entities = customComponentEntity.getComponentProperties();

                descriptionLines.add("");
                descriptionLines.add(componentHeadingPrefix + componentName);
                descriptionLines.add("");

                String componentDescription = null;
                if (entities != null && !entities.isEmpty()) {
                    PropertyDescriptorEntity firstEntity = entities.get(0);
                    if (firstEntity != null) {
                        componentDescription = firstEntity.getComponentDescription();
                    }
                }

                if (componentDescription != null) {
                    descriptionLines.add(removeSpacesBeforeNewline(componentDescription));
                    descriptionLines.add("");
                }

                descriptionLines.add(PROPERTIES_DESCRIPTION_HEADER);
                descriptionLines.add(PROPERTIES_DESCRIPTION_TITLE_SEPARATOR);

                if (entities != null) {
                    for (PropertyDescriptorEntity entity : entities) {
                        if (entity != null) {
                            String displayName = entity.getDisplayName() != null ? entity.getDisplayName() : "";
                            String apiName = entity.getApiName() != null ? entity.getApiName() : "";
                            String defaultValue = entity.getDefaultValueAsString() != null
                                    ? entity.getDefaultValueAsString() : "";
                            String allowableValuesStr = entity.getAllowableValuesAsString() != null
                                    ? entity.getAllowableValuesAsString() : "";
                            String description = entity.getDescriptionAsString() != null
                                    ? entity.getDescriptionAsString() : "";
                            descriptionLines.add("| " + displayName + " | " + apiName + " | " + defaultValue + " | "
                                    + allowableValuesStr + " | " + description + " |");
                        } else {
                            getLog().error("Found null entity in list for component '"
                                    + componentName + "'");
                        }
                    }
                }
            }
        }

        List<String> updatedLines = new ArrayList<>(lines.subList(0, endIndex));
        updatedLines.addAll(descriptionLines);
        updatedLines.addAll(lines.subList(endIndex, lines.size()));
        lines = updatedLines;
    }

    private String removeSpacesBeforeNewline(String input) {
        return input.replaceAll("[ \\t]+(?=\\r?\\n|\\r)", "");
    }

}

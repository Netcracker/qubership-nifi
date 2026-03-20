package org.qubership.nifi.tools.compare;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class CsvReportGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(CsvReportGenerator.class);

    private static final String CSV_OUTPUT_FILE = "NiFiComponentsDelta.csv";

    private static final String[] CSV_HEADERS = {
            "Component Name", "Component Type", "Change Type",
            "Old Display Name", "New Display Name",
            "Old Api Name", "New Api Name"
    };

    private final Path outputDir;

    /**
     * Creates a new CSV report generator.
     *
     * @param outputDirValue directory where the CSV file will be written
     */
    public CsvReportGenerator(final Path outputDirValue) {
        this.outputDir = outputDirValue;
    }

    /**
     * Writes the CSV report from the provided comparison records.
     *
     * @param csvRecords list of record arrays, each containing values
     *                   matching the {@link #CSV_HEADERS} columns
     */
    public void generate(List<String[]> csvRecords) {
        CSVFormat csvFormat = CSVFormat.DEFAULT
                .withHeader(CSV_HEADERS);

        try (CSVPrinter printer = new CSVPrinter(new FileWriter(getOutputPath()), csvFormat)) {
            for (String[] csvRecord : csvRecords) {
                printer.printRecord((Object[]) csvRecord);
            }
            LOGGER.info("Report successfully written to: {}", getOutputPath());
            LOGGER.info("Total records: {}", csvRecords.size());
        } catch (IOException e) {
            LOGGER.error("Error writing CSV file: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns the absolute path of the CSV output file.
     *
     * @return absolute path to NiFiComponentsDelta.csv
     */
    public String getOutputPath() {
        return outputDir.resolve(CSV_OUTPUT_FILE).toAbsolutePath().toString();
    }
}

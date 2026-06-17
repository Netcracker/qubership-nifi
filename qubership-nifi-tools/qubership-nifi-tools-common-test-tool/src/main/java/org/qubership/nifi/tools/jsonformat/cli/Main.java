package org.qubership.nifi.tools.jsonformat.cli;

import org.qubership.nifi.tools.jsonformat.JsonFormatReformatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Command-line entry point for the JSON format-preserving reformat tool.
 *
 * <p>Usage: {@code <inputJsonPath> <outputJsonPath>}. The tool detects the input file's formatting,
 * parses it with Jackson, and writes an equivalently formatted copy to the output path.</p>
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final int EXPECTED_ARG_COUNT = 2;

    private Main() {
    }

    /**
     * Application entry point.
     *
     * @param args command-line arguments: {@code <inputJsonPath> <outputJsonPath>}
     */
    public static void main(final String[] args) {
        if (args.length != EXPECTED_ARG_COUNT) {
            LOGGER.error("Usage: <inputJsonPath> <outputJsonPath>");
            System.exit(1);
            return;
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        LOGGER.info("Reformatting {} -> {}", input, output);
        try {
            new JsonFormatReformatter().reformatFile(input, output);
            LOGGER.info("Done.");
        } catch (IOException e) {
            LOGGER.error("Failed to reformat JSON: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}

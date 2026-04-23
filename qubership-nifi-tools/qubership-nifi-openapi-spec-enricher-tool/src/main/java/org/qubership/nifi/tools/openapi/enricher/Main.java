package org.qubership.nifi.tools.openapi.enricher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI entry point. Parses arguments and writes JSON output.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private static final String DEFAULT_OUTPUT_DIR = "./openapi";

    private Main() { }

    /**
     * Get resource as input stream from classpath.
     *
     * @param resourceName the resource name
     * @return input stream for the resource
     * @throws IOException if resource not found
     */
    private static InputStream getResourceAsStream(String resourceName) throws IOException {
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName);
        if (is == null) {
            throw new IOException("Resource not found: " + resourceName);
        }
        return is;
    }

    /**
     * Application entry point.
     *
     * @param args command-line arguments (--version, --output-dir, --timeout, --port);
     *             each flag must be followed by its value — a flag provided as the last
     *             argument without a value causes an {@link ArrayIndexOutOfBoundsException}
     * @throws Exception if any step of the export process fails
     */
    public static void main(final String[] args) throws Exception {
        String outputDir = DEFAULT_OUTPUT_DIR;


        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output-dir":
                    outputDir = args[++i];
                    break;
                default:
                    // ignore unknown flags
                    break;
            }
        }

        LOG.info("Starting openapi spec enrichment tool");
        LOG.info("  output-dir : {}", outputDir);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = null;
        try (InputStream in = getResourceAsStream("docs/rest-api/swagger.json")) {
            node = mapper.readTree(in);
        }

        EnrichSpecification enrich = new EnrichSpecification();
        node = enrich.enrichNiFi(node);

        //
        Files.createDirectories(Paths.get(outputDir));
        //
        Path outputPath = Paths.get(outputDir, "openapi.json");
        try (OutputStream out = new FileOutputStream(outputPath.toFile())) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(out, node);
        }

        LOG.info("Done. Output written to: {}", outputPath);
    }
}

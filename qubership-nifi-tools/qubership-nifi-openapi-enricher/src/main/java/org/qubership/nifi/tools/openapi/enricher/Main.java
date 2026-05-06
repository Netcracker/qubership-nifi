package org.qubership.nifi.tools.openapi.enricher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI entry point. Parses arguments and writes OpenAPI specification in JSON format.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final String DEFAULT_OUTPUT_DIR = "./docs/openapi";

    private Main() { }

    /**
     * Application entry point.
     *
     * @param args command-line arguments (--output-dir);
     *             each flag must be followed by its value - a flag provided as the last
     *             argument without a value causes an {@link IllegalArgumentException}
     * @throws Exception if any step of the enrichment process fails
     */
    public static void main(final String[] args) throws Exception {
        String outputDir = DEFAULT_OUTPUT_DIR;

        for (int i = 0; i < args.length; i++) {
            // ignore unknown flags and fail, if flag has no value
            if (args[i].equals("--output-dir")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--output-dir requires a value");
                }
                outputDir = args[++i];
            }
        }

        LOG.info("Starting openapi spec enrichment tool. Output-dir: {}", outputDir);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = null;
        try (InputStream in = ResourceUtils.getResourceAsStream("docs/rest-api/swagger.json")) {
            node = mapper.readTree(in);
        }

        EnrichSpecification enrich = new EnrichSpecification();
        node = enrich.enrichNiFi(node);
        Files.createDirectories(Paths.get(outputDir));
        Path outputPath = Paths.get(outputDir, "openapi.json");
        try (OutputStream out = new FileOutputStream(outputPath.toFile())) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(out, node);
        }

        LOG.info("Done. Output written to: {}", outputPath);
    }
}

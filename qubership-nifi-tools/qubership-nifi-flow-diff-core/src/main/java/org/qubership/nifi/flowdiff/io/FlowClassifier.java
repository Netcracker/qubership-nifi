package org.qubership.nifi.flowdiff.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.nifi.flowdiff.flow.FlowExport;
import org.qubership.nifi.flowdiff.flow.FlowParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.qubership.nifi.flowdiff.flow.FlowFields.FLOW_CONTENTS;

import java.io.File;
import java.io.IOException;

/**
 * Parses and classifies a single candidate's content as a flow export or a non-flow JSON. A parse failure fails the
 * run naming the file, unless the skip-malformed option downgrades it to a warning. Shared by the file-system and Git
 * sources so both classify candidates identically.
 */
public final class FlowClassifier {

    private static final Logger LOG = LoggerFactory.getLogger(FlowClassifier.class);

    private final boolean skipMalformed;
    private final ObjectMapper mapper;

    /**
     * Creates a classifier.
     *
     * @param skipMalformedValue whether to skip a malformed file with a warning instead of failing
     * @param mapperValue        the JSON mapper
     */
    public FlowClassifier(final boolean skipMalformedValue, final ObjectMapper mapperValue) {
        this.skipMalformed = skipMalformedValue;
        this.mapper = mapperValue;
    }

    /**
     * Classifies one candidate read from a file, letting Jackson stream the file rather than buffering it as a
     * {@code String} first.
     *
     * @param file    the candidate file
     * @param display the normalized display path used in messages and reports
     * @return the classified entry, or {@code null} when a malformed file is skipped
     * @throws IOException when the file cannot be read
     */
    public SideEntry classify(final File file, final String display) throws IOException {
        return classify(() -> mapper.readTree(file), display);
    }

    /**
     * Classifies one candidate from its raw bytes, avoiding an intermediate {@code String}.
     *
     * @param content the raw JSON bytes
     * @param display the normalized display path used in messages and reports
     * @return the classified entry, or {@code null} when a malformed file is skipped
     * @throws IOException when the content cannot be read
     */
    public SideEntry classify(final byte[] content, final String display) throws IOException {
        return classify(() -> mapper.readTree(content), display);
    }

    private SideEntry classify(final NodeParser parser, final String display) throws IOException {
        JsonNode root;
        try {
            root = parser.read();
        } catch (JsonProcessingException ex) {
            if (skipMalformed) {
                LOG.warn("Skipping malformed JSON file: {}", display);
                return null;
            }
            throw new FlowParseException("Malformed JSON file: " + display, ex);
        }
        if (root != null && root.isObject() && root.has(FLOW_CONTENTS)) {
            return SideEntry.flow(display, FlowExport.of(display, root));
        }
        LOG.debug("Skipping non-flow JSON file: {}", display);
        return SideEntry.nonFlow(display);
    }

    /**
     * Reads a JSON tree from a source, deferring the choice of source (file or bytes) to the caller.
     */
    @FunctionalInterface
    private interface NodeParser {
        JsonNode read() throws IOException;
    }
}

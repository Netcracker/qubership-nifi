package org.qubership.nifi.maven.flowdiff.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.logging.Log;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;
import org.qubership.nifi.maven.flowdiff.flow.FlowParseException;

/**
 * Parses and classifies a single candidate's content as a flow export or a non-flow JSON. A parse failure fails the
 * goal naming the file, unless {@code skip-malformed} downgrades it to a warning. Shared by the file-system and Git
 * sources so both classify candidates identically.
 */
public final class FlowClassifier {

    private final boolean skipMalformed;
    private final ObjectMapper mapper;
    private final Log log;

    /**
     * Creates a classifier.
     *
     * @param skipMalformedValue whether to skip a malformed file with a warning instead of failing
     * @param mapperValue        the JSON mapper
     * @param logValue           the Maven log
     */
    public FlowClassifier(final boolean skipMalformedValue, final ObjectMapper mapperValue, final Log logValue) {
        this.skipMalformed = skipMalformedValue;
        this.mapper = mapperValue;
        this.log = logValue;
    }

    /**
     * Classifies one candidate.
     *
     * @param content the raw JSON content
     * @param display the normalized display path used in messages and reports
     * @return the classified entry, or {@code null} when a malformed file is skipped
     */
    public SideEntry classify(final String content, final String display) {
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (JsonProcessingException ex) {
            if (skipMalformed) {
                log.warn("Skipping malformed JSON file: " + display);
                return null;
            }
            throw new FlowParseException("Malformed JSON file: " + display, ex);
        }
        if (root != null && root.isObject() && root.has(FlowExport.FLOW_CONTENTS)) {
            return SideEntry.flow(display, FlowExport.of(display, root));
        }
        log.debug("Skipping non-flow JSON file: " + display);
        return SideEntry.nonFlow(display);
    }
}

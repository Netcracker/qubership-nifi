package org.qubership.nifi.maven.flowdiff.flow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Set;

import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.EXTERNAL_CONTROLLER_SERVICES;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.FLOW_CONTENTS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.FLOW_ENCODING_VERSION;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.PARAMETER_CONTEXTS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.PARAMETER_PROVIDERS;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.SNAPSHOT_METADATA;
import static org.qubership.nifi.maven.flowdiff.flow.FlowFields.LATEST;

/**
 * A parsed NiFi versioned flow export: the {@code flowContents} process-group tree plus the closed set of sibling
 * sections. The set of supported top-level siblings is fixed; an unknown sibling fails the flow so no reported path is
 * ever backed by an undocumented identity rule.
 */
public final class FlowExport {

    /** The closed set of supported top-level sibling section names, {@code flowContents} included. */
    public static final Set<String> KNOWN_TOP_LEVEL_SIBLINGS = Set.of(
            FLOW_CONTENTS,
            FLOW_ENCODING_VERSION,
            PARAMETER_CONTEXTS,
            PARAMETER_PROVIDERS,
            EXTERNAL_CONTROLLER_SERVICES,
            SNAPSHOT_METADATA,
            LATEST);

    private final String displayPath;
    private final JsonNode root;

    private FlowExport(final String displayPathValue, final JsonNode rootValue) {
        this.displayPath = displayPathValue;
        this.root = rootValue;
    }

    /**
     * Wraps an already-parsed export root, validating the closed top-level sibling set.
     *
     * @param displayPathValue the normalized display path of the source file, used in messages
     * @param rootValue        the parsed top-level JSON object
     * @return the wrapped export
     * @throws FlowParseException when the root is not an object, lacks {@code flowContents}, or carries an unknown
     *                            top-level sibling
     */
    public static FlowExport of(final String displayPathValue, final JsonNode rootValue) {
        if (rootValue == null || !rootValue.isObject()) {
            throw new FlowParseException("Flow export is not a JSON object: " + displayPathValue);
        }
        if (!rootValue.has(FLOW_CONTENTS)) {
            throw new FlowParseException("Flow export has no '" + FLOW_CONTENTS + "': " + displayPathValue);
        }
        if (!rootValue.path(FLOW_CONTENTS).isObject()) {
            throw new FlowParseException("Flow export has '" + FLOW_CONTENTS + "', but it's not an object: "
                    + displayPathValue);
        }
        Iterator<String> fields = rootValue.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!KNOWN_TOP_LEVEL_SIBLINGS.contains(field)) {
                throw new FlowParseException("Unknown top-level sibling section '" + field
                        + "' in flow export: " + displayPathValue
                        + ". Supported sections are " + KNOWN_TOP_LEVEL_SIBLINGS + ".");
            }
        }
        return new FlowExport(displayPathValue, rootValue);
    }

    /**
     * Returns the normalized display path of the source file.
     *
     * @return the display path
     */
    public String getDisplayPath() {
        return displayPath;
    }

    /**
     * Returns the top-level export object.
     *
     * @return the root JSON node
     */
    public JsonNode getRoot() {
        return root;
    }

    /**
     * Returns the root process group node ({@code flowContents}).
     *
     * @return the {@code flowContents} node
     */
    public JsonNode getFlowContents() {
        return root.get(FLOW_CONTENTS);
    }
}

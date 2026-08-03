package org.qubership.nifi.maven.flowdiff.io;

/**
 * How a discovered {@code *.json} candidate was classified. A flow export drives a field-by-field diff; a parseable
 * non-flow JSON is retained so a flow-vs-non-flow mismatch at the same relative path can be reported with a warning
 * rather than mistaken for plain absence.
 */
public enum CandidateKind {

    /** The file parsed and carries {@code flowContents}: a flow export. */
    FLOW,
    /** The file parsed but has no {@code flowContents}: not a flow export. */
    NON_FLOW
}

package org.qubership.nifi.maven.flowdiff.report;

import java.util.Locale;
import java.util.Optional;

/**
 * The report output formats. {@code TEXT} is written to standard output by default; {@code JSON} and {@code MD} require
 * an output file.
 */
public enum ReportFormat {

    /** Human-readable grouped-tree text, the default. */
    TEXT,
    /** Machine-readable flat JSON for CI gating. */
    JSON,
    /** Markdown tables for pasting into a pull request. */
    MD;

    /**
     * Parses a format name case-insensitively.
     *
     * @param value the format name, may be {@code null}
     * @return the matching format, or empty when unrecognized
     */
    public static Optional<ReportFormat> parse(final String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

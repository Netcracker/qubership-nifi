package org.qubership.nifi.flowdiff.cli;

import org.qubership.nifi.flowdiff.service.FlowDiffService;
import org.qubership.nifi.flowdiff.service.ReportOptions;
import picocli.CommandLine.Option;

import java.io.File;

/**
 * The report options shared by the {@code diff} and {@code git-diff} subcommands. The format is passed through as
 * typed, so an unrecognized value is reported back verbatim.
 */
final class ReportOptionsMixin {

    /** How this front end spells its output option, quoted in the message when the option is missing. */
    static final String OUTPUT_OPTION_HINT = "--output <file>";

    @Option(names = "--format", paramLabel = "<format>",
            description = "Report format: text, json, or md. Default: text.")
    private String format = "text";

    @Option(names = "--output", paramLabel = "<file>",
            description = "Report file. Required for json and md; text goes to standard output when omitted.")
    private File output;

    @Option(names = "--max-value-length", paramLabel = "<n>",
            description = "Value truncation budget for text and md; 0 disables truncation. Default: 200.")
    private int maxValueLength = 200;

    @Option(names = "--show-technical",
            description = "Also list technical changes, marked [tech], for debugging the classification.")
    private boolean showTechnical;

    /**
     * Converts the parsed options into their core form, resolving a relative output path against the base directory
     * the same way the Maven plugin resolves a relative {@code -Doutput}.
     *
     * @param basedir the directory relative paths resolve against
     * @return the report options
     */
    ReportOptions toOptions(final File basedir) {
        File resolved = output == null ? null : FlowDiffService.resolveAgainstBasedir(basedir, output);
        return new ReportOptions(format, resolved, maxValueLength, showTechnical);
    }
}

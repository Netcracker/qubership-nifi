package org.qubership.nifi.maven.flowdiff.mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.qubership.nifi.flowdiff.report.ReportModel;
import org.qubership.nifi.flowdiff.service.ReportEmitter;
import org.qubership.nifi.flowdiff.service.ReportOptions;

import java.io.File;

/**
 * Shared behavior for the flow-diff goals: the common report parameters, resolving a relative input against the Maven
 * {@code basedir}, and emitting the report in the requested format. The work itself lives in
 * {@code qubership-nifi-flow-diff-core}, which the command-line front end drives through the same services.
 */
public abstract class AbstractFlowDiffMojo extends AbstractMojo {

    /** How this front end spells its output option, quoted in the message when the option is missing. */
    static final String OUTPUT_OPTION_HINT = "-Doutput=<file>";

    /** The project base directory that relative inputs resolve against. */
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File basedir;

    /** The report format: {@code text} (default, to stdout), {@code json}, or {@code md}. */
    @Parameter(property = "format", defaultValue = "text")
    private String format;

    /** The report output file; required for {@code json} and {@code md}, optional for {@code text}. */
    @Parameter(property = "output")
    private File output;

    /** The value truncation budget for {@code text} and {@code md}; {@code 0} disables truncation. */
    @Parameter(property = "max-value-length", defaultValue = "200")
    private int maxValueLength;

    /** Whether to list technical changes in the report as well, marked {@code [tech]}, for debugging classification. */
    @Parameter(property = "show-technical", defaultValue = "false")
    private boolean showTechnical;

    /** Whether to continue past a malformed candidate file instead of failing. */
    @Parameter(property = "skip-malformed", defaultValue = "false")
    private boolean skipMalformed;

    /**
     * Returns the project base directory.
     *
     * @return the base directory
     */
    protected final File getBasedir() {
        return basedir;
    }

    /**
     * Tells whether malformed candidate files should be skipped rather than fail the goal.
     *
     * @return {@code true} when malformed files are skipped
     */
    protected final boolean isSkipMalformed() {
        return skipMalformed;
    }

    /**
     * Renders the model in the requested format and writes it to the output file, or to standard output for a
     * text report with no output file.
     *
     * @param model the diff model
     */
    protected final void emit(final ReportModel model) {
        ReportOptions options = new ReportOptions(format, output, maxValueLength, showTechnical);
        new ReportEmitter(options, OUTPUT_OPTION_HINT).emit(model);
    }
}

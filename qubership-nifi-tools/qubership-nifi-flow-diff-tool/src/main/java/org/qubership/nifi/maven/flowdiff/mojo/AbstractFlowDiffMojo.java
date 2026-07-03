package org.qubership.nifi.maven.flowdiff.mojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.qubership.nifi.maven.flowdiff.report.JsonReporter;
import org.qubership.nifi.maven.flowdiff.report.MarkdownReporter;
import org.qubership.nifi.maven.flowdiff.report.ReportFormat;
import org.qubership.nifi.maven.flowdiff.report.ReportModel;
import org.qubership.nifi.maven.flowdiff.report.TextReporter;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Shared behavior for the flow-diff goals: the common report parameters, resolving a relative input against the Maven
 * {@code basedir}, and emitting the report in the requested format. Until the JSON and Markdown renderers land, a
 * request for a non-text format fails fast rather than silently rendering text.
 */
public abstract class AbstractFlowDiffMojo extends AbstractMojo {

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
     * Jackson ObjectMapper for reuse within plugin.
     */
    public static final ObjectMapper MAPPER = new ObjectMapper();

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
     * Resolves an input against the base directory: an absolute path is used as is, a relative path is resolved
     * against {@code basedir}.
     *
     * @param input the input file, possibly relative
     * @return the resolved file
     */
    protected final File resolveAgainstBasedir(final File input) {
        if (input.isAbsolute()) {
            return input;
        }
        return new File(basedir, input.getPath());
    }

    /**
     * Renders the model in the requested format and writes it to the output file, or to standard output for a
     * text report with no output file.
     *
     * @param model the diff model
     * @throws MojoExecutionException when the format is unknown or unsupported, or the report cannot be written
     */
    protected final void emit(final ReportModel model) throws MojoExecutionException {
        ReportFormat reportFormat = ReportFormat.parse(format).orElseThrow(() -> new MojoExecutionException(
                "Unknown format '" + format + "'. Use text, json, or md."));
        if (reportFormat == ReportFormat.TEXT && output == null) {
            // Wrap standard output without closing it, so subsequent Maven output is unaffected.
            Writer out = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
            render(reportFormat, model, out);
            try {
                out.flush();
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to write the report to standard output", e);
            }
            return;
        }
        if (output == null) {
            throw new MojoExecutionException("Report format '" + reportFormat.name().toLowerCase(Locale.ROOT)
                    + "' requires -Doutput=<file>.");
        }
        try (Writer out = Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8)) {
            render(reportFormat, model, out);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write report to " + output, e);
        }
    }

    private void render(final ReportFormat reportFormat, final ReportModel model, final Writer out)
            throws MojoExecutionException {
        try {
            switch (reportFormat) {
                case TEXT -> new TextReporter(maxValueLength, showTechnical).render(model, out);
                case MD -> new MarkdownReporter(maxValueLength, showTechnical).render(model, out);
                case JSON -> new JsonReporter(MAPPER, showTechnical).render(model, out);
                default -> throw new MojoExecutionException("Unsupported format: " + reportFormat);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to render the "
                    + reportFormat.name().toLowerCase(Locale.ROOT) + " report", e);
        }
    }
}

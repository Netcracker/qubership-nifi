package org.qubership.nifi.flowdiff.service;

import org.qubership.nifi.flowdiff.error.FlowDiffExecutionException;
import org.qubership.nifi.flowdiff.report.JsonReporter;
import org.qubership.nifi.flowdiff.report.MarkdownReporter;
import org.qubership.nifi.flowdiff.report.ReportFormat;
import org.qubership.nifi.flowdiff.report.ReportModel;
import org.qubership.nifi.flowdiff.report.TextReporter;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Renders a {@link ReportModel} in the requested format and writes it to the output file, or to standard output for a
 * text report with no output file. Each front end supplies the spelling of its own output option, so the message a
 * user sees when {@code json} or {@code md} is requested without one names the option they would have typed.
 */
public final class ReportEmitter {

    private final ReportOptions options;
    private final String outputOptionHint;

    /**
     * Creates an emitter.
     *
     * @param optionsValue          the report settings
     * @param outputOptionHintValue how the calling front end spells its output option, for example
     *                              {@code -Doutput=<file>} or {@code --output <file>}
     */
    public ReportEmitter(final ReportOptions optionsValue, final String outputOptionHintValue) {
        this.options = optionsValue;
        this.outputOptionHint = outputOptionHintValue;
    }

    /**
     * Emits the model.
     *
     * @param model the diff model
     * @throws FlowDiffExecutionException when the format is unknown, an output file is required but missing, or the
     *                                    report cannot be rendered or written
     */
    public void emit(final ReportModel model) {
        ReportFormat reportFormat = ReportFormat.parse(options.format())
                .orElseThrow(() -> new FlowDiffExecutionException(
                        "Unknown format '" + options.format() + "'. Use text, json, or md."));
        if (reportFormat == ReportFormat.TEXT && options.output() == null) {
            // Wrap standard output without closing it, so subsequent output from the host is unaffected.
            Writer out = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
            render(reportFormat, model, out);
            try {
                out.flush();
            } catch (IOException e) {
                throw new FlowDiffExecutionException("Failed to write the report to standard output", e);
            }
            return;
        }
        if (options.output() == null) {
            throw new FlowDiffExecutionException("Report format '" + reportFormat.name().toLowerCase(Locale.ROOT)
                    + "' requires " + outputOptionHint + ".");
        }
        try (Writer out = Files.newBufferedWriter(options.output().toPath(), StandardCharsets.UTF_8)) {
            render(reportFormat, model, out);
        } catch (IOException e) {
            throw new FlowDiffExecutionException("Failed to write report to " + options.output(), e);
        }
    }

    private void render(final ReportFormat reportFormat, final ReportModel model, final Writer out) {
        try {
            switch (reportFormat) {
                case TEXT -> new TextReporter(options.maxValueLength(), options.showTechnical()).render(model, out);
                case MD -> new MarkdownReporter(options.maxValueLength(), options.showTechnical()).render(model, out);
                case JSON -> new JsonReporter(FlowDiffMapper.INSTANCE, options.showTechnical()).render(model, out);
                default -> throw new FlowDiffExecutionException("Unsupported format: " + reportFormat);
            }
        } catch (IOException e) {
            throw new FlowDiffExecutionException("Failed to render the "
                    + reportFormat.name().toLowerCase(Locale.ROOT) + " report", e);
        }
    }
}

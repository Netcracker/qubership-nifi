package org.qubership.nifi.flowdiff.cli;

import org.qubership.nifi.flowdiff.report.ReportModel;
import org.qubership.nifi.flowdiff.service.FlowDiffService;
import org.qubership.nifi.flowdiff.service.ReportEmitter;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;

/**
 * The {@code diff} subcommand: compares a baseline against a target and emits a read-only report. Each input may be a
 * directory tree or a single flow file, given as a relative path (resolved against {@code --basedir}) or an absolute
 * path. Both sides must be the same kind - two directories or two files.
 */
@Command(name = "diff",
        description = "Compare a baseline against a target and report the differences.")
final class DiffCommand extends AbstractFlowDiffCommand {

    @Mixin
    private ReportOptionsMixin reportOptions;

    @Option(names = "--baseline", required = true, paramLabel = "<dirOrFile>",
            description = "Baseline directory or single flow file.")
    private File baseline;

    @Option(names = "--target", required = true, paramLabel = "<dirOrFile>",
            description = "Target directory or single flow file.")
    private File target;

    @Override
    public Integer call() throws Exception {
        ReportModel model = new FlowDiffService().diff(getBasedir(), baseline, target, isSkipMalformed());
        new ReportEmitter(reportOptions.toOptions(getBasedir()), ReportOptionsMixin.OUTPUT_OPTION_HINT).emit(model);
        return ExitCode.OK;
    }
}

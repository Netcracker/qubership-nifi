package org.qubership.nifi.flowdiff.cli;

import org.qubership.nifi.flowdiff.report.ReportModel;
import org.qubership.nifi.flowdiff.service.FlowDiffService;
import org.qubership.nifi.flowdiff.service.ReportEmitter;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * The {@code git-diff} subcommand: compares the working tree against a committed baseline read through JGit. The
 * baseline is the commit that {@code --branch} resolves to (defaulting to {@code HEAD}); the target is the working
 * copy. For a branch the baseline is its tip, not the merge-base, which answers what a replacement would introduce,
 * matching how NiFi flows are integrated.
 */
@Command(name = "git-diff",
        description = "Compare the working tree against a committed baseline and report the differences.")
final class GitDiffCommand extends AbstractFlowDiffCommand {

    @Mixin
    private ReportOptionsMixin reportOptions;

    @Option(names = "--path", required = true, paramLabel = "<dirOrFile>",
            description = "Directory or single flow file to process, relative to --basedir.")
    private String path;

    @Option(names = "--branch", paramLabel = "<rev>",
            description = "Baseline revision resolved by JGit: HEAD, a full or abbreviated SHA-1, a full reference "
                    + "name, or a short branch, tag, or remote name. Default: HEAD.")
    private String branch = "HEAD";

    @Override
    public Integer call() throws Exception {
        ReportModel model = new FlowDiffService().gitDiff(getBasedir(), path, branch, isSkipMalformed());
        new ReportEmitter(reportOptions.toOptions(getBasedir()), ReportOptionsMixin.OUTPUT_OPTION_HINT).emit(model);
        return ExitCode.OK;
    }
}

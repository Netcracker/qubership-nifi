package org.qubership.nifi.flowdiff.cli;

import org.qubership.nifi.flowdiff.service.RevertSummary;
import org.qubership.nifi.flowdiff.service.TechnicalRevertService;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;

/**
 * The {@code git-revert-technical} subcommand: rewrites the working copy so its technical fields match {@code HEAD},
 * leaving environmental and significant changes untouched, and prints a per-file summary of the reverted counts.
 */
@Command(name = "git-revert-technical",
        description = "Rewrite the working copy so its technical fields match HEAD.")
final class GitRevertTechnicalCommand extends AbstractFlowDiffCommand {

    @Option(names = "--path", required = true, paramLabel = "<dirOrFile>",
            description = "Directory or single flow file to rewrite in place, relative to --basedir.")
    private String path;

    @Override
    public Integer call() throws Exception {
        // Report each file as it is rewritten, so a run that fails part way still names the files it changed.
        RevertSummary summary = new TechnicalRevertService()
                .revertGit(getBasedir(), path, isSkipMalformed(), System.out::println);
        System.out.println(summary.totalLine());
        return ExitCode.OK;
    }
}

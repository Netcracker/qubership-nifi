package org.qubership.nifi.maven.flowdiff.mojo;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.qubership.nifi.flowdiff.error.FlowDiffExecutionException;
import org.qubership.nifi.flowdiff.error.FlowDiffInputException;
import org.qubership.nifi.flowdiff.flow.FlowParseException;
import org.qubership.nifi.flowdiff.report.ReportModel;
import org.qubership.nifi.flowdiff.service.FlowDiffService;

import java.io.IOException;

/**
 * The {@code nifi-flow-diff:git-diff} goal: compares the working tree against a committed baseline read through JGit.
 * The baseline is the commit that {@code branch} resolves to (defaulting to {@code HEAD}); the target is the working
 * copy. For a branch, the baseline is its tip, not the merge-base, which answers what a replace would introduce,
 * matching how NiFi flows are integrated.
 */
@Mojo(name = "git-diff", defaultPhase = LifecyclePhase.NONE, requiresProject = false, threadSafe = true)
public final class GitDiffMojo extends AbstractFlowDiffMojo {

    /**
     * The directory or single flow file to process, relative to the Maven base directory. Declared as a string, not a
     * {@code File}, so Maven does not pre-resolve a relative value to an absolute path before the relative-only check.
     */
    @Parameter(property = "path", required = true)
    private String path;

    /**
     * The baseline revision, resolved by JGit: {@code HEAD}, a complete or abbreviated SHA-1, a complete reference
     * name ({@code refs/...}), or a short branch, tag, or remote name. Defaults to {@code HEAD}.
     */
    @Parameter(property = "branch", defaultValue = "HEAD")
    private String branch;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            ReportModel model = new FlowDiffService().gitDiff(getBasedir(), path, branch, isSkipMalformed());
            emit(model);
        } catch (FlowDiffInputException | FlowParseException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (FlowDiffExecutionException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("I/O error while reading flows from Git", e);
        }
    }
}

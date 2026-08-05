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

import java.io.File;
import java.io.IOException;

/**
 * The {@code nifi-flow-diff:diff} goal: compares a baseline against a target and emits a read-only report. Each input
 * may be a directory tree or a single flow file, given as a relative path (resolved against the Maven {@code basedir})
 * or an absolute path. Both sides must be the same kind - two directories or two files.
 */
@Mojo(name = "diff", defaultPhase = LifecyclePhase.NONE, requiresProject = false, threadSafe = true)
public final class DiffMojo extends AbstractFlowDiffMojo {

    /** The baseline directory or single flow file. */
    @Parameter(property = "baseline", required = true)
    private File baseline;

    /** The target directory or single flow file. */
    @Parameter(property = "target", required = true)
    private File target;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            ReportModel model = new FlowDiffService().diff(getBasedir(), baseline, target, isSkipMalformed());
            emit(model);
        } catch (FlowDiffInputException | FlowParseException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (FlowDiffExecutionException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("I/O error while reading flows", e);
        }
    }
}

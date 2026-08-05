package org.qubership.nifi.maven.flowdiff.mojo;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.qubership.nifi.flowdiff.flow.FlowParseException;
import org.qubership.nifi.flowdiff.service.FlowDiffInputException;
import org.qubership.nifi.flowdiff.service.RevertSummary;
import org.qubership.nifi.flowdiff.service.TechnicalRevertService;

import java.io.IOException;

/**
 * The {@code nifi-flow-diff:git-revert-technical} goal: rewrites the working copy so its technical fields match
 * {@code HEAD}, leaving environmental and significant changes untouched. Writes are guarded against clobbering a
 * concurrent edit - the raw bytes are re-read just before writing and the file is skipped when they changed - and are
 * applied atomically through a temporary file in the same directory.
 */
@Mojo(name = "git-revert-technical", defaultPhase = LifecyclePhase.NONE, requiresProject = false, threadSafe = true)
public final class GitRevertTechnicalMojo extends AbstractFlowDiffMojo {

    /**
     * The directory or single flow file to rewrite in place, relative to the Maven base directory. Declared as a
     * string, not a {@code File}, so Maven does not pre-resolve a relative value to an absolute path before the
     * relative-only check.
     */
    @Parameter(property = "path", required = true)
    private String path;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        RevertSummary summary;
        try {
            summary = new TechnicalRevertService().revertGit(getBasedir(), path, isSkipMalformed());
        } catch (FlowDiffInputException | FlowParseException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("I/O error during revert", e);
        }
        for (String line : summary.summaryLines()) {
            System.out.println(line);
        }
        System.out.println(summary.totalLine());
    }
}

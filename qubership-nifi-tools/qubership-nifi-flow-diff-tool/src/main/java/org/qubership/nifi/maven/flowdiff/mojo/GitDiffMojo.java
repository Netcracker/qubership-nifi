package org.qubership.nifi.maven.flowdiff.mojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.qubership.nifi.maven.flowdiff.flow.FlowParseException;
import org.qubership.nifi.maven.flowdiff.io.Candidate;
import org.qubership.nifi.maven.flowdiff.io.FlowClassifier;
import org.qubership.nifi.maven.flowdiff.io.GitSource;
import org.qubership.nifi.maven.flowdiff.report.DiffModelBuilder;
import org.qubership.nifi.maven.flowdiff.report.ReportModel;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * The {@code nifi-flow-diff:git-diff} goal: compares the working tree against a committed baseline read through JGit.
 * The baseline is the tip of {@code HEAD} or of {@code branch}; the target is the working copy. Comparing against the
 * branch tip (not the merge-base) answers what a replace would introduce, matching how NiFi flows are integrated.
 */
@Mojo(name = "git-diff", defaultPhase = LifecyclePhase.NONE, requiresProject = false, threadSafe = true)
public final class GitDiffMojo extends AbstractFlowDiffMojo {

    /**
     * The directory or single flow file to process, relative to the Maven base directory. Declared as a string, not a
     * {@code File}, so Maven does not pre-resolve a relative value to an absolute path before the relative-only check.
     */
    @Parameter(property = "path", required = true)
    private String path;

    /** The branch whose tip is the baseline; defaults to {@code HEAD}. */
    @Parameter(property = "branch", defaultValue = "HEAD")
    private String branch;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        FlowClassifier classifier = new FlowClassifier(isSkipMalformed(), MAPPER, getLog());
        try (GitSource git = new GitSource(getBasedir(), new File(path), classifier)) {
            Map<String, Candidate> committed = git.discoverCommitted(branch);
            Map<String, Candidate> working = git.discoverWorking();
            ReportModel model = new DiffModelBuilder(getLog()).build(committed, working, true);
            emit(model);
        } catch (FlowParseException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("I/O error while reading flows from Git", e);
        }
    }
}

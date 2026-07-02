package org.qubership.nifi.maven.flowdiff.mojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.qubership.nifi.maven.flowdiff.flow.FlowParseException;
import org.qubership.nifi.maven.flowdiff.io.DirectorySource;
import org.qubership.nifi.maven.flowdiff.io.FlowClassifier;
import org.qubership.nifi.maven.flowdiff.io.SideEntry;
import org.qubership.nifi.maven.flowdiff.report.DiffModelBuilder;
import org.qubership.nifi.maven.flowdiff.report.ReportModel;

import java.io.File;
import java.io.IOException;
import java.util.Map;

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
        File base = resolveAgainstBasedir(baseline);
        File tgt = resolveAgainstBasedir(target);
        if (!base.exists()) {
            throw new MojoFailureException("Baseline path does not exist: " + base);
        }
        if (!tgt.exists()) {
            throw new MojoFailureException("Target path does not exist: " + tgt);
        }
        ObjectMapper mapper = newMapper();
        FlowClassifier classifier = new FlowClassifier(isSkipMalformed(), mapper, getLog());
        DirectorySource baseSource = new DirectorySource(base, classifier);
        DirectorySource targetSource = new DirectorySource(tgt, classifier);
        if (baseSource.isDirectory() != targetSource.isDirectory()) {
            throw new MojoFailureException("baseline and target must both be directories or both be single files: "
                    + base + " vs " + tgt);
        }
        try {
            Map<String, SideEntry> baseEntries = baseSource.read();
            Map<String, SideEntry> targetEntries = targetSource.read();
            ReportModel model = new DiffModelBuilder(getLog())
                    .build(baseEntries, targetEntries, baseSource.isDirectory());
            emit(model);
        } catch (FlowParseException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("I/O error while reading flows", e);
        }
    }
}

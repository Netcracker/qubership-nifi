package org.qubership.nifi.flowdiff.service;

import org.qubership.nifi.flowdiff.error.FlowDiffInputException;
import org.qubership.nifi.flowdiff.io.Candidate;
import org.qubership.nifi.flowdiff.io.DirectorySource;
import org.qubership.nifi.flowdiff.io.FlowClassifier;
import org.qubership.nifi.flowdiff.io.GitSource;
import org.qubership.nifi.flowdiff.report.DiffModelBuilder;
import org.qubership.nifi.flowdiff.report.ReportModel;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Builds a diff model from a pair of inputs. The two entry points differ only in where the baseline comes from: from a
 * second path on disk, or from a commit read through JGit.
 */
public final class FlowDiffService {

    /**
     * Resolves an input against a base directory: an absolute path is used as is, a relative path is resolved against
     * {@code basedir}.
     *
     * @param basedir the base directory relative inputs resolve against
     * @param input   the input file, possibly relative
     * @return the resolved file
     */
    public static File resolveAgainstBasedir(final File basedir, final File input) {
        if (input.isAbsolute()) {
            return input;
        }
        return new File(basedir, input.getPath());
    }

    /**
     * Compares a baseline against a target on disk. Each input may be a directory tree or a single flow file; both
     * sides must be the same kind.
     *
     * @param basedir       the base directory relative inputs resolve against
     * @param baseline      the baseline directory or single flow file
     * @param target        the target directory or single flow file
     * @param skipMalformed whether to continue past a malformed candidate file instead of failing
     * @return the assembled report model
     * @throws IOException                when a flow cannot be read
     * @throws FlowDiffInputException     when a path is missing or the two sides are not the same kind
     */
    public ReportModel diff(final File basedir, final File baseline, final File target, final boolean skipMalformed)
            throws IOException {
        File base = resolveAgainstBasedir(basedir, baseline);
        File tgt = resolveAgainstBasedir(basedir, target);
        if (!base.exists()) {
            throw new FlowDiffInputException("Baseline path does not exist: " + base);
        }
        if (!tgt.exists()) {
            throw new FlowDiffInputException("Target path does not exist: " + tgt);
        }
        FlowClassifier classifier = new FlowClassifier(skipMalformed, FlowDiffMapper.INSTANCE);
        DirectorySource baseSource = new DirectorySource(base, classifier);
        DirectorySource targetSource = new DirectorySource(tgt, classifier);
        if (baseSource.isDirectory() != targetSource.isDirectory()) {
            throw new FlowDiffInputException("baseline and target must both be directories or both be single files: "
                    + base + " vs " + tgt);
        }
        Map<String, Candidate> baseCandidates = baseSource.discover();
        Map<String, Candidate> targetCandidates = targetSource.discover();
        return new DiffModelBuilder().build(baseCandidates, targetCandidates, baseSource.isDirectory());
    }

    /**
     * Compares the working tree against a committed baseline. For a branch the baseline is its tip, not the
     * merge-base, which answers what a replacement would introduce.
     *
     * @param basedir       the base directory the relative path resolves against, and where the enclosing repository
     *                      is discovered from
     * @param path          the directory or single flow file to process, which must be relative
     * @param branch        the baseline revision, resolved by JGit
     * @param skipMalformed whether to continue past a malformed candidate file instead of failing
     * @return the assembled report model
     * @throws IOException            when a flow cannot be read from Git or the working tree
     * @throws FlowDiffInputException when the path is absolute, resolves outside the worktree, no worktree encloses
     *                                the base directory, or the branch cannot be resolved
     */
    public ReportModel gitDiff(final File basedir, final String path, final String branch,
            final boolean skipMalformed) throws IOException {
        FlowClassifier classifier = new FlowClassifier(skipMalformed, FlowDiffMapper.INSTANCE);
        try (GitSource git = new GitSource(basedir, new File(path), classifier)) {
            Map<String, Candidate> committed = git.discoverCommitted(branch);
            Map<String, Candidate> working = git.discoverWorking();
            return new DiffModelBuilder().build(committed, working, true);
        }
    }
}

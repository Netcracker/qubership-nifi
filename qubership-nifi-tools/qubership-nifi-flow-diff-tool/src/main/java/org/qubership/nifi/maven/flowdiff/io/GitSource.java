package org.qubership.nifi.maven.flowdiff.io;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.qubership.nifi.maven.flowdiff.flow.FlowParseException;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads both sides of a Git-mode comparison, keyed by worktree-relative path so the sides align. The committed
 * baseline is read from a commit's tree through JGit without touching the working copy; the target is the working tree
 * on disk. The {@code path} is required to be relative and to lie inside the enclosing worktree; an absolute path is
 * rejected before the containment check. Discovery enumerates the candidates on each side without reading their content
 * - the working tree by directory walk, the committed baseline by a tree walk that records each blob's {@code ObjectId}
 * - so each flow is read and parsed only when the pair loop loads it.
 */
public final class GitSource implements Closeable {

    private final Repository repository;
    private final Path worktreeRoot;
    private final String worktreeRelative;
    private final boolean pathExists;
    private final FlowClassifier classifier;

    /**
     * Opens the enclosing repository and resolves the input path to a worktree-relative form.
     *
     * @param basedir         the Maven base directory the relative path resolves against
     * @param pathInput        the relative input path (directory or single file)
     * @param classifierValue the flow classifier
     * @throws IOException when the repository cannot be opened or the path resolved
     */
    public GitSource(final File basedir, final File pathInput, final FlowClassifier classifierValue)
            throws IOException {
        if (pathInput.isAbsolute()) {
            throw new FlowParseException("Git-mode path must be relative, not absolute: " + pathInput);
        }
        this.classifier = classifierValue;
        this.repository = new FileRepositoryBuilder().readEnvironment().findGitDir(basedir).build();
        if (repository.getWorkTree() == null || repository.getDirectory() == null) {
            throw new FlowParseException("No Git worktree encloses: " + basedir);
        }
        this.worktreeRoot = repository.getWorkTree().getCanonicalFile().toPath();
        File resolved = new File(basedir, pathInput.getPath());
        this.pathExists = resolved.exists();
        this.worktreeRelative = worktreeRelative(resolved, pathInput);
    }

    private String worktreeRelative(final File resolved, final File pathInput) throws IOException {
        Path candidate;
        if (pathExists) {
            candidate = resolved.getCanonicalFile().toPath();
            requireInside(candidate, pathInput);
        } else {
            File parent = resolved.getAbsoluteFile().getParentFile();
            while (parent != null && !parent.exists()) {
                parent = parent.getParentFile();
            }
            if (parent == null) {
                throw new FlowParseException("Cannot resolve a worktree parent for: " + pathInput);
            }
            Path parentCanonical = parent.getCanonicalFile().toPath();
            requireInside(parentCanonical, pathInput);
            Path remainder = parent.getAbsoluteFile().toPath().normalize()
                    .relativize(resolved.getAbsoluteFile().toPath().normalize());
            candidate = parentCanonical.resolve(remainder);
        }
        return JsonFiles.toPosix(worktreeRoot.relativize(candidate));
    }

    private void requireInside(final Path candidate, final File pathInput) {
        if (!candidate.startsWith(worktreeRoot)) {
            throw new FlowParseException("Path resolves outside the Git worktree: " + pathInput);
        }
    }

    /**
     * Returns the worktree-relative form of the input path, used to scope the baseline tree walk and prefix report
     * paths.
     *
     * @return the worktree-relative path in {@code /}-separated form
     */
    public String getWorktreeRelative() {
        return worktreeRelative;
    }

    /**
     * Tells whether the input path exists in the working tree.
     *
     * @return {@code true} when the path exists on disk
     */
    public boolean isPathPresent() {
        return pathExists;
    }

    /**
     * Returns the absolute path of a worktree-relative entry on disk.
     *
     * @param worktreeRelativeKey the worktree-relative key of an entry
     * @return the on-disk path of that entry
     */
    public Path workingFile(final String worktreeRelativeKey) {
        return worktreeRoot.resolve(worktreeRelativeKey);
    }

    /**
     * Discovers the working-tree flow candidates under the input path without reading their content, keyed by
     * worktree-relative path. Each candidate reads and classifies its file only when loaded.
     *
     * @return the working-tree candidates keyed by worktree-relative path
     * @throws IOException when the directory tree cannot be walked
     */
    public Map<String, Candidate> discoverWorking() throws IOException {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        File target = worktreeRelative.isEmpty()
                ? worktreeRoot.toFile() : new File(worktreeRoot.toFile(), worktreeRelative);
        if (!target.exists()) {
            return candidates;
        }
        if (target.isDirectory()) {
            for (Path file : JsonFiles.under(target.toPath())) {
                String key = JsonFiles.toPosix(worktreeRoot.relativize(file));
                candidates.put(key, () -> classifier.classify(file.toFile(), key));
            }
        } else {
            candidates.put(worktreeRelative, () -> classifier.classify(target, worktreeRelative));
        }
        return candidates;
    }

    /**
     * Discovers the committed flow candidates at the tip of a branch (or {@code HEAD}) under the input path, recording
     * each blob's {@code ObjectId} without reading it, keyed by worktree-relative path. Each candidate opens and
     * classifies its blob only when loaded, so the repository must stay open (this source not yet closed) until the
     * candidates are loaded.
     *
     * @param branch the branch name or {@code HEAD} whose tip is the baseline
     * @return the committed candidates keyed by worktree-relative path
     * @throws IOException when a tree cannot be walked
     */
    public Map<String, Candidate> discoverCommitted(final String branch) throws IOException {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        ObjectId commitId = repository.resolve(branch + "^{commit}");
        if (commitId == null) {
            throw new FlowParseException("Cannot resolve branch or ref: " + branch);
        }
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(commitId);
            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                if (!worktreeRelative.isEmpty()) {
                    treeWalk.setFilter(PathFilter.create(worktreeRelative));
                }
                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    if (!path.endsWith(JsonFiles.JSON_SUFFIX)) {
                        continue;
                    }
                    ObjectId blobId = treeWalk.getObjectId(0);
                    candidates.put(path, () -> classifier.classify(repository.open(blobId).getBytes(), path));
                }
            }
        }
        return candidates;
    }

    @Override
    public void close() throws IOException {
        repository.close();
    }
}

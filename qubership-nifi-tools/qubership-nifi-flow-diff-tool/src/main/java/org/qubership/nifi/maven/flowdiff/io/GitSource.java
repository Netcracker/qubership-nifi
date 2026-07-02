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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads both sides of a Git-mode comparison, keyed by worktree-relative path so the sides align. The committed
 * baseline is read from a commit's tree through JGit without touching the working copy; the target is the working tree
 * on disk. The {@code path} is required to be relative and to lie inside the enclosing worktree; an absolute path is
 * rejected before the containment check.
 */
public final class GitSource implements Closeable {

    private static final String JSON_SUFFIX = ".json";

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
        return toPosix(worktreeRoot.relativize(candidate));
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
     * Reads the flow candidates from the working tree under the input path.
     *
     * @return the working-tree entries keyed by worktree-relative path
     * @throws IOException when a file cannot be read
     */
    public Map<String, SideEntry> readWorking() throws IOException {
        Map<String, SideEntry> entries = new LinkedHashMap<>();
        File target = worktreeRelative.isEmpty()
                ? worktreeRoot.toFile() : new File(worktreeRoot.toFile(), worktreeRelative);
        if (!target.exists()) {
            return entries;
        }
        if (target.isDirectory()) {
            for (Path file : jsonFiles(target.toPath())) {
                putEntry(entries, toPosix(worktreeRoot.relativize(file)),
                        Files.readString(file, StandardCharsets.UTF_8));
            }
        } else {
            putEntry(entries, worktreeRelative, Files.readString(target.toPath(), StandardCharsets.UTF_8));
        }
        return entries;
    }

    /**
     * Reads the committed flow candidates from the tip of a branch (or {@code HEAD}) under the input path.
     *
     * @param branch the branch name or {@code HEAD} whose tip is the baseline
     * @return the committed entries keyed by worktree-relative path
     * @throws IOException when a tree or blob cannot be read
     */
    public Map<String, SideEntry> readCommitted(final String branch) throws IOException {
        Map<String, SideEntry> entries = new LinkedHashMap<>();
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
                    if (!path.endsWith(JSON_SUFFIX)) {
                        continue;
                    }
                    byte[] bytes = repository.open(treeWalk.getObjectId(0)).getBytes();
                    putEntry(entries, path, new String(bytes, StandardCharsets.UTF_8));
                }
            }
        }
        return entries;
    }

    private void putEntry(final Map<String, SideEntry> entries, final String key, final String content) {
        SideEntry entry = classifier.classify(content, key);
        if (entry != null) {
            entries.put(key, entry);
        }
    }

    private static List<Path> jsonFiles(final Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = new ArrayList<>(walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(JSON_SUFFIX))
                    .toList());
            files.sort(Path::compareTo);
            return files;
        }
    }

    private static String toPosix(final Path path) {
        return path.toString().replace('\\', '/');
    }

    @Override
    public void close() throws IOException {
        repository.close();
    }
}

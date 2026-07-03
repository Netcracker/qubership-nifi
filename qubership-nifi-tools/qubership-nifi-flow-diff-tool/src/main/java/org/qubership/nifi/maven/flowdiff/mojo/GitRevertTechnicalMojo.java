package org.qubership.nifi.maven.flowdiff.mojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;
import org.qubership.nifi.maven.flowdiff.flow.FlowParseException;
import org.qubership.nifi.maven.flowdiff.io.Candidate;
import org.qubership.nifi.maven.flowdiff.io.FlowClassifier;
import org.qubership.nifi.maven.flowdiff.io.GitSource;
import org.qubership.nifi.maven.flowdiff.io.SideEntry;
import org.qubership.nifi.maven.flowdiff.revert.RevertCounts;
import org.qubership.nifi.maven.flowdiff.revert.TechnicalReverter;
import org.qubership.nifi.tools.jsonformat.JsonFormatReformatter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
        FlowClassifier classifier = new FlowClassifier(isSkipMalformed(), MAPPER, getLog());
        TechnicalReverter reverter = new TechnicalReverter();
        JsonFormatReformatter reformatter = new JsonFormatReformatter(MAPPER);
        List<String> summary = new ArrayList<>();
        int reverted = 0;

        try (GitSource git = new GitSource(getBasedir(), new File(path), classifier)) {
            Map<String, Candidate> committed = git.discoverCommitted("HEAD");
            Map<String, Candidate> working = git.discoverWorking();
            if (!git.isPathPresent()) {
                getLog().warn("Path is absent from the working tree; nothing to revert: " + path);
            }
            Set<String> allKeys = new TreeSet<>(committed.keySet());
            allKeys.addAll(working.keySet());
            for (String key : allKeys) {
                SideEntry committedEntry = load(committed.get(key));
                SideEntry workingEntry = load(working.get(key));
                if (!bothFlows(committedEntry, workingEntry)) {
                    continue;
                }
                RevertCounts counts = rewrite(git.workingFile(key), committedEntry.getFlow(), reverter,
                        reformatter, key);
                if (counts != null && counts.total() > 0) {
                    reverted += counts.total();
                    summary.add(summaryLine(key, counts));
                }
            }
        } catch (FlowParseException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("I/O error during revert", e);
        }
        printSummary(summary, reverted);
    }

    private RevertCounts rewrite(final Path file, final FlowExport committedFlow, final TechnicalReverter reverter,
            final JsonFormatReformatter reformatter, final String key) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        String content = new String(raw, StandardCharsets.UTF_8);
        FlowExport workingFresh = FlowExport.of(key, MAPPER.readTree(content));
        RevertCounts counts = reverter.revert(committedFlow, workingFresh);
        if (counts.total() == 0) {
            return counts;
        }
        if (!Arrays.equals(raw, Files.readAllBytes(file))) {
            getLog().warn("File changed between read and write; skipping: " + key);
            return null;
        }
        atomicWrite(file, reformatter.write(workingFresh.getRoot(), reformatter.detect(content)));
        return counts;
    }

    private static void atomicWrite(final Path file, final String content) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static SideEntry load(final Candidate candidate) throws IOException {
        return candidate == null ? null : candidate.load();
    }

    private boolean bothFlows(final SideEntry committedEntry, final SideEntry workingEntry) {
        boolean committedFlow = committedEntry != null && committedEntry.isFlow();
        boolean workingFlow = workingEntry != null && workingEntry.isFlow();
        if (committedFlow && workingFlow) {
            return true;
        }
        if (committedFlow && workingEntry != null) {
            getLog().warn("Flow present as baseline but a non-flow JSON on the target side: "
                    + committedEntry.getDisplayPath() + " vs " + workingEntry.getDisplayPath());
        } else if (workingFlow && committedEntry != null) {
            getLog().warn("Flow present as target but a non-flow JSON on the baseline side: "
                    + workingEntry.getDisplayPath() + " vs " + committedEntry.getDisplayPath());
        }
        return false;
    }

    private static String summaryLine(final String key, final RevertCounts counts) {
        return key + ": " + counts.total() + " reverted (instanceIdentifier=" + counts.instanceIdentifier()
                + ", rootIdentifier=" + counts.rootIdentifier()
                + ", groupIdentifier=" + counts.groupIdentifier()
                + ", endpointGroupId=" + counts.endpointGroupId() + ")";
    }

    private void printSummary(final List<String> summary, final int reverted) {
        StringBuilder sb = new StringBuilder();
        summary.forEach(line -> sb.append(line).append('\n'));
        if (summary.isEmpty()) {
            sb.append("Total: 0 files rewritten");
        } else {
            sb.append("Total: ").append(summary.size()).append(" files rewritten, ")
                    .append(reverted).append(" technical changes reverted.");
        }
        System.out.println(sb);
    }
}

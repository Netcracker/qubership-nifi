package org.qubership.nifi.flowdiff.service;

import org.qubership.nifi.flowdiff.flow.FlowExport;
import org.qubership.nifi.flowdiff.io.Candidate;
import org.qubership.nifi.flowdiff.io.FlowClassifier;
import org.qubership.nifi.flowdiff.io.GitSource;
import org.qubership.nifi.flowdiff.io.SideEntry;
import org.qubership.nifi.flowdiff.revert.RevertCounts;
import org.qubership.nifi.flowdiff.revert.TechnicalReverter;
import org.qubership.nifi.tools.jsonformat.JsonFormatReformatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rewrites a working copy so its technical fields match a committed baseline, leaving environmental and significant
 * changes untouched. Writes are guarded against clobbering a concurrent edit - the raw bytes are re-read just before
 * writing and the file is skipped when they changed - and are applied atomically through a temporary file in the same
 * directory. The original formatting of each file is reproduced, so a rewrite produces no whitespace-only noise.
 */
public final class TechnicalRevertService {

    private static final Logger LOG = LoggerFactory.getLogger(TechnicalRevertService.class);

    /**
     * Reverts the technical changes the working tree carries relative to {@code HEAD}.
     *
     * @param basedir       the base directory the relative path resolves against, and where the enclosing repository
     *                      is discovered from
     * @param path          the directory or single flow file to rewrite in place, which must be relative
     * @param skipMalformed whether to continue past a malformed candidate file instead of failing
     * @return what was rewritten
     * @throws IOException when a flow cannot be read or written
     */
    public RevertSummary revertGit(final File basedir, final String path, final boolean skipMalformed)
            throws IOException {
        FlowClassifier classifier = new FlowClassifier(skipMalformed, FlowDiffMapper.INSTANCE);
        TechnicalReverter reverter = new TechnicalReverter();
        JsonFormatReformatter reformatter = new JsonFormatReformatter(FlowDiffMapper.INSTANCE);
        Map<String, RevertCounts> written = new LinkedHashMap<>();

        try (GitSource git = new GitSource(basedir, new File(path), classifier)) {
            Map<String, Candidate> committed = git.discoverCommitted("HEAD");
            Map<String, Candidate> working = git.discoverWorking();
            if (!git.isPathPresent()) {
                LOG.warn("Path is absent from the working tree; nothing to revert: {}", path);
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
                    written.put(key, counts);
                }
            }
        }
        return new RevertSummary(written);
    }

    private RevertCounts rewrite(final Path file, final FlowExport committedFlow, final TechnicalReverter reverter,
            final JsonFormatReformatter reformatter, final String key) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        String content = new String(raw, StandardCharsets.UTF_8);
        FlowExport workingFresh = FlowExport.of(key, FlowDiffMapper.INSTANCE.readTree(content));
        RevertCounts counts = reverter.revert(committedFlow, workingFresh);
        if (counts.total() == 0) {
            return counts;
        }
        if (!Arrays.equals(raw, Files.readAllBytes(file))) {
            LOG.warn("File changed between read and write; skipping: {}", key);
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
            LOG.warn("Flow present as baseline but a non-flow JSON on the target side: {} vs {}",
                    committedEntry.getDisplayPath(), workingEntry.getDisplayPath());
        } else if (workingFlow && committedEntry != null) {
            LOG.warn("Flow present as target but a non-flow JSON on the baseline side: {} vs {}",
                    workingEntry.getDisplayPath(), committedEntry.getDisplayPath());
        }
        return false;
    }
}

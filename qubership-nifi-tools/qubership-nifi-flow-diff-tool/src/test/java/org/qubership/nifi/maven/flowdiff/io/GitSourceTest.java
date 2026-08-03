package org.qubership.nifi.maven.flowdiff.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.nifi.maven.flowdiff.flow.FlowParseException;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GitSource}: reading the committed baseline and the working tree keyed by worktree-relative path,
 * rejecting an absolute path, and rejecting a path that resolves outside the worktree.
 */
@ExtendWith(MockitoExtension.class)
class GitSourceTest {

    private static final String FLOW = """
            {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","properties":{"k":"1"}}]}}""";

    @Mock
    private Log log;

    @TempDir
    private Path dir;

    @BeforeEach
    void initRepo() throws Exception {
        Files.createDirectories(dir.resolve("flows"));
        Files.writeString(dir.resolve("flows/a.json"), FLOW, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("flows/params.json"), "{\"x\":1}", StandardCharsets.UTF_8);
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setAuthor("t", "t@e").setSign(false).call();
        }
    }

    private FlowClassifier classifier() {
        return new FlowClassifier(false, new ObjectMapper(), log);
    }

    @Test
    void readsCommittedAndWorkingKeyedByWorktreePath() throws Exception {
        try (GitSource git = new GitSource(dir.toFile(), new File("flows"), classifier())) {
            assertEquals("flows", git.getWorktreeRelative());
            Map<String, Candidate> committed = git.discoverCommitted("HEAD");
            Map<String, Candidate> working = git.discoverWorking();
            assertTrue(committed.get("flows/a.json").load().isFlow());
            assertEquals(CandidateKind.NON_FLOW, committed.get("flows/params.json").load().getKind());
            assertTrue(working.get("flows/a.json").load().isFlow());
        }
    }

    @Test
    void absolutePathRejectedEvenInsideWorktree() {
        File absolute = dir.resolve("flows/a.json").toAbsolutePath().toFile();
        assertThrows(FlowParseException.class,
                () -> new GitSource(dir.toFile(), absolute, classifier()).close());
    }

    @Test
    void pathOutsideWorktreeRejected() {
        File outside = new File(".." + File.separator + "outside");
        FlowParseException ex = assertThrows(FlowParseException.class,
                () -> new GitSource(dir.toFile(), outside, classifier()).close());
        assertTrue(ex.getMessage().contains("outside"), ex.getMessage());
    }

    @Test
    void missingBranchRejected() throws Exception {
        try (GitSource git = new GitSource(dir.toFile(), new File("flows"), classifier())) {
            FlowParseException ex = assertThrows(FlowParseException.class,
                    () -> git.discoverCommitted("no-such-branch"));
            assertTrue(ex.getMessage().contains("no-such-branch"), ex.getMessage());
        }
    }
}

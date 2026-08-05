package org.qubership.nifi.flowdiff.service;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.flowdiff.compare.ChangeCategory;
import org.qubership.nifi.flowdiff.error.FlowDiffInputException;
import org.qubership.nifi.flowdiff.report.ReportModel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FlowDiffService}: the input checks both entry points share, and the Git-mode baseline being the tip
 * of the named revision rather than the merge-base.
 */
class FlowDiffServiceTest {

    private static final String COMMITTED = """
            {"flowContents":{"identifier":"root-committed","name":"R","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"p1-c",
               "groupIdentifier":"root-committed","properties":{"k":"v"}}]}}""";
    private static final String WORKING = """
            {"flowContents":{"identifier":"root-working","name":"R","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"p1-w",
               "groupIdentifier":"root-working","properties":{"k":"v2"}}]}}""";
    private static final String SIMPLE_FLOW = """
            {"flowContents":{"identifier":"r2","name":"R2","componentType":"PROCESS_GROUP","processors":[]}}""";

    @TempDir
    private Path dir;

    private final FlowDiffService service = new FlowDiffService();

    private File write(final String relative, final String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }

    private Git openOrInit() throws Exception {
        if (dir.resolve(".git").toFile().exists()) {
            return Git.open(dir.toFile());
        }
        return Git.init().setDirectory(dir.toFile()).call();
    }

    private void commitAll() throws Exception {
        try (Git git = openOrInit()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("commit").setAuthor("t", "t@e").setSign(false).call();
        }
    }

    @Test
    void missingBaselineFails() throws Exception {
        File target = write("b.json", WORKING);
        FlowDiffInputException ex = assertThrows(FlowDiffInputException.class,
                () -> service.diff(dir.toFile(), dir.resolve("missing.json").toFile(), target, false));
        assertTrue(ex.getMessage().contains("does not exist"), ex.getMessage());
    }

    @Test
    void missingTargetFails() throws Exception {
        File baseline = write("a.json", COMMITTED);
        FlowDiffInputException ex = assertThrows(FlowDiffInputException.class,
                () -> service.diff(dir.toFile(), baseline, dir.resolve("missing.json").toFile(), false));
        assertTrue(ex.getMessage().contains("does not exist"), ex.getMessage());
    }

    @Test
    void directoryVersusFileMismatchFails() throws Exception {
        File baselineDir = Files.createDirectory(dir.resolve("base")).toFile();
        File targetFile = write("b.json", WORKING);
        FlowDiffInputException ex = assertThrows(FlowDiffInputException.class,
                () -> service.diff(dir.toFile(), baselineDir, targetFile, false));
        assertTrue(ex.getMessage().contains("both be directories or both be single files"), ex.getMessage());
    }

    @Test
    void relativeInputsResolveAgainstBasedir() throws Exception {
        write("a.json", COMMITTED);
        write("b.json", WORKING);
        ReportModel model = service.diff(dir.toFile(), new File("a.json"), new File("b.json"), false);
        assertEquals(1, model.getFlows().size());
        assertEquals("b.json", model.getFlows().get(0).getPath());
    }

    @Test
    void gitDiffReportsSignificantAndCountsTechnicalAgainstHead() throws Exception {
        write("flows/a.json", COMMITTED);
        commitAll();
        write("flows/a.json", WORKING);

        ReportModel model = service.gitDiff(dir.toFile(), "flows", "HEAD", false);

        assertEquals(1, model.getFlows().size());
        assertEquals(3, model.total(ChangeCategory.TECHNICAL));
        assertEquals(1, model.total(ChangeCategory.SIGNIFICANT));
    }

    @Test
    void gitDiffDiscoversFlowRemovedFromWorkingTree() throws Exception {
        write("flows/a.json", COMMITTED);
        write("flows/b.json", SIMPLE_FLOW);
        commitAll();
        Files.delete(dir.resolve("flows/b.json"));

        ReportModel model = service.gitDiff(dir.toFile(), "flows", "HEAD", false);

        assertEquals(List.of("flows/b.json"), model.getRemovedFlows());
    }

    @Test
    void gitDiffComparesAgainstBranchTipNotMergeBase() throws Exception {
        write("flows/a.json", COMMITTED);
        commitAll();
        String main;
        try (Git git = openOrInit()) {
            main = git.getRepository().getBranch();
            git.checkout().setCreateBranch(true).setName("feature").call();
        }
        write("flows/c.json", SIMPLE_FLOW);
        commitAll();
        try (Git git = openOrInit()) {
            git.checkout().setName(main).call();
        }

        ReportModel model = service.gitDiff(dir.toFile(), "flows", "feature", false);

        assertEquals(List.of("flows/c.json"), model.getRemovedFlows());
    }

    @Test
    void gitDiffRejectsAbsolutePath() throws Exception {
        write("flows/a.json", COMMITTED);
        commitAll();
        String absolute = dir.resolve("flows/a.json").toString();
        FlowDiffInputException ex = assertThrows(FlowDiffInputException.class,
                () -> service.gitDiff(dir.toFile(), absolute, "HEAD", false));
        assertTrue(ex.getMessage().contains("must be relative"), ex.getMessage());
    }
}

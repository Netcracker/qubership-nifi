package org.qubership.nifi.maven.flowdiff.mojo;

import org.apache.maven.plugin.logging.Log;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GitDiffMojo}: classifying working-tree changes against the committed baseline, discovering a flow
 * removed from the working tree, and comparing against a branch tip rather than the merge-base.
 */
@ExtendWith(MockitoExtension.class)
class GitDiffMojoTest {

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

    @Mock
    private Log log;

    @TempDir
    private Path dir;

    private Git openOrInit() throws Exception {
        if (dir.resolve(".git").toFile().exists()) {
            return Git.open(dir.toFile());
        }
        return Git.init().setDirectory(dir.toFile()).call();
    }

    private void write(final String relative, final String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void commitAll() throws Exception {
        try (Git git = openOrInit()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("commit").setAuthor("t", "t@e").setSign(false).call();
        }
    }

    private void setField(final Class<?> clazz, final Object target, final String name, final Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private String runDiff(final String path, final String branch) throws Exception {
        File output = dir.resolve("report.txt").toFile();
        GitDiffMojo mojo = new GitDiffMojo();
        mojo.setLog(log);
        setField(AbstractFlowDiffMojo.class, mojo, "basedir", dir.toFile());
        setField(AbstractFlowDiffMojo.class, mojo, "format", "text");
        setField(AbstractFlowDiffMojo.class, mojo, "output", output);
        setField(AbstractFlowDiffMojo.class, mojo, "maxValueLength", 200);
        setField(AbstractFlowDiffMojo.class, mojo, "skipMalformed", false);
        setField(GitDiffMojo.class, mojo, "path", path);
        setField(GitDiffMojo.class, mojo, "branch", branch);
        mojo.execute();
        return Files.readString(output.toPath(), StandardCharsets.UTF_8);
    }

    @Test
    void reportsSignificantAndCountsTechnicalAgainstHead() throws Exception {
        write("flows/a.json", COMMITTED);
        commitAll();
        write("flows/a.json", WORKING);

        String report = runDiff("flows", "HEAD");

        assertTrue(report.contains("properties/k: v -> v2"), report);
        assertTrue(report.contains("technical: 3"), report);
        assertFalse(report.contains("instanceIdentifier"), report);
    }

    @Test
    void discoversFlowRemovedFromWorkingTree() throws Exception {
        write("flows/a.json", COMMITTED);
        write("flows/b.json", SIMPLE_FLOW);
        commitAll();
        Files.delete(dir.resolve("flows/b.json"));

        String report = runDiff("flows", "HEAD");

        assertTrue(report.contains("removed flow: flows/b.json"), report);
    }

    @Test
    void comparesAgainstBranchTip() throws Exception {
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

        String report = runDiff("flows", "feature");

        assertTrue(report.contains("removed flow: flows/c.json"), report);
    }
}

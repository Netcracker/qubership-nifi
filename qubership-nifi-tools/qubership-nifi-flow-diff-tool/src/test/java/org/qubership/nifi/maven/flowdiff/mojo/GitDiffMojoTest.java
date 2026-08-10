package org.qubership.nifi.maven.flowdiff.mojo;

import org.apache.maven.plugin.MojoFailureException;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GitDiffMojo}: that {@code path}, {@code branch}, {@code format}, and {@code output} reach the core
 * service and produce a report on disk, and that an unresolvable revision surfaces as a {@code MojoFailureException}.
 */
class GitDiffMojoTest {

    private static final String COMMITTED = """
            {"flowContents":{"identifier":"root-committed","name":"R","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"p1-c",
               "groupIdentifier":"root-committed","properties":{"k":"v"}}]}}""";
    private static final String WORKING = """
            {"flowContents":{"identifier":"root-working","name":"R","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"p1-w",
               "groupIdentifier":"root-working","properties":{"k":"v2"}}]}}""";

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

    private GitDiffMojo mojo(final String path, final String branch, final File output) throws Exception {
        GitDiffMojo mojo = new GitDiffMojo();
        setField(AbstractFlowDiffMojo.class, mojo, "basedir", dir.toFile());
        setField(AbstractFlowDiffMojo.class, mojo, "format", "text");
        setField(AbstractFlowDiffMojo.class, mojo, "output", output);
        setField(AbstractFlowDiffMojo.class, mojo, "maxValueLength", 200);
        setField(AbstractFlowDiffMojo.class, mojo, "skipMalformed", false);
        setField(GitDiffMojo.class, mojo, "path", path);
        setField(GitDiffMojo.class, mojo, "branch", branch);
        return mojo;
    }

    @Test
    void reportsSignificantAndCountsTechnicalAgainstHead() throws Exception {
        write("flows/a.json", COMMITTED);
        commitAll();
        write("flows/a.json", WORKING);
        File output = dir.resolve("report.txt").toFile();

        mojo("flows", "HEAD", output).execute();

        String report = Files.readString(output.toPath(), StandardCharsets.UTF_8);
        assertTrue(report.contains("properties/k: v -> v2"), report);
        assertTrue(report.contains("technical: 3"), report);
        assertFalse(report.contains("instanceIdentifier"), report);
    }

    @Test
    void unresolvableBranchFails() throws Exception {
        write("flows/a.json", COMMITTED);
        commitAll();
        GitDiffMojo mojo = mojo("flows", "no-such-branch", dir.resolve("report.txt").toFile());
        MojoFailureException ex = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("no-such-branch"), ex.getMessage());
    }
}

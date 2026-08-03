package org.qubership.nifi.maven.flowdiff.mojo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link GitRevertTechnicalMojo}: rewriting technical fields to committed values while leaving significant
 * changes untouched, the per-file summary breakdown, the clean no-op summary, and the deleted-single-file warning.
 */
@ExtendWith(MockitoExtension.class)
class GitRevertTechnicalMojoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COMMITTED = """
            {"flowContents":{"identifier":"root-committed","name":"R","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"p1-c",
               "groupIdentifier":"root-committed","properties":{"k":"v"}}]}}""";
    private static final String WORKING = """
            {"flowContents":{"identifier":"root-working","name":"R","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"p1-w",
               "groupIdentifier":"root-working","properties":{"k":"v2"}}]}}""";

    @Mock
    private Log log;

    @TempDir
    private Path dir;

    private void commit(final String relative, final String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        try (Git git = openOrInit()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("commit").setAuthor("t", "t@e").setSign(false).call();
        }
    }

    private Git openOrInit() throws Exception {
        if (dir.resolve(".git").toFile().exists()) {
            return Git.open(dir.toFile());
        }
        return Git.init().setDirectory(dir.toFile()).call();
    }

    private void setField(final Class<?> clazz, final Object target, final String name, final Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private GitRevertTechnicalMojo mojo(final String path) throws Exception {
        GitRevertTechnicalMojo mojo = new GitRevertTechnicalMojo();
        mojo.setLog(log);
        setField(AbstractFlowDiffMojo.class, mojo, "basedir", dir.toFile());
        setField(AbstractFlowDiffMojo.class, mojo, "format", "text");
        setField(AbstractFlowDiffMojo.class, mojo, "maxValueLength", 200);
        setField(AbstractFlowDiffMojo.class, mojo, "skipMalformed", false);
        setField(GitRevertTechnicalMojo.class, mojo, "path", path);
        return mojo;
    }

    private String runCapturingStdout(final GitRevertTechnicalMojo mojo) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            mojo.execute();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void rewritesTechnicalFieldsAndPrintsBreakdown() throws Exception {
        commit("flows/a.json", COMMITTED);
        Files.writeString(dir.resolve("flows/a.json"), WORKING, StandardCharsets.UTF_8);

        String out = runCapturingStdout(mojo("flows/a.json"));

        JsonNode root = MAPPER.readTree(dir.resolve("flows/a.json").toFile()).get("flowContents");
        assertEquals("root-committed", root.get("identifier").asText());
        JsonNode processor = root.get("processors").get(0);
        assertEquals("p1-c", processor.get("instanceIdentifier").asText());
        assertEquals("root-committed", processor.get("groupIdentifier").asText());
        assertEquals("v2", processor.get("properties").get("k").asText());
        assertTrue(out.contains(
                "flows/a.json: 3 reverted (instanceIdentifier=1, rootIdentifier=1, groupIdentifier=1, "
                        + "endpointGroupId=0)"), out);
        assertTrue(out.contains("Total: 1 files rewritten, 3 technical changes reverted."), out);
    }

    @Test
    void cleanWorkingTreePrintsZeroRewritten() throws Exception {
        commit("flows/a.json", COMMITTED);
        String out = runCapturingStdout(mojo("flows/a.json"));
        assertTrue(out.contains("Total: 0 files rewritten"), out);
    }

    @Test
    void absolutePathIsRejected() throws Exception {
        commit("flows/a.json", COMMITTED);
        String absolute = dir.resolve("flows/a.json").toString();
        org.apache.maven.plugin.MojoFailureException ex = org.junit.jupiter.api.Assertions.assertThrows(
                org.apache.maven.plugin.MojoFailureException.class, () -> mojo(absolute).execute());
        assertTrue(ex.getMessage().contains("must be relative"), ex.getMessage());
    }

    @Test
    void flowVsNonFlowMismatchWarnsAndRewritesNothing() throws Exception {
        commit("flows/a.json", COMMITTED);
        Files.writeString(dir.resolve("flows/a.json"), "{\"notAFlow\":true}", StandardCharsets.UTF_8);

        String out = runCapturingStdout(mojo("flows/a.json"));

        verify(log).warn(contains("non-flow JSON on the target side"));
        assertTrue(out.contains("Total: 0 files rewritten"), out);
    }

    @Test
    void deletedSingleFileWarnsAndRewritesNothing() throws Exception {
        commit("flows/gone.json", COMMITTED);
        Files.delete(dir.resolve("flows/gone.json"));

        String out = runCapturingStdout(mojo("flows/gone.json"));

        verify(log).warn(contains("gone.json"));
        assertTrue(out.contains("Total: 0 files rewritten"), out);
    }
}

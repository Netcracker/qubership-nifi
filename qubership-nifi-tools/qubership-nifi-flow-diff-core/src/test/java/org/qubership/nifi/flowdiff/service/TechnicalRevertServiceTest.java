package org.qubership.nifi.flowdiff.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.flowdiff.LogCapture;
import org.qubership.nifi.flowdiff.flow.FlowParseException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TechnicalRevertService}: rewriting technical fields to their committed values while leaving
 * significant changes untouched, the per-file summary breakdown, the clean no-op summary, and the warnings for a
 * deleted file and for a flow paired against a non-flow JSON.
 */
class TechnicalRevertServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
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

    private final TechnicalRevertService service = new TechnicalRevertService();

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

    @Test
    void rewritesTechnicalFieldsAndSummarizesTheBreakdown() throws Exception {
        commit("flows/a.json", COMMITTED);
        Files.writeString(dir.resolve("flows/a.json"), WORKING, StandardCharsets.UTF_8);

        RevertSummary summary = service.revertGit(dir.toFile(), "flows/a.json", false);

        JsonNode root = MAPPER.readTree(dir.resolve("flows/a.json").toFile()).get("flowContents");
        assertEquals("root-committed", root.get("identifier").asText());
        JsonNode processor = root.get("processors").get(0);
        assertEquals("p1-c", processor.get("instanceIdentifier").asText());
        assertEquals("root-committed", processor.get("groupIdentifier").asText());
        assertEquals("v2", processor.get("properties").get("k").asText());

        assertEquals(1, summary.filesWritten());
        assertEquals(3, summary.totalReverted());
        assertEquals(List.of("flows/a.json: 3 reverted (instanceIdentifier=1, rootIdentifier=1, groupIdentifier=1, "
                + "endpointGroupId=0)"), summary.summaryLines());
        assertEquals("Total: 1 files rewritten, 3 technical changes reverted.", summary.totalLine());
    }

    @Test
    void cleanWorkingTreeRewritesNothing() throws Exception {
        commit("flows/a.json", COMMITTED);
        RevertSummary summary = service.revertGit(dir.toFile(), "flows/a.json", false);
        assertEquals(0, summary.filesWritten());
        assertTrue(summary.summaryLines().isEmpty());
        assertEquals("Total: 0 files rewritten", summary.totalLine());
    }

    @Test
    void absolutePathIsRejected() throws Exception {
        commit("flows/a.json", COMMITTED);
        String absolute = dir.resolve("flows/a.json").toString();
        FlowParseException ex = assertThrows(FlowParseException.class,
                () -> service.revertGit(dir.toFile(), absolute, false));
        assertTrue(ex.getMessage().contains("must be relative"), ex.getMessage());
    }

    @Test
    void flowVersusNonFlowWarnsAndRewritesNothing() throws Exception {
        commit("flows/a.json", COMMITTED);
        Files.writeString(dir.resolve("flows/a.json"), "{\"notAFlow\":true}", StandardCharsets.UTF_8);

        try (LogCapture logs = LogCapture.on(TechnicalRevertService.class)) {
            RevertSummary summary = service.revertGit(dir.toFile(), "flows/a.json", false);
            assertTrue(logs.warnedAbout("non-flow JSON on the target side"));
            assertEquals("Total: 0 files rewritten", summary.totalLine());
        }
    }

    @Test
    void deletedSingleFileWarnsAndRewritesNothing() throws Exception {
        commit("flows/gone.json", COMMITTED);
        Files.delete(dir.resolve("flows/gone.json"));

        try (LogCapture logs = LogCapture.on(TechnicalRevertService.class)) {
            RevertSummary summary = service.revertGit(dir.toFile(), "flows/gone.json", false);
            assertTrue(logs.warnedAbout("gone.json"));
            assertEquals("Total: 0 files rewritten", summary.totalLine());
        }
    }
}

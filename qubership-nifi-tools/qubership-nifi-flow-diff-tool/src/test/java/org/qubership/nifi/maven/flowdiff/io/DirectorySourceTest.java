package org.qubership.nifi.maven.flowdiff.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.nifi.maven.flowdiff.flow.FlowParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link DirectorySource}: flow detection, non-flow classification, malformed-JSON handling with and without
 * {@code skip-malformed}, and the unknown-top-level-sibling failure. Discovery enumerates candidates without reading
 * them, so classification (and any failure) surfaces when a candidate is loaded.
 */
@ExtendWith(MockitoExtension.class)
class DirectorySourceTest {

    private static final String FLOW = """
            {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP"}}""";

    @Mock
    private Log log;

    @TempDir
    private Path dir;

    private void write(final String name, final String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private DirectorySource source(final boolean skipMalformed) {
        return new DirectorySource(dir.toFile(), new FlowClassifier(skipMalformed, new ObjectMapper(), log));
    }

    @Test
    void detectsFlowAndSkipsNonFlow() throws IOException {
        write("flow.json", FLOW);
        write("params.json", "{\"some\":\"value\"}");
        Map<String, Candidate> candidates = source(false).discover();
        assertEquals(2, candidates.size());
        assertTrue(candidates.get("flow.json").load().isFlow());
        assertEquals(CandidateKind.NON_FLOW, candidates.get("params.json").load().getKind());
    }

    @Test
    void malformedJsonFailsWithoutSkip() throws IOException {
        write("broken.json", "{ not valid json ");
        Candidate broken = source(false).discover().get("broken.json");
        FlowParseException ex = assertThrows(FlowParseException.class, broken::load);
        assertTrue(ex.getMessage().contains("broken.json"));
    }

    @Test
    void malformedJsonWarnsAndSkipsWithSkip() throws IOException {
        write("broken.json", "{ not valid json ");
        write("flow.json", FLOW);
        Map<String, Candidate> candidates = source(true).discover();
        assertEquals(2, candidates.size());
        assertTrue(candidates.get("flow.json").load().isFlow());
        assertNull(candidates.get("broken.json").load());
        verify(log).warn(org.mockito.ArgumentMatchers.contains("broken.json"));
    }

    @Test
    void unknownTopLevelSiblingFails() throws IOException {
        write("flow.json", """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP"},
                 "unexpectedSection":{}}""");
        Candidate flow = source(false).discover().get("flow.json");
        FlowParseException ex = assertThrows(FlowParseException.class, flow::load);
        assertTrue(ex.getMessage().contains("unexpectedSection"));
    }
}

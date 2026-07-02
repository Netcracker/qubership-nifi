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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link DirectorySource}: flow detection, non-flow classification, malformed-JSON handling with and without
 * {@code skip-malformed}, and the unknown-top-level-sibling failure.
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
        Map<String, SideEntry> entries = source(false).read();
        assertEquals(2, entries.size());
        assertTrue(entries.get("flow.json").isFlow());
        assertEquals(CandidateKind.NON_FLOW, entries.get("params.json").getKind());
    }

    @Test
    void malformedJsonFailsWithoutSkip() throws IOException {
        write("broken.json", "{ not valid json ");
        DirectorySource source = source(false);
        FlowParseException ex = assertThrows(FlowParseException.class, source::read);
        assertTrue(ex.getMessage().contains("broken.json"));
    }

    @Test
    void malformedJsonWarnsAndContinuesWithSkip() throws IOException {
        write("broken.json", "{ not valid json ");
        write("flow.json", FLOW);
        Map<String, SideEntry> entries = source(true).read();
        assertEquals(1, entries.size());
        assertTrue(entries.containsKey("flow.json"));
        verify(log).warn(org.mockito.ArgumentMatchers.contains("broken.json"));
    }

    @Test
    void unknownTopLevelSiblingFails() throws IOException {
        write("flow.json", """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP"},
                 "unexpectedSection":{}}""");
        DirectorySource source = source(false);
        FlowParseException ex = assertThrows(FlowParseException.class, source::read);
        assertTrue(ex.getMessage().contains("unexpectedSection"));
    }
}

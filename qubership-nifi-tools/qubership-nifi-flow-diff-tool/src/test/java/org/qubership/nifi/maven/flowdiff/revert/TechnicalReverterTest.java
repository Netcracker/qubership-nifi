package org.qubership.nifi.maven.flowdiff.revert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link TechnicalReverter}: restoring {@code instanceIdentifier}, the root {@code identifier}, and direct
 * child {@code groupIdentifier} back-references and connection-endpoint identifiers to committed values, while leaving
 * a property value that merely equals an old identifier untouched.
 */
class TechnicalReverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowExport flow(final String json) {
        try {
            return FlowExport.of("flow.json", MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode firstProcessor(final FlowExport flow) {
        return flow.getFlowContents().get("processors").get(0);
    }

    @Test
    void revertsInstanceRootAndGroupIdentifiers() {
        FlowExport committed = flow("""
                {"flowContents":{"identifier":"root-c","name":"R","componentType":"PROCESS_GROUP",
                 "instanceIdentifier":"root-inst-c","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR",
                   "instanceIdentifier":"p1-inst-c","groupIdentifier":"root-c"}]}}""");
        FlowExport working = flow("""
                {"flowContents":{"identifier":"root-w","name":"R","componentType":"PROCESS_GROUP",
                 "instanceIdentifier":"root-inst-w","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR",
                   "instanceIdentifier":"p1-inst-w","groupIdentifier":"root-w"}]}}""");

        RevertCounts counts = new TechnicalReverter().revert(committed, working);

        assertEquals(2, counts.instanceIdentifier());
        assertEquals(1, counts.rootIdentifier());
        assertEquals(1, counts.groupIdentifier());
        assertEquals("root-c", working.getFlowContents().get("identifier").asText());
        assertEquals("root-inst-c", working.getFlowContents().get("instanceIdentifier").asText());
        assertEquals("p1-inst-c", firstProcessor(working).get("instanceIdentifier").asText());
        assertEquals("root-c", firstProcessor(working).get("groupIdentifier").asText());
    }

    @Test
    void preservesPropertyValueEqualToOldInstanceIdentifier() {
        FlowExport committed = flow("""
                {"flowContents":{"identifier":"root","name":"R","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"inst-c",
                   "properties":{"note":"inst-w"}}]}}""");
        FlowExport working = flow("""
                {"flowContents":{"identifier":"root","name":"R","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"inst-w",
                   "properties":{"note":"inst-w"}}]}}""");

        new TechnicalReverter().revert(committed, working);

        assertEquals("inst-c", firstProcessor(working).get("instanceIdentifier").asText());
        assertEquals("inst-w", firstProcessor(working).get("properties").get("note").asText());
    }

    @Test
    void revertsConnectionEndpointInstanceIdentifier() {
        FlowExport committed = flow("""
                {"flowContents":{"identifier":"root","name":"R","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"inst-c"}],
                 "connections":[{"identifier":"c1","name":"","componentType":"CONNECTION",
                  "source":{"id":"p1","type":"PROCESSOR","name":"A","instanceIdentifier":"inst-c"},
                  "destination":{"id":"p1","type":"PROCESSOR","name":"A","instanceIdentifier":"inst-c"}}]}}""");
        FlowExport working = flow("""
                {"flowContents":{"identifier":"root","name":"R","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"inst-w"}],
                 "connections":[{"identifier":"c1","name":"","componentType":"CONNECTION",
                  "source":{"id":"p1","type":"PROCESSOR","name":"A","instanceIdentifier":"inst-w"},
                  "destination":{"id":"p1","type":"PROCESSOR","name":"A","instanceIdentifier":"inst-w"}}]}}""");

        RevertCounts counts = new TechnicalReverter().revert(committed, working);

        JsonNode connection = working.getFlowContents().get("connections").get(0);
        assertEquals("inst-c", connection.get("source").get("instanceIdentifier").asText());
        assertEquals("inst-c", connection.get("destination").get("instanceIdentifier").asText());
        assertEquals(3, counts.instanceIdentifier());
    }

    @Test
    void revertsRemotePortAndEndpointReferencingIt() {
        String template = """
                {"flowContents":{"identifier":"root","name":"R","componentType":"PROCESS_GROUP",
                 "remoteProcessGroups":[{"identifier":"rpg1","name":"RPG","componentType":"REMOTE_PROCESS_GROUP",
                  "inputPorts":[{"identifier":"rip1","name":"In","componentType":"REMOTE_INPUT_PORT",
                   "instanceIdentifier":"%1$s"}]}],
                 "connections":[{"identifier":"c1","name":"","componentType":"CONNECTION",
                  "source":{"id":"rip1","type":"REMOTE_INPUT_PORT","name":"In","instanceIdentifier":"%1$s"},
                  "destination":{"id":"rip1","type":"REMOTE_INPUT_PORT","name":"In","instanceIdentifier":"%1$s"}}]}}""";
        FlowExport committed = flow(template.formatted("port-c"));
        FlowExport working = flow(template.formatted("port-w"));

        RevertCounts counts = new TechnicalReverter().revert(committed, working);

        JsonNode port = working.getFlowContents().get("remoteProcessGroups").get(0).get("inputPorts").get(0);
        assertEquals("port-c", port.get("instanceIdentifier").asText());
        JsonNode connection = working.getFlowContents().get("connections").get(0);
        assertEquals("port-c", connection.get("source").get("instanceIdentifier").asText());
        assertEquals("port-c", connection.get("destination").get("instanceIdentifier").asText());
        assertEquals(3, counts.instanceIdentifier());
    }
}

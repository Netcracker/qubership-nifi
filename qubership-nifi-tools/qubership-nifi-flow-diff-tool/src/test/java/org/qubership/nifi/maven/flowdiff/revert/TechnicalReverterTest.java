package org.qubership.nifi.maven.flowdiff.revert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.maven.flowdiff.compare.ChangeCategory;
import org.qubership.nifi.maven.flowdiff.compare.Difference;
import org.qubership.nifi.maven.flowdiff.compare.FlowComparator;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TechnicalReverter}: restoring {@code instanceIdentifier}, the root {@code identifier}, direct
 * child {@code groupIdentifier} back-references, connection-endpoint identifiers, and connection-endpoint
 * {@code groupId} root back-references to committed values, while leaving a property value that merely equals an old
 * identifier - and a sub-group endpoint {@code groupId} - untouched.
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
    void revertsEndpointGroupIdRootBackReferenceButLeavesSubGroupReference() {
        String template = """
                {"flowContents":{"identifier":"%1$s","name":"R","componentType":"PROCESS_GROUP","processGroups":[
                  {"identifier":"g1","name":"Child","componentType":"PROCESS_GROUP","inputPorts":[
                    {"identifier":"in","name":"in","componentType":"INPUT_PORT"}]}],
                 "connections":[{"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"%1$s",
                  "source":{"id":"p1","type":"PROCESSOR","name":"A","groupId":"%1$s"},
                  "destination":{"id":"in","type":"INPUT_PORT","name":"in","groupId":"g1"}}]}}""";
        FlowExport committed = flow(template.formatted("root-c"));
        FlowExport working = flow(template.formatted("root-w"));

        RevertCounts counts = new TechnicalReverter().revert(committed, working);

        JsonNode connection = working.getFlowContents().get("connections").get(0);
        assertEquals("root-c", connection.get("source").get("groupId").asText());
        assertEquals("g1", connection.get("destination").get("groupId").asText());
        assertEquals(1, counts.endpointGroupId());
    }

    @Test
    void leavesEndpointInstanceIdentifierWhenEndpointIdChanged() {
        FlowExport committed = flow("""
                {"flowContents":{"identifier":"root","name":"R","componentType":"PROCESS_GROUP",
                 "connections":[{"identifier":"c1","name":"","componentType":"CONNECTION",
                  "source":{"id":"p1","type":"PROCESSOR","name":"A","instanceIdentifier":"src-c"},
                  "destination":{"id":"d1","type":"PROCESSOR","name":"B","instanceIdentifier":"dst-c"}}]}}""");
        FlowExport working = flow("""
                {"flowContents":{"identifier":"root","name":"R","componentType":"PROCESS_GROUP",
                 "connections":[{"identifier":"c1","name":"","componentType":"CONNECTION",
                  "source":{"id":"p2","type":"PROCESSOR","name":"C","instanceIdentifier":"src-w"},
                  "destination":{"id":"d1","type":"PROCESSOR","name":"B","instanceIdentifier":"dst-w"}}]}}""");

        RevertCounts counts = new TechnicalReverter().revert(committed, working);

        JsonNode connection = working.getFlowContents().get("connections").get(0);
        // The source id changed (p1 -> p2), so its instanceIdentifier is a significant change and is left in place.
        assertEquals("src-w", connection.get("source").get("instanceIdentifier").asText());
        // The destination id is unchanged (d1), so its instanceIdentifier is reverted.
        assertEquals("dst-c", connection.get("destination").get("instanceIdentifier").asText());
        assertEquals(1, counts.instanceIdentifier());
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

    /**
     * Flow shape covering every reverted technical location - root {@code identifier} and
     * {@code instanceIdentifier}, a child processor's {@code instanceIdentifier} and {@code groupIdentifier}
     * back-reference, a direct-root-child connection's {@code groupIdentifier}, and both endpoints'
     * {@code instanceIdentifier} and root-referencing {@code groupId}. The three placeholders are the only technical
     * values, so formatting it with different arguments yields two flows that differ solely by technical churn.
     */
    private static final String CHURN_TEMPLATE = """
            {"flowContents":{"identifier":"%1$s","name":"R","componentType":"PROCESS_GROUP","instanceIdentifier":"%2$s",
             "processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"%3$s",
               "groupIdentifier":"%1$s"}],
             "connections":[{"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"%1$s",
              "source":{"id":"p1","type":"PROCESSOR","name":"A","instanceIdentifier":"%3$s","groupId":"%1$s"},
              "destination":{"id":"p1","type":"PROCESSOR","name":"A","instanceIdentifier":"%3$s",
               "groupId":"%1$s"}}]}}""";

    @Test
    void roundTripRestoresWorkingToCommitted() {
        FlowExport committed = flow(CHURN_TEMPLATE.formatted("root-c", "root-inst-c", "p1-inst-c"));
        FlowExport working = flow(CHURN_TEMPLATE.formatted("root-w", "root-inst-w", "p1-inst-w"));

        RevertCounts counts = new TechnicalReverter().revert(committed, working);

        assertTrue(counts.total() > 0, "expected the churn fixture to exercise the revert");
        // Round-trip: a pure-technical-churn revert diffs to nothing against the committed flow.
        assertTrue(new FlowComparator().compare(committed, working).isEmpty(),
                () -> "expected no differences after reverting pure technical churn");
        // Whole file: the working tree is now structurally identical to committed, so nothing else moved.
        assertEquals(committed.getRoot(), working.getRoot());
    }

    @Test
    void revertChangesOnlyTechnicalFields() {
        String template = """
                {"flowContents":{"identifier":"%1$s","name":"R","componentType":"PROCESS_GROUP",
                 "instanceIdentifier":"%2$s","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"%3$s",
                   "groupIdentifier":"%1$s","properties":{"Batch Size":"%4$s"}}]}}""";
        FlowExport committed = flow(template.formatted("root-c", "root-inst-c", "p1-inst-c", "1000"));
        FlowExport working = flow(template.formatted("root-w", "root-inst-w", "p1-inst-w", "5000"));
        // committed plus only the significant change: what the working flow must look like after the revert.
        FlowExport expected = flow(template.formatted("root-c", "root-inst-c", "p1-inst-c", "5000"));

        new TechnicalReverter().revert(committed, working);

        // Whole file: every technical field reverted, the significant property value kept, nothing else touched.
        assertEquals(expected.getRoot(), working.getRoot());
        // Cross-check: the only surviving difference against committed is the significant property change.
        List<Difference> diffs = new FlowComparator().compare(committed, working);
        assertEquals(1, diffs.size(), () -> "expected a single surviving difference but got " + diffs);
        assertEquals(ChangeCategory.SIGNIFICANT, diffs.get(0).getCategory());
        assertEquals("properties/Batch Size", diffs.get(0).getFieldPath());
    }
}

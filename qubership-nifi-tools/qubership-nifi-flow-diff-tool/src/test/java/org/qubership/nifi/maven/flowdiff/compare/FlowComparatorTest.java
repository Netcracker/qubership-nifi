package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.maven.flowdiff.flow.ComponentType;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;
import org.qubership.nifi.maven.flowdiff.flow.FlowParseException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FlowComparator}: identity-based matching, category classification, added and removed components,
 * canonical path building, and the identifier-uniqueness guard.
 */
class FlowComparatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final FlowComparator comparator = new FlowComparator();

    private FlowExport flow(final String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            return FlowExport.of("test.json", root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(final List<Difference> diffs, final ChangeCategory category) {
        return diffs.stream().filter(d -> d.getCategory() == category).count();
    }

    private Difference only(final List<Difference> diffs) {
        assertEquals(1, diffs.size(), () -> "expected a single difference but got " + diffs.size());
        return diffs.get(0);
    }

    private Difference find(final List<Difference> diffs, final String fieldPath) {
        return diffs.stream().filter(d -> fieldPath.equals(d.getFieldPath())).findFirst()
                .orElseThrow(() -> new IllegalStateException("no difference at field path " + fieldPath));
    }

    @Test
    void arrayReorderingProducesNoChanges() {
        FlowExport baseline = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"i1"},
                  {"identifier":"p2","name":"B","componentType":"PROCESSOR","instanceIdentifier":"i2"}]}}""");
        FlowExport target = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p2","name":"B","componentType":"PROCESSOR","instanceIdentifier":"i2"},
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"i1"}]}}""");
        assertTrue(comparator.compare(baseline, target).isEmpty());
    }

    @Test
    void selectedRelationshipsReorderingProducesNoChanges() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"root",
                   "selectedRelationships":%s,
                   "source":{"id":"p1","type":"PROCESSOR","name":"A"},
                   "destination":{"id":"p2","type":"PROCESSOR","name":"B"}}]}}""";
        FlowExport baseline = flow(template.formatted("[\"success\",\"success2\",\"success3\"]"));
        FlowExport target = flow(template.formatted("[\"success2\",\"success\",\"success3\"]"));
        assertTrue(comparator.compare(baseline, target).isEmpty());
    }

    @Test
    void autoTerminatedAndRetriedRelationshipsReorderingProducesNoChanges() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR",
                   "autoTerminatedRelationships":%1$s,"retriedRelationships":%1$s}]}}""";
        FlowExport baseline = flow(template.formatted("[\"success\",\"failure\"]"));
        FlowExport target = flow(template.formatted("[\"failure\",\"success\"]"));
        assertTrue(comparator.compare(baseline, target).isEmpty());
    }

    @Test
    void relationshipValueChangeStillSignificant() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"root",
                   "selectedRelationships":%s,
                   "source":{"id":"p1","type":"PROCESSOR","name":"A"},
                   "destination":{"id":"p2","type":"PROCESSOR","name":"B"}}]}}""";
        FlowExport baseline = flow(template.formatted("[\"success\",\"failure\"]"));
        FlowExport target = flow(template.formatted("[\"success\"]"));
        List<Difference> diffs = comparator.compare(baseline, target);
        assertEquals(1, diffs.size());
        assertEquals(ChangeCategory.SIGNIFICANT, only(diffs).getCategory());
        assertEquals("selectedRelationships", only(diffs).getFieldPath());
    }

    @Test
    void instanceIdentifierClassifiedTechnical() {
        FlowExport baseline = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"old"}]}}""");
        FlowExport target = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"new"}]}}""");
        List<Difference> diffs = comparator.compare(baseline, target);
        assertEquals(1, count(diffs, ChangeCategory.TECHNICAL));
        assertEquals(0, count(diffs, ChangeCategory.SIGNIFICANT));
    }

    @Test
    void rootIdentifierAndDirectChildGroupIdentifierTechnical() {
        FlowExport baseline = flow("""
                {"flowContents":{"identifier":"oldroot","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR","groupIdentifier":"oldroot"}]}}""");
        FlowExport target = flow("""
                {"flowContents":{"identifier":"newroot","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"A","componentType":"PROCESSOR","groupIdentifier":"newroot"}]}}""");
        List<Difference> diffs = comparator.compare(baseline, target);
        assertEquals(2, count(diffs, ChangeCategory.TECHNICAL));
        assertEquals(0, count(diffs, ChangeCategory.SIGNIFICANT));
    }

    @Test
    void nestedGroupIdentifierChangeIsSignificant() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processGroups":[
                  {"identifier":"g1","name":"Nested","componentType":"PROCESS_GROUP","processors":[
                    {"identifier":"p1","name":"A","componentType":"PROCESSOR","groupIdentifier":"%s"}]}]}}""";
        List<Difference> diffs = comparator.compare(
                flow(template.formatted("g1")), flow(template.formatted("g1-changed")));
        Difference diff = only(diffs);
        assertEquals(ChangeCategory.SIGNIFICANT, diff.getCategory());
        assertEquals("groupIdentifier", diff.getFieldPath());
    }

    @Test
    void connectionEndpointGroupIdToRootIsTechnical() {
        String template = """
                {"flowContents":{"identifier":"%1$s","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"%1$s",
                   "source":{"id":"p1","type":"PROCESSOR","name":"A","groupId":"%1$s"},
                   "destination":{"id":"p2","type":"PROCESSOR","name":"B","groupId":"%1$s"}}]}}""";
        List<Difference> diffs = comparator.compare(
                flow(template.formatted("oldroot")), flow(template.formatted("newroot")));
        assertEquals(0, count(diffs, ChangeCategory.SIGNIFICANT));
        assertEquals(ChangeCategory.TECHNICAL, find(diffs, "source/groupId").getCategory());
        assertEquals(ChangeCategory.TECHNICAL, find(diffs, "destination/groupId").getCategory());
    }

    @Test
    void connectionEndpointGroupIdToSubGroupIsSignificant() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"root",
                   "source":{"id":"p1","type":"PROCESSOR","name":"A","groupId":"root"},
                   "destination":{"id":"in","type":"INPUT_PORT","name":"in","groupId":"%s"}}]}}""";
        Difference diff = only(comparator.compare(flow(template.formatted("g1")), flow(template.formatted("g2"))));
        assertEquals(ChangeCategory.SIGNIFICANT, diff.getCategory());
        assertEquals("destination/groupId", diff.getFieldPath());
    }

    @Test
    void connectionEndpointGroupIdMixedRootAndSubGroupClassifiedIndependently() {
        // A connection running from a root-level component into a child process group's input port: the source
        // groupId is a root back-reference (technical) while the destination groupId is the child group id.
        String template = """
                {"flowContents":{"identifier":"%1$s","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"%1$s",
                   "source":{"id":"p1","type":"PROCESSOR","name":"A","groupId":"%1$s"},
                   "destination":{"id":"in","type":"INPUT_PORT","name":"in","groupId":"%2$s"}}]}}""";
        List<Difference> diffs = comparator.compare(
                flow(template.formatted("oldroot", "child")),
                flow(template.formatted("newroot", "child-changed")));
        assertEquals(ChangeCategory.TECHNICAL, find(diffs, "source/groupId").getCategory());
        assertEquals(ChangeCategory.SIGNIFICANT, find(diffs, "destination/groupId").getCategory());
    }

    @Test
    void addedComponentIsSingleSignificant() {
        FlowExport baseline = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                  "processors":[]}}""");
        FlowExport target = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p9","name":"New","componentType":"PROCESSOR"}]}}""");
        Difference diff = only(comparator.compare(baseline, target));
        assertEquals("added", diff.getChange());
        assertEquals(ChangeCategory.SIGNIFICANT, diff.getCategory());
        assertEquals("Root/New", diff.getPath());
        assertEquals(ComponentType.PROCESSOR, diff.getComponentType());
    }

    @Test
    void removedComponentIsSingleSignificant() {
        FlowExport baseline = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p9","name":"Gone","componentType":"PROCESSOR"}]}}""");
        FlowExport target = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                  "processors":[]}}""");
        Difference diff = only(comparator.compare(baseline, target));
        assertEquals("removed", diff.getChange());
        assertEquals("Gone", diff.getName());
    }

    @Test
    void duplicateIdentifierFails() {
        FlowExport flow = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"dup","name":"A","componentType":"PROCESSOR"},
                  {"identifier":"dup","name":"B","componentType":"PROCESSOR"}]}}""");
        FlowParseException ex = assertThrows(FlowParseException.class, () -> comparator.compare(flow, flow));
        assertTrue(ex.getMessage().contains("dup"));
    }

    @Test
    void missingIdentifierFails() {
        FlowExport flow = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"name":"A","componentType":"PROCESSOR"}]}}""");
        FlowParseException ex = assertThrows(FlowParseException.class, () -> comparator.compare(flow, flow));
        assertTrue(ex.getMessage().contains("PROCESSOR"));
    }

    @Test
    void propertyChangeIsSignificantWithCanonicalPath() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR","properties":{"Batch Size":"%s"}}]}}""";
        Difference diff = only(comparator.compare(flow(template.formatted("1000")), flow(template.formatted("5000"))));
        assertEquals("Root/Load/properties/Batch Size", diff.getPath());
        assertEquals(List.of("Root", "Load", "properties", "Batch Size"), diff.getPathSegments());
        assertEquals("1000", diff.getBaselineValue().asText());
        assertEquals("5000", diff.getTargetValue().asText());
    }

    @Test
    void duplicateNameAppendsShortIdentifierInPath() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"7c8d9e0f-1111","name":"Route","componentType":"PROCESSOR","properties":{"s":"%s"}},
                  {"identifier":"aa","name":"Route","componentType":"PROCESSOR"}]}}""";
        Difference diff = only(comparator.compare(flow(template.formatted("x")), flow(template.formatted("y"))));
        assertTrue(diff.getPath().startsWith("Root/Route(7c8d9e0f-1111)/"), diff.getPath());
    }

    @Test
    void changeOrderFollowsDeclaredCollectionOrder() {
        // The collections are declared here out of order (ports, services, processors) to prove the change order
        // follows the fixed CHILD_COLLECTIONS sequence (processors, controllerServices, inputPorts), not JSON order.
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                  "inputPorts":[
                    {"identifier":"ip1","name":"In","componentType":"INPUT_PORT","comments":"%1$s"}],
                  "controllerServices":[
                    {"identifier":"cs1","name":"CS","componentType":"CONTROLLER_SERVICE","comments":"%1$s"}],
                  "processors":[
                    {"identifier":"p1","name":"P","componentType":"PROCESSOR","comments":"%1$s"}]}}""";
        List<Difference> diffs = comparator.compare(flow(template.formatted("a")), flow(template.formatted("b")));
        List<ComponentType> order = diffs.stream().map(Difference::getComponentType).toList();
        assertEquals(
                List.of(ComponentType.PROCESSOR, ComponentType.CONTROLLER_SERVICE, ComponentType.INPUT_PORT),
                order);
    }

    @Test
    void remotePortSignificantFieldChangeIsReported() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                  "remoteProcessGroups":[
                    {"identifier":"rpg1","name":"RPG","componentType":"REMOTE_PROCESS_GROUP",
                     "instanceIdentifier":"ri1","inputPorts":[
                       {"identifier":"rip1","name":"In","componentType":"REMOTE_INPUT_PORT",
                        "instanceIdentifier":"ii1","concurrentlySchedulableTaskCount":%s}]}]}}""";
        Difference diff = only(comparator.compare(flow(template.formatted("1")), flow(template.formatted("2"))));
        assertEquals(ChangeCategory.SIGNIFICANT, diff.getCategory());
        assertEquals(ComponentType.REMOTE_INPUT_PORT, diff.getComponentType());
        assertEquals("Root/In/concurrentlySchedulableTaskCount", diff.getPath());
    }

    @Test
    void remotePortInstanceIdentifierIsTechnical() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                  "remoteProcessGroups":[
                    {"identifier":"rpg1","name":"RPG","componentType":"REMOTE_PROCESS_GROUP",
                     "instanceIdentifier":"ri1","outputPorts":[
                       {"identifier":"rop1","name":"Out","componentType":"REMOTE_OUTPUT_PORT",
                        "instanceIdentifier":"%s"}]}]}}""";
        List<Difference> diffs = comparator.compare(flow(template.formatted("old")), flow(template.formatted("new")));
        assertEquals(1, count(diffs, ChangeCategory.TECHNICAL));
        assertEquals(0, count(diffs, ChangeCategory.SIGNIFICANT));
    }

    @Test
    void remotePortReorderingProducesNoChanges() {
        String portA =
                "{\"identifier\":\"rip1\",\"name\":\"A\",\"componentType\":\"REMOTE_INPUT_PORT\","
                + "\"instanceIdentifier\":\"i1\"}";
        String portB =
                "{\"identifier\":\"rip2\",\"name\":\"B\",\"componentType\":\"REMOTE_INPUT_PORT\","
                + "\"instanceIdentifier\":\"i2\"}";
        String template = "{\"flowContents\":{\"identifier\":\"root\",\"name\":\"Root\","
                + "\"componentType\":\"PROCESS_GROUP\",\"remoteProcessGroups\":[{\"identifier\":\"rpg1\","
                + "\"name\":\"RPG\",\"componentType\":\"REMOTE_PROCESS_GROUP\",\"inputPorts\":[%s,%s]}]}}";
        FlowExport baseline = flow(template.formatted(portA, portB));
        FlowExport target = flow(template.formatted(portB, portA));
        assertTrue(comparator.compare(baseline, target).isEmpty());
    }

    @Test
    void nameChangeCarriesBaselineAndTargetNames() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"%s","componentType":"PROCESSOR"}]}}""";
        Difference diff = only(comparator.compare(flow(template.formatted("Old")), flow(template.formatted("New"))));
        assertEquals("Old", diff.getNameBaseline());
        assertEquals("New", diff.getNameTarget());
    }

    @Test
    void connectionEndpointInstanceIdentifierWithUnchangedIdIsTechnical() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"root",
                   "source":{"id":"p1","type":"PROCESSOR","name":"A","instanceIdentifier":"%s"},
                   "destination":{"id":"p2","type":"PROCESSOR","name":"B"}}]}}""";
        List<Difference> diffs = comparator.compare(flow(template.formatted("old")), flow(template.formatted("new")));
        assertEquals(0, count(diffs, ChangeCategory.SIGNIFICANT));
        assertEquals(ChangeCategory.TECHNICAL, find(diffs, "source/instanceIdentifier").getCategory());
    }

    @Test
    void connectionEndpointIdChangeMakesEndpointFieldsSignificantAndCarriesSnapshot() {
        FlowExport baseline = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"root",
                   "source":{"id":"p1","type":"PROCESSOR","name":"A"},
                   "destination":{"id":"out","type":"OUTPUT_PORT","name":"out_success",
                    "instanceIdentifier":"i-old"}}]}}""");
        FlowExport target = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"root",
                   "source":{"id":"p1","type":"PROCESSOR","name":"A"},
                   "destination":{"id":"fn","type":"FUNNEL","name":"Funnel","instanceIdentifier":"i-new"}}]}}""");
        List<Difference> diffs = comparator.compare(baseline, target);
        assertEquals(0, count(diffs, ChangeCategory.TECHNICAL));
        assertEquals(ChangeCategory.SIGNIFICANT, find(diffs, "destination/instanceIdentifier").getCategory());
        EndpointChange change = find(diffs, "destination/id").getEndpointChange();
        assertEquals("destination", change.role());
        assertEquals("OP", change.baseline().typeCode());
        assertEquals("OUTPUT_PORT", change.baseline().typeName());
        assertEquals("out_success", change.baseline().label());
        assertEquals("out", change.baseline().identifier());
        assertEquals("FN", change.target().typeCode());
        assertEquals("FUNNEL", change.target().typeName());
        assertEquals("Funnel", change.target().label());
        assertEquals("fn", change.target().identifier());
    }
}

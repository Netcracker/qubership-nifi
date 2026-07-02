package org.qubership.nifi.maven.flowdiff.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.maven.flowdiff.compare.Difference;
import org.qubership.nifi.maven.flowdiff.compare.FlowComparator;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TextReporter}: the counts header, the {@code [env]} marker, technical changes counted but not
 * listed, and the whole added and removed flow lines with totals.
 */
class TextReporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowExport flow(final String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            return FlowExport.of("flows/Loader.json", root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ReportModel modelWithMixedChanges() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR","instanceIdentifier":"%s",
                   "properties":{"Batch Size":"%s"},
                   "bundle":{"group":"g","artifact":"a","version":"%s"}}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(template.formatted("old", "1000", "2.0.0")),
                flow(template.formatted("new", "5000", "2.1.0")));
        return new ReportModel(List.of(new FlowReport("flows/Loader.json", changes)), List.of(), List.of());
    }

    @Test
    void headerCountsAndEnvMarkerAndTechnicalNotListed() {
        String report = new TextReporter(200, false).render(modelWithMixedChanges());
        assertTrue(report.contains("(significant: 1, environmental: 1, technical: 1)"), report);
        assertTrue(report.contains("[env] bundle/version: 2.0.0 -> 2.1.0"), report);
        assertTrue(report.contains("properties/Batch Size: 1000 -> 5000"), report);
        assertFalse(report.contains("instanceIdentifier"), report);
    }

    @Test
    void technicalChangesListedWithMarkerWhenShowTechnical() {
        String report = new TextReporter(200, true).render(modelWithMixedChanges());
        assertTrue(report.contains("(significant: 1, environmental: 1, technical: 1)"), report);
        assertTrue(report.contains("[tech] instanceIdentifier: old -> new"), report);
    }

    @Test
    void legendListsOnlyUsedCodes() {
        String report = new TextReporter(200, false).render(modelWithMixedChanges());
        assertTrue(report.startsWith("Types: P = processor"), report);
        assertFalse(report.contains("CS = controller service"), report);
    }

    @Test
    void siblingChangesRenderUnderOtherAttributes() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP"},
                 "parameterContexts":{"Database":{"name":"Database","parameters":[
                   {"name":"Max Connections","value":"%s"}]}}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(template.formatted("10")), flow(template.formatted("20")));
        ReportModel model = new ReportModel(
                List.of(new FlowReport("flows/Loader.json", changes)), List.of(), List.of());
        String report = new TextReporter(200, false).render(model);
        assertTrue(report.contains("  other attributes"), report);
        assertTrue(report.contains("parameterContexts / Database / Max Connections"), report);
        assertTrue(report.contains("value: 10 -> 20"), report);
    }

    @Test
    void wholeAddedAndRemovedFlowsRenderWithTotals() {
        ReportModel model = new ReportModel(List.of(),
                List.of("flows/New.json"), List.of("flows/Old.json"));
        String report = new TextReporter(200, false).render(model);
        assertTrue(report.contains("added flow: flows/New.json"), report);
        assertTrue(report.contains("removed flow: flows/Old.json"), report);
        assertTrue(report.contains("added flows 1, removed flows 1"), report);
    }

    private ReportModel model(final List<Difference> changes) {
        return new ReportModel(List.of(new FlowReport("flows/Loader.json", changes)), List.of(), List.of());
    }

    @Test
    void endpointIdChangeRendersCompactLineInsteadOfPerFieldLines() {
        FlowExport baseline = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"root",
                   "source":{"id":"s1","type":"PROCESSOR","name":"UpdateAttribute"},
                   "destination":{"id":"d-old","type":"OUTPUT_PORT","name":"out_success",
                    "instanceIdentifier":"i-old"}}]}}""");
        FlowExport target = flow("""
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"root",
                   "source":{"id":"s1","type":"PROCESSOR","name":"UpdateAttribute"},
                   "destination":{"id":"d-new","type":"FUNNEL","name":"Funnel","instanceIdentifier":"i-new"}}]}}""");
        List<Difference> changes = new FlowComparator().compare(baseline, target);
        String report = new TextReporter(200, false).render(model(changes));
        assertTrue(report.contains("destination: [OP] out_success (d-old) -> [FN] Funnel (d-new)"), report);
        assertFalse(report.contains("destination/id"), report);
        assertFalse(report.contains("destination/instanceIdentifier"), report);
        assertFalse(report.contains("destination/name"), report);
    }

    @Test
    void positionCollapsesToSingleLine() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                   "position":{"x":%s,"y":%s}}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(template.formatted("464.0", "-96.0")),
                flow(template.formatted("2336.0", "-784.0")));
        String report = new TextReporter(200, false).render(model(changes));
        assertTrue(report.contains("position: (464.0, -96.0) -> (2336.0, -784.0)"), report);
        assertFalse(report.contains("position/x"), report);
        assertFalse(report.contains("position/y"), report);
    }

    @Test
    void positionShowsBothCoordinatesWhenOnlyOneChanged() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                   "position":{"x":%s,"y":64.0}}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(template.formatted("464.0")), flow(template.formatted("500.0")));
        String report = new TextReporter(200, false).render(model(changes));
        assertTrue(report.contains("position: (464.0, 64.0) -> (500.0, 64.0)"), report);
    }

    @Test
    void bendsRenderAsCoordinateList() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"root",
                   "source":{"id":"s1","type":"PROCESSOR","name":"A"},
                   "destination":{"id":"d1","type":"PROCESSOR","name":"B"},"bends":%s}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(template.formatted("[{\"x\":976.0,\"y\":64.0}]")),
                flow(template.formatted("[{\"x\":976.0,\"y\":64.0},{\"x\":975.0,\"y\":63.0}]")));
        String report = new TextReporter(200, false).render(model(changes));
        assertTrue(report.contains("bends: [(976.0, 64.0)] -> [(976.0, 64.0), (975.0, 63.0)]"), report);
    }

    @Test
    void emptyStringValueRendersAsEmptyMarker() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                   "properties":{"HTTP Protocols":"%s"}}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(template.formatted("HTTP_1_1")), flow(template.formatted("")));
        String report = new TextReporter(200, false).render(model(changes));
        assertTrue(report.contains("properties/HTTP Protocols: HTTP_1_1 -> (empty)"), report);
    }

    @Test
    void technicalEndpointFieldsWithUnchangedIdStillRenderedAsTechnical() {
        String template = """
                {"flowContents":{"identifier":"%1$s","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"%1$s",
                   "source":{"id":"p1","type":"PROCESSOR","name":"A","groupId":"%1$s","instanceIdentifier":"%2$s"},
                   "destination":{"id":"p2","type":"PROCESSOR","name":"B","groupId":"%1$s"}}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(template.formatted("oldroot", "si-old")),
                flow(template.formatted("newroot", "si-new")));
        String report = new TextReporter(200, true).render(model(changes));
        assertTrue(report.contains("[tech] source/instanceIdentifier: si-old -> si-new"), report);
        assertTrue(report.contains("[tech] source/groupId: oldroot -> newroot"), report);
        assertTrue(report.contains("[tech] destination/groupId: oldroot -> newroot"), report);
        assertFalse(report.contains("source: ["), report);
    }
}

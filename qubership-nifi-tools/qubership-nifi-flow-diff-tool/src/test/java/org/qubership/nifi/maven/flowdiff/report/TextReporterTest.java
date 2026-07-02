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
        String report = new TextReporter(200).render(modelWithMixedChanges());
        assertTrue(report.contains("(significant: 1, environmental: 1, technical: 1)"), report);
        assertTrue(report.contains("[env] bundle/version: 2.0.0 -> 2.1.0"), report);
        assertTrue(report.contains("properties/Batch Size: 1000 -> 5000"), report);
        assertFalse(report.contains("instanceIdentifier"), report);
    }

    @Test
    void legendListsOnlyUsedCodes() {
        String report = new TextReporter(200).render(modelWithMixedChanges());
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
        String report = new TextReporter(200).render(model);
        assertTrue(report.contains("  other attributes"), report);
        assertTrue(report.contains("parameterContexts / Database / Max Connections"), report);
        assertTrue(report.contains("value: 10 -> 20"), report);
    }

    @Test
    void wholeAddedAndRemovedFlowsRenderWithTotals() {
        ReportModel model = new ReportModel(List.of(),
                List.of("flows/New.json"), List.of("flows/Old.json"));
        String report = new TextReporter(200).render(model);
        assertTrue(report.contains("added flow: flows/New.json"), report);
        assertTrue(report.contains("removed flow: flows/Old.json"), report);
        assertTrue(report.contains("added flows 1, removed flows 1"), report);
    }
}

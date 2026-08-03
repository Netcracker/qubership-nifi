package org.qubership.nifi.maven.flowdiff.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.maven.flowdiff.compare.Difference;
import org.qubership.nifi.maven.flowdiff.compare.FlowComparator;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JsonReporter}: the schema version, per-flow counts, that technical changes are counted but not
 * listed, that every listed record carries {@code pathSegments}, that a root-owned record omits the identifier, and
 * that totals exclude whole added flows from the significant count.
 */
class JsonReporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowExport flow(final String json) {
        try {
            return FlowExport.of("flows/Loader.json", MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode render() {
        return render(false);
    }

    private JsonNode render(final boolean showTechnical) {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                 "position":{"x":%s},"processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR","instanceIdentifier":"%s",
                   "properties":{"Batch Size":"%s"},"bundle":{"group":"g","artifact":"a","version":"%s"}}
                  %s]}}""";
        FlowExport baseline = flow(template.formatted("1", "old", "1000", "2.0.0", ""));
        FlowExport target = flow(template.formatted("2", "new", "5000", "2.1.0",
                ",{\"identifier\":\"p9\",\"name\":\"New\",\"componentType\":\"PROCESSOR\"}"));
        List<Difference> changes = new FlowComparator().compare(baseline, target);
        ReportModel model = new ReportModel(
                List.of(new FlowReport("flows/Loader.json", changes)), List.of("flows/New.json"), List.of());
        try {
            return MAPPER.readTree(new JsonReporter(MAPPER, showTechnical).render(model));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void carriesSchemaCountsAndExcludesTechnicalFromChanges() {
        JsonNode report = render();
        assertEquals(1, report.get("schemaVersion").asInt());
        JsonNode counts = report.get("flows").get(0).get("counts");
        assertEquals(1, counts.get("technical").asInt());
        assertEquals(1, counts.get("environmental").asInt());
        assertEquals(3, counts.get("significant").asInt());
        JsonNode changes = report.get("flows").get(0).get("changes");
        assertEquals(4, changes.size());
        for (JsonNode change : changes) {
            assertTrue(change.get("pathSegments").isArray());
            assertFalse(change.get("path").asText().contains("instanceIdentifier"));
        }
    }

    @Test
    void rootOwnedRecordOmitsIdentifierAndAddedRecordHasNoValues() {
        JsonNode changes = render().get("flows").get(0).get("changes");
        JsonNode rootPosition = find(changes, "Root/position/x");
        assertFalse(rootPosition.has("identifier"));
        JsonNode added = find(changes, "Root/New");
        assertEquals(Difference.ADDED, added.get("change").asText());
        assertEquals("PROCESSOR", added.get("componentType").asText());
        assertFalse(added.has("baselineValue"));
    }

    @Test
    void listsTechnicalChangesWhenShowTechnical() {
        JsonNode report = render(true);
        JsonNode changes = report.get("flows").get(0).get("changes");
        assertEquals(5, changes.size());
        boolean hasTechnical = false;
        for (JsonNode change : changes) {
            if ("technical".equals(change.get("category").asText())) {
                hasTechnical = true;
                assertTrue(change.get("path").asText().contains("instanceIdentifier"), change.toString());
            }
        }
        assertTrue(hasTechnical, changes.toString());
    }

    @Test
    void totalsExcludeWholeAddedFlowsFromSignificant() {
        JsonNode totals = render().get("totals");
        assertEquals(3, totals.get("significant").asInt());
        assertEquals(1, totals.get("addedFlows").asInt());
    }

    @Test
    void keepsPositionCoordinatesSeparateWithPerCoordinateCounts() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR","position":{"x":%s,"y":%s}}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(template.formatted("464.0", "-96.0")),
                flow(template.formatted("2336.0", "-784.0")));
        ReportModel model = new ReportModel(
                List.of(new FlowReport("flows/Loader.json", changes)), List.of(), List.of());
        JsonNode flow;
        try {
            flow = MAPPER.readTree(new JsonReporter(MAPPER, false).render(model)).get("flows").get(0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertEquals(2, flow.get("counts").get("significant").asInt());
        JsonNode reported = flow.get("changes");
        boolean hasX = false;
        boolean hasY = false;
        for (JsonNode change : reported) {
            hasX = hasX || change.get("path").asText().endsWith("position/x");
            hasY = hasY || change.get("path").asText().endsWith("position/y");
        }
        assertTrue(hasX && hasY, reported.toString());
    }

    private JsonNode find(final JsonNode changes, final String path) {
        for (JsonNode change : changes) {
            if (path.equals(change.get("path").asText())) {
                return change;
            }
        }
        throw new IllegalStateException("no change at " + path);
    }
}

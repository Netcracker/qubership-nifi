package org.qubership.nifi.maven.flowdiff.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.maven.flowdiff.compare.Difference;
import org.qubership.nifi.maven.flowdiff.compare.FlowComparator;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MarkdownReporter}: the per-group table, the {@code [env]} marker, inline-code value wrapping with
 * pipe escaping, added-component cells, and the whole added-flow line.
 */
class MarkdownReporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowExport flow(final String json) {
        try {
            return FlowExport.of("flows/Loader.json", MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String render(final List<String> addedFlows) {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                   "properties":{"Query":"%s"},"bundle":{"group":"g","artifact":"a","version":"%s"}}
                  %s]}}""";
        FlowExport baseline = flow(template.formatted("a=1", "2.0.0", ""));
        FlowExport target = flow(template.formatted("a=1 | b=2", "2.1.0",
                ",{\"identifier\":\"p9\",\"name\":\"New\",\"componentType\":\"PROCESSOR\"}"));
        List<Difference> changes = new FlowComparator().compare(baseline, target);
        ReportModel model = new ReportModel(
                List.of(new FlowReport("flows/Loader.json", changes)), addedFlows, List.of());
        return new MarkdownReporter(200, false).render(model);
    }

    @Test
    void listsTechnicalRowWithMarkerWhenShowTechnical() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR","instanceIdentifier":"%s"}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(template.formatted("old")), flow(template.formatted("new")));
        ReportModel model = new ReportModel(
                List.of(new FlowReport("flows/Loader.json", changes)), List.of(), List.of());
        String md = new MarkdownReporter(200, true).render(model);
        assertTrue(md.contains("[tech] `instanceIdentifier`"), md);
    }

    @Test
    void rendersTableWithHeadingAndCountsLine() {
        String md = render(List.of());
        assertTrue(md.contains("## flows/Loader.json"), md);
        assertTrue(md.contains("| Component | Type | Field | Baseline | Target |"), md);
        assertTrue(md.contains("### Root"), md);
    }

    @Test
    void wrapsValuesAsInlineCodeAndEscapesPipe() {
        String md = render(List.of());
        assertTrue(md.contains("`a=1 \\| b=2`"), md);
    }

    @Test
    void marksEnvironmentalRowAndAddedComponent() {
        String md = render(List.of());
        assertTrue(md.contains("[env] `bundle/version`"), md);
        assertTrue(md.contains("| `New` | PROCESSOR | _(added)_ | _(absent)_ | _(present)_ |"), md);
    }

    @Test
    void listsWholeAddedFlows() {
        String md = render(List.of("flows/New.json"));
        assertTrue(md.contains("Added flows: `flows/New.json`"), md);
    }

    private ReportModel model(final List<Difference> changes) {
        return new ReportModel(List.of(new FlowReport("flows/Loader.json", changes)), List.of(), List.of());
    }

    @Test
    void endpointIdChangeRendersSingleCompactRowWithFullTypeNames() {
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
        String md = new MarkdownReporter(200, false).render(model(changes));
        assertTrue(md.contains(
                "| `destination` | `[OUTPUT_PORT] out_success (d-old)` | `[FUNNEL] Funnel (d-new)` |"), md);
        assertFalse(md.contains("destination/id"), md);
        assertFalse(md.contains("destination/instanceIdentifier"), md);
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
        String md = new MarkdownReporter(200, true).render(model(changes));
        assertTrue(md.contains("[tech] `source/instanceIdentifier`"), md);
        assertTrue(md.contains("[tech] `source/groupId`"), md);
        assertTrue(md.contains("[tech] `destination/groupId`"), md);
    }
}

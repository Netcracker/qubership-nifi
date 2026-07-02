package org.qubership.nifi.maven.flowdiff.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.maven.flowdiff.compare.Difference;
import org.qubership.nifi.maven.flowdiff.compare.FlowComparator;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;

import java.util.List;

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
        return new MarkdownReporter(200).render(model);
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
}

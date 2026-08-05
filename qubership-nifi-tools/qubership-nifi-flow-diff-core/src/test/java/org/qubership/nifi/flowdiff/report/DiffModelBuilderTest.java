package org.qubership.nifi.flowdiff.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.flowdiff.LogCapture;
import org.qubership.nifi.flowdiff.flow.FlowExport;
import org.qubership.nifi.flowdiff.io.Candidate;
import org.qubership.nifi.flowdiff.io.SideEntry;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DiffModelBuilder}: union pairing of directories with added, removed, and changed flows; direct
 * pairing of two single files regardless of name; and the flow-vs-non-flow report-plus-warning path. Candidates are
 * loaded on demand as they are paired.
 */
class DiffModelBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Candidate flowEntry(final String display, final String propertyValue) {
        try {
            String json = ("""
                    {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                      {"identifier":"p1","name":"A","componentType":"PROCESSOR","properties":{"k":"%s"}}]}}""")
                    .formatted(propertyValue);
            SideEntry entry = SideEntry.flow(display, FlowExport.of(display, MAPPER.readTree(json)));
            return () -> entry;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Candidate> map(final String key, final Candidate candidate) {
        Map<String, Candidate> map = new LinkedHashMap<>();
        map.put(key, candidate);
        return map;
    }

    @Test
    void directoryPairingReportsChangedAddedRemoved() throws IOException {
        Map<String, Candidate> baseline = new LinkedHashMap<>();
        baseline.put("a.json", flowEntry("a.json", "1"));
        baseline.put("b.json", flowEntry("b.json", "1"));
        Map<String, Candidate> target = new LinkedHashMap<>();
        target.put("a.json", flowEntry("a.json", "2"));
        target.put("c.json", flowEntry("c.json", "1"));

        ReportModel model = new DiffModelBuilder().build(baseline, target, true);

        assertEquals(1, model.getFlows().size());
        assertEquals("a.json", model.getFlows().get(0).getPath());
        assertEquals(List.of("c.json"), model.getAddedFlows());
        assertEquals(List.of("b.json"), model.getRemovedFlows());
    }

    @Test
    void singleFilesBuildDiffForPairDirectlyRegardlessOfName() throws IOException {
        ReportModel model = new DiffModelBuilder().build(
                map("old.json", flowEntry("old.json", "1")),
                map("new.json", flowEntry("new.json", "2")),
                false);
        assertEquals(1, model.getFlows().size());
        assertEquals("new.json", model.getFlows().get(0).getPath());
    }

    @Test
    void flowVersusNonFlowIsReportedWithWarning() throws IOException {
        SideEntry nonFlow = SideEntry.nonFlow("x.json");
        Map<String, Candidate> baseline = map("x.json", flowEntry("x.json", "1"));
        Map<String, Candidate> target = map("x.json", () -> nonFlow);

        try (LogCapture logs = LogCapture.on(DiffModelBuilder.class)) {
            ReportModel model = new DiffModelBuilder().build(baseline, target, true);

            assertEquals(List.of("x.json"), model.getRemovedFlows());
            assertTrue(model.getAddedFlows().isEmpty());
            assertTrue(logs.warnedAbout("non-flow JSON on the target side"));
        }
    }
}

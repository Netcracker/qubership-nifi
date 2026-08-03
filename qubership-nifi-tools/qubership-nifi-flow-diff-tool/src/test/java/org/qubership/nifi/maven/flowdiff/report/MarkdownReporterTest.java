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
        return render(addedFlows, List.of());
    }

    private String render(final List<String> addedFlows, final List<String> removedFlows) {
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
                List.of(new FlowReport("flows/Loader.json", changes)), addedFlows, removedFlows);
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

    @Test
    void listsWholeRemovedFlows() {
        String md = render(List.of(), List.of("flows/Removed.json"));
        assertTrue(md.contains("Removed flows: `flows/Removed.json`"), md);
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
    void positionCollapsesToSingleRow() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                   "position":{"x":%s,"y":%s}}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(template.formatted("464.0", "-96.0")),
                flow(template.formatted("2336.0", "-784.0")));
        String md = new MarkdownReporter(200, false).render(model(changes));
        assertTrue(md.contains("| `Load` | PROCESSOR | `position` | `(464.0, -96.0)` | `(2336.0, -784.0)` |"), md);
        assertFalse(md.contains("position/x"), md);
        assertFalse(md.contains("position/y"), md);
    }

    @Test
    void bendsRenderAsCoordinateListCell() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","connections":[
                  {"identifier":"c1","name":"","componentType":"CONNECTION","groupIdentifier":"root",
                   "source":{"id":"s1","type":"PROCESSOR","name":"A"},
                   "destination":{"id":"d1","type":"PROCESSOR","name":"B"},"bends":%s}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(template.formatted("[{\"x\":976.0,\"y\":64.0}]")),
                flow(template.formatted("[{\"x\":976.0,\"y\":64.0},{\"x\":975.0,\"y\":63.0}]")));
        String md = new MarkdownReporter(200, false).render(model(changes));
        assertTrue(md.contains("`[(976.0, 64.0)]` | `[(976.0, 64.0), (975.0, 63.0)]`"), md);
    }

    @Test
    void emptyStringValueRendersAsBlankCell() {
        String template = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                   "properties":{"HTTP Protocols":"%s"}}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(template.formatted("HTTP_1_1")), flow(template.formatted("")));
        String md = new MarkdownReporter(200, false).render(model(changes));
        assertTrue(md.contains("`properties/HTTP Protocols` | `HTTP_1_1` |  |"), md);
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

    @Test
    void removedFieldRendersAsAbsent() {
        String oldFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                   "properties":{"HTTP Protocols":"HTTP_1_1"}}]}}""";
        String newFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                   "properties":{}}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(oldFlow), flow(newFlow));
        String md = new MarkdownReporter(200, false).render(model(changes));
        assertTrue(md.contains("`properties/HTTP Protocols` | `HTTP_1_1` | `(absent)` |"), md);
    }

    @Test
    void removedComponentRendersAsAbsent() {
        String oldFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load1","componentType":"PROCESSOR",
                   "properties":{"HTTP Protocols":"HTTP_1_1"}}]}}""";
        String newFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p2","name":"Load2","componentType":"PROCESSOR",
                   "properties":{"HTTP Protocols":"HTTP_1_1"}}]}}""";
        List<Difference> changes = new FlowComparator().compare(
                flow(oldFlow), flow(newFlow));
        String md = new MarkdownReporter(200, false).render(model(changes));
        assertTrue(md.contains("`Load1` | PROCESSOR | _(removed)_ | _(present)_ | _(absent)_ |"), md);
        assertTrue(md.contains("`Load2` | PROCESSOR | _(added)_ | _(absent)_ | _(present)_ |"), md);
    }

    @Test
    void parameterContextChangeTest() {
        String oldFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load2","componentType":"PROCESSOR",
                   "properties":{"HTTP Protocols":"HTTP_1_1"}}]},
                   "parameterContexts":{"reporting-params":{"componentType":"PARAMETER_CONTEXT",
                        "inheritedParameterContexts":[],
                        "name":"reporting-params",
                        "parameters":[
                            {"description":"","name":"param1","provided":false,"sensitive":true},
                            {"description":"param description2","name":"param2",
                                    "provided":false,"sensitive":false,"value":"value-old"},
                            {"description":"param description3","name":"param3",
                                    "provided":false,"sensitive":false,"value":"value31"}
                        ]}
                    }
                }
            """;
        String newFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
                  {"identifier":"p1","name":"Load2","componentType":"PROCESSOR",
                   "properties":{"HTTP Protocols":"HTTP_1_1"}}]},
                   "parameterContexts":{"reporting-params":{"componentType":"PARAMETER_CONTEXT",
                        "inheritedParameterContexts":[],
                        "name":"reporting-params",
                        "parameters":[
                            {"description":"param description2","name":"param2",
                                    "provided":false,"sensitive":false,"value":"value-new"},
                            {"description":"param description3","name":"param3",
                                    "provided":false,"sensitive":false,"value":"value31"},
                            {"description":"param description3","name":"param4",
                                    "provided":false,"sensitive":false,"value":"value4"}
                        ]}
                    }
                }
            """;
        List<Difference> changes = new FlowComparator().compare(
                flow(oldFlow), flow(newFlow));
        String md = new MarkdownReporter(200, false).render(model(changes));
        assertTrue(md.contains("`parameterContexts / reporting-params / param1` |"
                + " _(parameter)_ | _(removed)_ | _(present)_ | _(absent)_ |"), md);
        assertTrue(md.contains("`parameterContexts / reporting-params / param2` |"
                + " _(parameter)_ | `value` | `value-old` | `value-new` |"), md);
        assertTrue(md.contains("`parameterContexts / reporting-params / param4` |"
                + " _(parameter)_ | _(added)_ | _(absent)_ | _(present)_ |"), md);
    }

    @Test
    void childProcessGroupPath() {
        String oldFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                   "processGroups":[{"identifier":"pg1","name":"pg1",
                        "processors":[{"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                            "properties":{"HTTP Protocols":"HTTP_1_1"}}]
                   }]
                   }
                }
            """;
        String newFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                   "processGroups":[{"identifier":"pg1","name":"pg1",
                        "processors":[{"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                            "properties":{}}]
                   }]
                   }
                }
            """;
        List<Difference> changes = new FlowComparator().compare(
                flow(oldFlow), flow(newFlow));
        String md = new MarkdownReporter(200, false).render(model(changes));
        assertTrue(md.contains("### Root / pg1"), md);
        assertTrue(md.contains("`Load` | PROCESSOR | `properties/HTTP Protocols` | `HTTP_1_1` | `(absent)` |"), md);
    }

    @Test
    void childProcessGroupPathWithDuplicateName() {
        String oldFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                   "processGroups":[
                        {"identifier":"pg1","name":"pg1",
                            "processors":[{"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                                "properties":{"HTTP Protocols":"HTTP_1_1"}}]},
                        {"identifier":"pg2","name":"pg1",
                            "processors":[{"identifier":"p2","name":"Load","componentType":"PROCESSOR",
                                "properties":{"HTTP Protocols":"HTTP_1_1"}}]}
                   ]
                   }
                }
            """;
        String newFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                   "processGroups":[
                        {"identifier":"pg1","name":"pg1",
                            "processors":[{"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                                "properties":{"HTTP Protocols":"HTTP_1_2"}}]},
                        {"identifier":"pg2","name":"pg1",
                            "processors":[{"identifier":"p2","name":"Load","componentType":"PROCESSOR",
                                "properties":{"HTTP Protocols":"HTTP_1_2"}}]}
                   ]
                   }
                }
            """;
        List<Difference> changes = new FlowComparator().compare(
                flow(oldFlow), flow(newFlow));
        String md = new MarkdownReporter(200, false).render(model(changes));
        assertTrue(md.contains("### Root / pg1(pg1)"), md);
        assertTrue(md.contains("### Root / pg1(pg2)"), md);
        assertTrue(md.contains("`Load` | PROCESSOR | `properties/HTTP Protocols` | `HTTP_1_1` | `HTTP_1_2` |"), md);
    }

    @Test
    void childProcessGroupRename() {
        String oldFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                   "processGroups":[{"identifier":"pg1","name":"pg1",
                        "processors":[{"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                            "properties":{"HTTP Protocols":"HTTP_1_1"}}]
                   }]
                   }
                }
            """;
        String newFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                   "processGroups":[{"identifier":"pg1","name":"pg2",
                        "processors":[{"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                            "properties":{}}]
                   }]
                   }
                }
            """;
        List<Difference> changes = new FlowComparator().compare(
                flow(oldFlow), flow(newFlow));
        String md = new MarkdownReporter(200, false).render(model(changes));
        assertTrue(md.contains("### Root / pg2"), md);
        assertTrue(md.contains("_(group)_ | PROCESS_GROUP | `name` | `pg1` | `pg2` |"), md);
    }

    @Test
    void childProcessGroupAdded() {
        String oldFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                   "processGroups":[{"identifier":"pg1","name":"pg1",
                        "processors":[{"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                            "properties":{"HTTP Protocols":"HTTP_1_1"}}]
                   }]
                   }
                }
            """;
        String newFlow = """
                {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP",
                   "processGroups":[{"identifier":"pg2","name":"pg1",
                        "processors":[{"identifier":"p1","name":"Load","componentType":"PROCESSOR",
                            "properties":{"HTTP Protocols":"HTTP_1_1"}}]
                   }]
                   }
                }
            """;
        List<Difference> changes = new FlowComparator().compare(
                flow(oldFlow), flow(newFlow));
        String md = new MarkdownReporter(200, false).render(model(changes));
        assertTrue(md.contains("### Root"), md);
        assertTrue(md.contains("`pg1` | PROCESS_GROUP | _(removed)_ | _(present)_ | _(absent)_ |"), md);
        assertTrue(md.contains("`pg1` | PROCESS_GROUP | _(added)_ | _(absent)_ | _(present)_ |"), md);
    }
}

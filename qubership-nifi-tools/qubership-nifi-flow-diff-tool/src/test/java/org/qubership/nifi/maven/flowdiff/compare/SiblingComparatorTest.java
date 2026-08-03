package org.qubership.nifi.maven.flowdiff.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.maven.flowdiff.flow.FlowExport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.nifi.maven.flowdiff.compare.Difference.ADDED;
import static org.qubership.nifi.maven.flowdiff.compare.Difference.REMOVED;

/**
 * Tests for {@link SiblingComparator}: parameter-context and parameter comparison by name, parameter-provider and
 * external-controller-service comparison by identifier, the environmental classification of {@code flowEncodingVersion}
 * and provider {@code bundle.version}, and the single-segment handling of a name containing {@code /}.
 */
class SiblingComparatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SiblingComparator comparator = new SiblingComparator();

    private JsonNode root(final String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Difference only(final List<Difference> diffs) {
        assertEquals(1, diffs.size(), () -> "expected one difference but got " + diffs);
        return diffs.get(0);
    }

    @Test
    void parameterValueChangeIsSignificant() {
        String template = """
                {"parameterContexts":{"Database":{"name":"Database","parameters":[
                  {"name":"Max Connections","value":"%s"}]}}}""";
        Difference diff = only(comparator.compare(root(template.formatted("10")), root(template.formatted("20"))));
        assertEquals(ChangeCategory.SIGNIFICANT, diff.getCategory());
        assertEquals("parameterContexts/Database/parameters/Max Connections/value", diff.getPath());
        assertEquals("parameterContexts / Database / Max Connections", diff.getShortLabel());
        assertTrue(diff.isOtherAttributes());
    }

    @Test
    void flowEncodingVersionIsEnvironmental() {
        Difference diff = only(comparator.compare(
                root("{\"flowEncodingVersion\":\"1.0\"}"), root("{\"flowEncodingVersion\":\"1.1\"}")));
        assertEquals(ChangeCategory.ENVIRONMENTAL, diff.getCategory());
        assertEquals(List.of("flowEncodingVersion"), diff.getPathSegments());
    }

    @Test
    void latestSiblingIsAcceptedAndIgnored() {
        String template = """
                {"flowContents":{"identifier":"root","name":"R","componentType":"PROCESS_GROUP"},"latest":%s}""";
        JsonNode baseline = root(template.formatted("true"));
        JsonNode target = root(template.formatted("false"));
        // A direct NiFi flow export carries a top-level 'latest' flag; the closed sibling set must keep accepting it.
        assertDoesNotThrow(() -> FlowExport.of("baseline.json", baseline));
        assertDoesNotThrow(() -> FlowExport.of("target.json", target));
        // 'latest' is ignored rather than reported, so a difference there produces no diff.
        assertTrue(comparator.compare(baseline, target).isEmpty());
    }

    @Test
    void providerBundleVersionEnvironmentalAndNameSignificant() {
        String template = """
                {"parameterProviders":{"id1":{"identifier":"id1","name":"%s","type":"T",
                  "bundle":{"group":"g","artifact":"a","version":"%s"}}}}""";
        List<Difference> diffs = comparator.compare(
                root(template.formatted("Old", "2.0.0")), root(template.formatted("New", "2.1.0")));
        Difference bundle = diffs.stream().filter(d -> "bundle/version".equals(d.getFieldPath())).findFirst().get();
        Difference name = diffs.stream().filter(d -> "name".equals(d.getFieldPath())).findFirst().get();
        assertEquals(ChangeCategory.ENVIRONMENTAL, bundle.getCategory());
        assertEquals(ChangeCategory.SIGNIFICANT, name.getCategory());
        assertEquals("Old", name.getNameBaseline());
        assertEquals("New", name.getNameTarget());
    }

    @Test
    void externalControllerServiceNameRename() {
        String template = """
                {"externalControllerServices":{"cs1":{"identifier":"cs1","name":"%s"}}}""";
        Difference diff = only(comparator.compare(root(template.formatted("Old")), root(template.formatted("New"))));
        assertEquals("externalControllerServices/New(cs1)/name", diff.getPath());
        assertEquals("New", diff.getNameTarget());
    }

    @Test
    void changedProviderIdentifierIsRemoveAndAdd() {
        JsonNode baseline = root("""
                {"parameterProviders":{"id-a":{"identifier":"id-a","name":"P","type":"T"}}}""");
        JsonNode target = root("""
                {"parameterProviders":{"id-b":{"identifier":"id-b","name":"P","type":"T"}}}""");
        List<Difference> diffs = comparator.compare(baseline, target);
        assertEquals(2, diffs.size());
        assertTrue(diffs.stream().anyMatch(d -> ADDED.equals(d.getChange())));
        assertTrue(diffs.stream().anyMatch(d -> REMOVED.equals(d.getChange())));
    }

    @Test
    void addedParameterContextIsSingleSignificant() {
        Difference diff = only(comparator.compare(
                root("{\"parameterContexts\":{}}"),
                root("{\"parameterContexts\":{\"New\":{\"name\":\"New\",\"parameters\":[]}}}")));
        assertEquals(ADDED, diff.getChange());
        assertEquals("parameterContexts/New", diff.getPath());
    }

    @Test
    void addedParameterIsSingleSignificant() {
        JsonNode baseline = root("{\"parameterContexts\":{\"C\":{\"name\":\"C\",\"parameters\":[]}}}");
        JsonNode target = root("""
                {"parameterContexts":{"C":{"name":"C","parameters":[{"name":"P","value":"v"}]}}}""");
        Difference diff = only(comparator.compare(baseline, target));
        assertEquals(ADDED, diff.getChange());
        assertEquals("parameterContexts/C/parameters/P", diff.getPath());
    }

    @Test
    void parameterNameWithSlashStaysSingleSegment() {
        String template = """
                {"parameterContexts":{"C":{"name":"C","parameters":[
                  {"name":"with/slash","value":"%s"}]}}}""";
        Difference diff = only(comparator.compare(root(template.formatted("a")), root(template.formatted("b"))));
        assertTrue(diff.getPathSegments().contains("with/slash"), diff.getPathSegments().toString());
        assertEquals(List.of("parameterContexts", "C", "parameters", "with/slash", "value"), diff.getPathSegments());
    }
}

package org.qubership.nifi.maven.transform.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicatePathResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TYPE = "org.qubership.nifi.TestProcessor";
    private final DuplicatePathResolver resolver = new DuplicatePathResolver();

    private ProcessGroup rootGroup() {
        return new ProcessGroup("root", "root-id", List.of(), List.of(), null, false);
    }

    @Test
    void disambiguateLeavesUniquePathsUnchanged() {
        ProcessGroup root = rootGroup();
        Processor p1 = new Processor("Alpha", TYPE, "id-1", MAPPER.createObjectNode(), root);
        Processor p2 = new Processor("Beta", TYPE, "id-2", MAPPER.createObjectNode(), root);

        List<List<Processor>> collisions = resolver.disambiguate(List.of(p1, p2));

        assertEquals(Path.of("Alpha"), p1.getRelativePath());
        assertEquals(Path.of("Beta"), p2.getRelativePath());
        assertTrue(collisions.isEmpty());
    }

    @Test
    void disambiguateAppendsIdentifierSuffixToProcessorsWithSameName() {
        ProcessGroup root = rootGroup();
        Processor p1 = new Processor("MyProcessor", TYPE, "123e4567-e89b-12d3-a456-111111111111",
                MAPPER.createObjectNode(), root);
        Processor p2 = new Processor("MyProcessor", TYPE, "123e4567-e89b-12d3-a456-222222222222",
                MAPPER.createObjectNode(), root);

        List<List<Processor>> collisions = resolver.disambiguate(List.of(p1, p2));

        assertEquals(Path.of("MyProcessor_111111111111"), p1.getRelativePath());
        assertEquals(Path.of("MyProcessor_222222222222"), p2.getRelativePath());
        assertNotEquals(p1.getRelativePath(), p2.getRelativePath());
        assertEquals(1, collisions.size());
        assertEquals(List.of(p1, p2), collisions.get(0));
    }

    @Test
    void disambiguateAppendsSuffixToAllProcessorsSharingAName() {
        ProcessGroup root = rootGroup();
        Processor p1 = new Processor("MyProcessor", TYPE, "id-111111111111",
                MAPPER.createObjectNode(), root);
        Processor p2 = new Processor("MyProcessor", TYPE, "id-222222222222",
                MAPPER.createObjectNode(), root);
        Processor p3 = new Processor("MyProcessor", TYPE, "id-333333333333",
                MAPPER.createObjectNode(), root);

        resolver.disambiguate(List.of(p1, p2, p3));

        assertEquals(Path.of("MyProcessor_111111111111"), p1.getRelativePath());
        assertEquals(Path.of("MyProcessor_222222222222"), p2.getRelativePath());
        assertEquals(Path.of("MyProcessor_333333333333"), p3.getRelativePath());
    }

    @Test
    void disambiguateResolvesCollisionCausedByNameEncoding() {
        ProcessGroup root = rootGroup();
        Processor withSpecialChar = new Processor("Filter a>b", TYPE, "id-111111111111",
                MAPPER.createObjectNode(), root);
        Processor withToken = new Processor("Filter a_gt_b", TYPE, "id-222222222222",
                MAPPER.createObjectNode(), root);

        resolver.disambiguate(List.of(withSpecialChar, withToken));

        assertEquals(Path.of("Filter a_gt_b_111111111111"), withSpecialChar.getRelativePath());
        assertEquals(Path.of("Filter a_gt_b_222222222222"), withToken.getRelativePath());
    }

    @Test
    void disambiguateResolvesCollisionCausedByCaseDifferenceAlone() {
        ProcessGroup root = rootGroup();
        Processor p1 = new Processor("Load customers", TYPE, "id-111111111111",
                MAPPER.createObjectNode(), root);
        Processor p2 = new Processor("Load Customers", TYPE, "id-222222222222",
                MAPPER.createObjectNode(), root);

        List<List<Processor>> collisions = resolver.disambiguate(List.of(p1, p2));

        assertEquals(Path.of("Load customers_111111111111"), p1.getRelativePath());
        assertEquals(Path.of("Load Customers_222222222222"), p2.getRelativePath());
        assertEquals(1, collisions.size());
    }

    @Test
    void disambiguateResolvesCollisionCausedByParentGroupNamesMatching() {
        ProcessGroup root = rootGroup();
        ProcessGroup group1 = new ProcessGroup("SameGroup", "gid1", List.of(), List.of(), root, false);
        ProcessGroup group2 = new ProcessGroup("SameGroup", "gid2", List.of(), List.of(), root, false);
        Processor p1 = new Processor("MyProcessor", TYPE, "id-111111111111",
                MAPPER.createObjectNode(), group1);
        Processor p2 = new Processor("MyProcessor", TYPE, "id-222222222222",
                MAPPER.createObjectNode(), group2);

        resolver.disambiguate(List.of(p1, p2));

        assertNotEquals(p1.getRelativePath(), p2.getRelativePath());
    }

    @Test
    void disambiguateIsIdempotent() {
        ProcessGroup root = rootGroup();
        Processor p1 = new Processor("MyProcessor", TYPE, "id-111111111111",
                MAPPER.createObjectNode(), root);
        Processor p2 = new Processor("MyProcessor", TYPE, "id-222222222222",
                MAPPER.createObjectNode(), root);
        List<Processor> processors = List.of(p1, p2);

        List<List<Processor>> firstCollisions = resolver.disambiguate(processors);
        Path firstResolution = p1.getRelativePath();
        List<List<Processor>> secondCollisions = resolver.disambiguate(processors);

        assertEquals(firstResolution, p1.getRelativePath());
        assertEquals(firstCollisions, secondCollisions);
    }
}

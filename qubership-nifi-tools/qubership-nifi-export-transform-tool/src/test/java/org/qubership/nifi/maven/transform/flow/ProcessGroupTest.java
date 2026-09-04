package org.qubership.nifi.maven.transform.flow;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessGroupTest {

    private ProcessGroup group(String name, ProcessGroup parent) {
        return new ProcessGroup(name, name + "-id", List.of(), List.of(), parent, false);
    }

    @Test
    void getPathSegmentsReturnsEmptyListForRootGroup() {
        ProcessGroup root = group("root", null);
        assertTrue(root.getPathSegments().isEmpty());
    }

    @Test
    void getPathSegmentsReturnsSingleNameForDirectChild() {
        ProcessGroup root = group("root", null);
        ProcessGroup child = group("Extract", root);
        assertEquals(List.of("Extract"), child.getPathSegments());
    }

    @Test
    void getPathSegmentsReturnsAllSegmentsInOrderForNestedGroup() {
        ProcessGroup root = group("root", null);
        ProcessGroup child = group("Extract", root);
        ProcessGroup grandchild = group("PutSQL_pg", child);
        assertEquals(List.of("Extract", "PutSQL_pg"), grandchild.getPathSegments());
    }

    @Test
    void getRelativePathReturnsEmptyPathForRootGroup() {
        ProcessGroup root = group("root", null);
        assertEquals(Path.of(""), root.getRelativePath());
    }

    @Test
    void getRelativePathReturnsSingleSegmentForDirectChild() {
        ProcessGroup root = group("root", null);
        ProcessGroup child = group("Extract", root);
        assertEquals(Path.of("Extract"), child.getRelativePath());
    }

    @Test
    void getRelativePathReturnsNestedPathForDeepGroup() {
        ProcessGroup root = group("root", null);
        ProcessGroup child = group("Extract", root);
        ProcessGroup grandchild = group("PutSQL_pg", child);
        assertEquals(Path.of("Extract", "PutSQL_pg"), grandchild.getRelativePath());
    }

    @Test
    void getRelativePathEncodesSpecialCharactersInGroupName() {
        ProcessGroup root = group("root", null);
        ProcessGroup child = group("Get status/error", root);
        assertEquals(Path.of("Get status_sl_error"), child.getRelativePath());
    }

    @Test
    void getRelativePathEncodesSpecialCharactersInEverySegment() {
        ProcessGroup root = group("root", null);
        ProcessGroup child = group("a<b", root);
        ProcessGroup grandchild = group("c>d", child);
        assertEquals(Path.of("a_lt_b", "c_gt_d"), grandchild.getRelativePath());
    }

    @Test
    void getPathSegmentsKeepsSpecialCharactersUnchanged() {
        ProcessGroup root = group("root", null);
        ProcessGroup child = group("Get status/error", root);
        assertEquals(List.of("Get status/error"), child.getPathSegments());
    }

    @Test
    void isVersionedReturnsTrueWhenVersioned() {
        ProcessGroup g = new ProcessGroup("g", "id", List.of(), List.of(), null, true);
        assertTrue(g.isVersioned());
    }

    @Test
    void isVersionedReturnsFalseWhenNotVersioned() {
        ProcessGroup g = new ProcessGroup("g", "id", List.of(), List.of(), null, false);
        assertFalse(g.isVersioned());
    }
}

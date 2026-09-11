package org.qubership.nifi.maven.transform.flow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves colliding export paths between processors of configured types.
 *
 * Processors that would otherwise share a path (before any disambiguation suffix) are
 * marked via markPathDisambiguated(), which makes each one's getRelativePath() distinct.
 * Two paths that differ only in case are treated as colliding too, since Windows and the
 * macOS default file system resolve them to the same directory.
 */
public class DuplicatePathResolver {

    /**
     * Marks every processor whose export path collides with another one's, so each
     * getRelativePath() call afterward returns a distinct path.
     *
     * Grouping uses getBaseRelativePath(), not getRelativePath(), so a repeated call finds
     * the same collisions regardless of whether the processors are already marked. Grouping
     * is case-insensitive, so the result is the same on every platform regardless of whether
     * the file system Extract actually runs on is case-sensitive.
     *
     * @param processors processors to check for colliding export paths
     * @return each group of two or more processors that shared a path, in the order first seen;
     *         empty if no processor collided with another
     */
    public List<List<Processor>> disambiguate(Collection<Processor> processors) {
        Map<String, List<Processor>> byPath = new LinkedHashMap<>();
        for (Processor processor : processors) {
            String path = processor.getBaseRelativePath().toString().replace("\\", "/");
            byPath.computeIfAbsent(path.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(processor);
        }
        List<List<Processor>> collisions = new ArrayList<>();
        for (List<Processor> group : byPath.values()) {
            if (group.size() > 1) {
                group.forEach(Processor::markPathDisambiguated);
                collisions.add(group);
            }
        }
        return collisions;
    }
}

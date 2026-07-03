package org.qubership.nifi.maven.flowdiff.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * File-system helpers shared by {@link DirectorySource} and {@link GitSource}: recursive discovery of {@code *.json}
 * files in deterministic order, and conversion of a path to {@code /}-separated form for stable report keys.
 */
final class JsonFiles {

    /** The suffix that marks a candidate flow-export file. */
    static final String JSON_SUFFIX = ".json";

    private JsonFiles() {
    }

    /**
     * Walks a directory tree and returns its regular {@code *.json} files sorted by path.
     *
     * @param root the directory to walk
     * @return the discovered JSON files in deterministic order
     * @throws IOException when the tree cannot be walked
     */
    static List<Path> under(final Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = new ArrayList<>(walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(JSON_SUFFIX))
                    .toList());
            files.sort(Path::compareTo);
            return files;
        }
    }

    /**
     * Converts a path to {@code /}-separated form so keys are stable across platforms.
     *
     * @param path the path
     * @return the path text with {@code \} replaced by {@code /}
     */
    static String toPosix(final Path path) {
        return path.toString().replace('\\', '/');
    }
}

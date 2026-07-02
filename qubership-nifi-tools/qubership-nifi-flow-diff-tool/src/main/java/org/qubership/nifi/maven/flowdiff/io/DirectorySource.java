package org.qubership.nifi.maven.flowdiff.io;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads one side of a diff from the file system: a directory is walked recursively for {@code *.json} files, and a
 * single file is read directly. Each candidate is classified through a {@link FlowClassifier} as a flow export or a
 * non-flow JSON.
 */
public final class DirectorySource {

    private static final String JSON_SUFFIX = ".json";

    private final Path input;
    private final FlowClassifier classifier;

    /**
     * Creates a directory source.
     *
     * @param inputFile       the input directory or single file
     * @param classifierValue the flow classifier
     */
    public DirectorySource(final File inputFile, final FlowClassifier classifierValue) {
        this.input = inputFile.toPath();
        this.classifier = classifierValue;
    }

    /**
     * Tells whether the input is a directory.
     *
     * @return {@code true} when the input is a directory
     */
    public boolean isDirectory() {
        return Files.isDirectory(input);
    }

    /**
     * Discovers and classifies the candidates on this side, keyed by relative path (directory input) or file name
     * (single-file input).
     *
     * @return the discovered entries in deterministic order
     * @throws IOException when a file cannot be read
     */
    public Map<String, SideEntry> read() throws IOException {
        Map<String, SideEntry> entries = new LinkedHashMap<>();
        if (isDirectory()) {
            for (Path file : jsonFiles()) {
                String relative = toPosix(input.relativize(file));
                SideEntry entry = classify(file, relative);
                if (entry != null) {
                    entries.put(relative, entry);
                }
            }
        } else {
            String display = input.getFileName().toString();
            SideEntry entry = classify(input, display);
            if (entry != null) {
                entries.put(display, entry);
            }
        }
        return entries;
    }

    private List<Path> jsonFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(input)) {
            List<Path> files = new ArrayList<>(walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(JSON_SUFFIX))
                    .toList());
            files.sort(Path::compareTo);
            return files;
        }
    }

    private SideEntry classify(final Path file, final String display) throws IOException {
        return classifier.classify(Files.readString(file, StandardCharsets.UTF_8), display);
    }

    private static String toPosix(final Path path) {
        return path.toString().replace('\\', '/');
    }
}

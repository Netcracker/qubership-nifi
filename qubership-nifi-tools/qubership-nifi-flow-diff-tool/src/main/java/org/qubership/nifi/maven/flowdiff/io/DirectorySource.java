package org.qubership.nifi.maven.flowdiff.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads one side of a diff from the file system: a directory is walked recursively for {@code *.json} files, and a
 * single file is read directly. Discovery enumerates the candidates keyed by relative path (directory input) or file
 * name (single-file input) without reading their content; each is loaded and classified through a
 * {@link FlowClassifier} as a flow export or a non-flow JSON only when the pair loop asks for it.
 */
public final class DirectorySource {

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
     * Discovers the candidates on this side without reading their content, keyed by relative path (directory input) or
     * file name (single-file input). Each candidate reads, classifies, and parses its file only when loaded.
     *
     * @return the discovered candidates in deterministic order
     * @throws IOException when the directory tree cannot be walked
     */
    public Map<String, Candidate> discover() throws IOException {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        if (isDirectory()) {
            for (Path file : JsonFiles.under(input)) {
                String relative = JsonFiles.toPosix(input.relativize(file));
                candidates.put(relative, () -> classifier.classify(file.toFile(), relative));
            }
        } else {
            String display = input.getFileName().toString();
            candidates.put(display, () -> classifier.classify(input.toFile(), display));
        }
        return candidates;
    }
}

package org.qubership.nifi.maven.flowdiff.io;

import java.io.IOException;

/**
 * A discovered candidate at one relative path whose content is not read until it is needed. Discovery enumerates the
 * candidates on each side cheaply (a directory walk or a Git tree walk, without reading file content); the pair loop
 * then loads one side at a time, so only the flow (or pair of flows) currently being compared is held in memory rather
 * than every parsed flow on both sides at once.
 */
@FunctionalInterface
public interface Candidate {

    /**
     * Reads, classifies, and parses this candidate on demand.
     *
     * @return the loaded entry, or {@code null} when a malformed file is skipped
     * @throws IOException when the candidate cannot be read
     */
    SideEntry load() throws IOException;
}

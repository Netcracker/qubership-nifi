package org.qubership.nifi.maven.transform.flow;

import java.util.Map;

/**
 * Encodes a single name (a processor name or a process group name) into a string
 * that is safe to use as one path segment on every operating system.
 *
 * NiFi processor and process group names are free-form user text and may contain
 * characters that are not allowed in file and directory names on Windows:
 * {@code \ / : * ? " < > |}. Each such character is replaced with a short
 * underscore-delimited token (for example {@code <} becomes {@code _lt_}).
 *
 * The underscore itself is the token delimiter and is left unchanged.
 *
 * There is no matching decode operation: the encoded form is only ever used as a
 * directory name and inside the {@code @}-reference value, and nothing needs to
 * recover the original name from it.
 */
public final class PathSegmentEncoder {

    /**
     * Maps each unsafe character to its replacement token.
     */
    private static final Map<Character, String> DICTIONARY = Map.ofEntries(
            Map.entry('\\', "_bs_"),
            Map.entry('/', "_sl_"),
            Map.entry(':', "_cl_"),
            Map.entry('*', "_st_"),
            Map.entry('?', "_qm_"),
            Map.entry('"', "_qt_"),
            Map.entry('<', "_lt_"),
            Map.entry('>', "_gt_"),
            Map.entry('|', "_vb_"));

    private PathSegmentEncoder() {
    }

    /**
     * Replaces every unsafe character in the given name with its token.
     * A name that contains no unsafe characters is returned unchanged in content.
     * Applying this method to an already encoded string does not change it further,
     * because the tokens contain no unsafe characters.
     *
     * @param segment a single processor or process group name
     * @return the name with all unsafe characters replaced by tokens
     */
    public static String encode(String segment) {
        StringBuilder result = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char currentChar = segment.charAt(i);
            String replacement = DICTIONARY.get(currentChar);
            result.append(replacement != null ? replacement : currentChar);
        }
        return result.toString();
    }
}

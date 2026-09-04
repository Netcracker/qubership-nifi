package org.qubership.nifi.maven.transform.flow;

import java.util.Map;

/**
 * Replaces the nine characters not allowed in Windows file and directory names
 * (backslash, slash, colon, asterisk, question mark, double quote, less-than,
 * greater-than, vertical bar) with underscore-delimited tokens, so a processor or
 * process group name can be used as one path segment - for example ">" becomes
 * "_gt_". The underscore itself is left unchanged, and there is no decode: the
 * encoded form is only used as a directory name and inside the reference value.
 *
 * A few names the platform still rejects (an empty name, a NUL character, or on
 * Windows a trailing space or a reserved device name such as CON) are left
 * untouched and fail when the path is built or the directory is created. None of
 * these are expected in real NiFi component names.
 */
public final class PathSegmentEncoder {

    /**
     * The replacement token for each unsafe character.
     */
    private static final Map<Character, String> REPLACEMENTS = Map.ofEntries(
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
     * Replaces every unsafe character in the given name with its token, for example
     * ">" with "_gt_".
     *
     * The mapping is one-way and many-to-one: the tokens are ordinary text, so a
     * name that literally contains "_gt_" encodes to the same string as a name that
     * contains ">". Code that must keep such names apart compares the encoded form
     * (see FlowValidator). Encoding an already encoded string changes nothing,
     * because the tokens contain no unsafe characters.
     *
     * @param segment a single processor or process group name
     * @return the name with every unsafe character replaced by its token
     */
    public static String encode(String segment) {
        StringBuilder result = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char currentChar = segment.charAt(i);
            String replacement = REPLACEMENTS.get(currentChar);
            result.append(replacement != null ? replacement : currentChar);
        }
        return result.toString();
    }
}

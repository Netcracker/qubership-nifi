package org.qubership.nifi.maven.transform.flow;

import java.util.Map;

/**
 * Replaces the nine characters that are not allowed in Windows file and directory
 * names (backslash, slash, colon, asterisk, question mark, double quote, less-than,
 * greater-than, vertical bar) with short underscore-delimited tokens, so a processor
 * or process group name can be used as one path segment. For example the greater-than
 * sign becomes _gt_.
 *
 * This does not guarantee that every name can be used as a directory. Names the
 * platform's own path rules still reject are left untouched and surface as an
 * error when the export path is built or the directory is created: a NUL
 * character on any system, and a trailing space or a reserved device name such as
 * CON on Windows. An empty name is also left untouched - the extracted file then
 * lands in the parent group's directory instead of its own. None of these are
 * expected in real NiFi component names.
 *
 * The underscore is the token delimiter and is left unchanged. There is no decode
 * operation: the encoded form is only used as a directory name and inside the
 * reference value, and nothing needs to recover the original name from it.
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

package org.qubership.nifi.tools.jsonformat;

import com.fasterxml.jackson.core.util.Separators;

/**
 * Immutable description of a JSON document's textual formatting.
 *
 * <p>The values describe a single, global formatting style that a Jackson
 * {@link com.fasterxml.jackson.core.util.DefaultPrettyPrinter} can reproduce. They do not capture
 * per-element variations (for example, "small arrays inline but large arrays expanded"); see the
 * module README for the documented limitations.</p>
 *
 * @param indent per-level indentation unit (for example {@code "  "} or {@code "\t"}); used only
 *               when objects or arrays are {@link ContainerStyle#EXPANDED}
 * @param eol line separator, either {@code "\n"} or {@code "\r\n"}
 * @param objectStyle layout style applied to objects
 * @param arrayStyle layout style applied to arrays
 * @param objectColonSpacing spacing around the {@code :} between a field name and its value
 * @param objectEntrySpacing spacing after the {@code ,} separating inline object members (used only
 *                           when {@code objectStyle} is {@link ContainerStyle#INLINE})
 * @param arrayElementSpacing spacing after the {@code ,} separating inline array elements (used only
 *                            when {@code arrayStyle} is {@link ContainerStyle#INLINE})
 * @param objectEmptySeparator text rendered inside an empty object ({@code ""} -&gt; {@code {}},
 *                             {@code " "} -&gt; <code>{ }</code>)
 * @param arrayEmptySeparator text rendered inside an empty array ({@code ""} -&gt; {@code []},
 *                            {@code " "} -&gt; {@code [ ]})
 * @param trailingNewline whether the document ends with a final EOL
 */
public record JsonFormat(
        String indent,
        String eol,
        ContainerStyle objectStyle,
        ContainerStyle arrayStyle,
        Separators.Spacing objectColonSpacing,
        Separators.Spacing objectEntrySpacing,
        Separators.Spacing arrayElementSpacing,
        String objectEmptySeparator,
        String arrayEmptySeparator,
        boolean trailingNewline
) {

    /** Indentation unit used when none can be detected. */
    public static final String DEFAULT_INDENT = "  ";

    /** Line separator used when none can be detected. */
    public static final String DEFAULT_EOL = "\n";

    /**
     * Returns a sensible default format: two-space indentation, LF line endings, expanded objects
     * and arrays, a single space after each colon, compact empty containers and a trailing newline.
     *
     * @return the default {@link JsonFormat}
     */
    public static JsonFormat defaults() {
        return new JsonFormat(
                DEFAULT_INDENT,
                DEFAULT_EOL,
                ContainerStyle.EXPANDED,
                ContainerStyle.EXPANDED,
                Separators.Spacing.AFTER,
                Separators.Spacing.NONE,
                Separators.Spacing.NONE,
                "",
                "",
                true);
    }
}

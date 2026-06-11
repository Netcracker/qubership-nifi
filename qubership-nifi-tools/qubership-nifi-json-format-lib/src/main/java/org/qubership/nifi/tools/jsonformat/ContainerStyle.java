package org.qubership.nifi.tools.jsonformat;

/**
 * Layout style applied to a JSON object or array container.
 */
public enum ContainerStyle {

    /** Each member on its own line, indented (Jackson {@code DefaultIndenter}). */
    EXPANDED,

    /** Members separated by a single space, brackets padded with a space (Jackson
     * {@code FixedSpaceIndenter}); this is Jackson's default array layout. */
    FIXED_SPACE,

    /** No whitespace added between brackets and members (Jackson {@code NopIndenter}). */
    INLINE
}

package org.qubership.nifi.maven.transform.config;

import java.util.regex.Pattern;

/**
 * Mapping of a single processor property: property name (or regex) to a target filename.
 */
public final class PropertyMapping {

    private final String propertyNameOrRegex;
    private final String targetFilename;
    private final boolean isRegex;
    private final Pattern compiledPattern; // null if not a regex

    private PropertyMapping(final String propertyNameOrRegexValue, final String targetFilenameValue,
                            final boolean isRegexValue, final Pattern compiledPatternValue) {
        this.propertyNameOrRegex = propertyNameOrRegexValue;
        this.targetFilename = targetFilenameValue;
        this.isRegex = isRegexValue;
        this.compiledPattern = compiledPatternValue;
    }

    /**
     * Creates a PropertyMapping with a literal (exact) property name match.
     *
     * @param propertyName   exact property name to match
     * @param targetFilename name of the file to extract the property value into
     * @return new PropertyMapping instance
     */
    public static PropertyMapping of(String propertyName, String targetFilename) {
        return new PropertyMapping(propertyName, targetFilename, false, null);
    }

    /**
     * Creates a PropertyMapping with a regex pattern to match property names.
     * Throws {@link java.util.regex.PatternSyntaxException} if the pattern is invalid.
     *
     * @param pattern        regex pattern to match property names
     * @param targetFilename name of the file to extract the property value into
     * @return new PropertyMapping instance
     */
    public static PropertyMapping ofRegex(String pattern, String targetFilename) {
        Pattern compiled = Pattern.compile(pattern);
        return new PropertyMapping(pattern, targetFilename, true, compiled);
    }

    /**
     * Returns the property name or regex pattern string as defined in the config.
     *
     * @return property name or regex string
     */
    public String getPropertyNameOrRegex() {
        return propertyNameOrRegex;
    }

    /**
     * Returns the name of the target file for the extracted property value.
     *
     * @return target filename
     */
    public String getTargetFilename() {
        return targetFilename;
    }

    /**
     * Returns true if this mapping uses a regex pattern to match property names.
     *
     * @return true if regex, false if exact name
     */
    public boolean isRegex() {
        return isRegex;
    }

    /**
     * Returns the compiled regex pattern.
     *
     * @return compiled Pattern
     * @throws IllegalStateException if this mapping is not a regex
     */
    public Pattern getCompiledPattern() {
        if (!isRegex) {
            throw new IllegalStateException(
                    "PropertyMapping '" + propertyNameOrRegex + "' is not a regex");
        }
        return compiledPattern;
    }

}

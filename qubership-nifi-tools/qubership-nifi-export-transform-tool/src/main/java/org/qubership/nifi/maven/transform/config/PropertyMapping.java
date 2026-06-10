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
     * Creates a PropertyMapping from a property name or regex pattern and a target filename.
     *
     * @param propertyNameOrRegex property name or regex pattern
     * @param targetFilename      name of the file to extract the property value into
     * @return new PropertyMapping instance
     * @throws PatternSyntaxException if the string looks like a regex but has invalid syntax
     */
    public static PropertyMapping of(String propertyNameOrRegex, String targetFilename) {
        boolean isRegex = looksLikeRegex(propertyNameOrRegex);
        Pattern compiled = isRegex ? Pattern.compile(propertyNameOrRegex) : null;
        return new PropertyMapping(propertyNameOrRegex, targetFilename, isRegex, compiled);
    }

    /**
     * Returns true if the given property name matches this mapping.
     *
     * @param propertyName property name to test
     * @return true if the name matches
     */
    public boolean matches(String propertyName) {
        if (isRegex) {
            return compiledPattern.matcher(propertyName).matches();
        }
        return propertyNameOrRegex.equals(propertyName);
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

    /**
     * Determines whether the given string should be treated as a regex
     * by checking for the presence of regex special characters.
     *
     * @param value string to test
     * @return true if the string contains regex special characters
     */
    private static boolean looksLikeRegex(final String value) {
        return value.chars().anyMatch(c ->
                c == '.' || c == '*' || c == '+' || c == '?'
                        || c == '(' || c == ')' || c == '[' || c == ']'
                        || c == '{' || c == '}' || c == '^' || c == '$'
                        || c == '|' || c == '\\'
        );
    }
}

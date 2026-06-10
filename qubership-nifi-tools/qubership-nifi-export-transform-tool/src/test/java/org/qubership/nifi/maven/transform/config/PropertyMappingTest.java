package org.qubership.nifi.maven.transform.config;

import org.junit.jupiter.api.Test;

import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyMappingTest {

    @Test
    void exactNameNoRegexCharsIsNotRegex() {
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");

        assertFalse(mapping.isRegex());
        assertEquals("SQL Query", mapping.getPropertyNameOrRegex());
        assertEquals("query.sql", mapping.getTargetFilename());
    }

    @Test
    void exactNameMatchesReturnsTrue() {
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");

        assertTrue(mapping.matches("SQL Query"));
    }

    @Test
    void exactNameDoesNotMatchDifferentNameReturnsFalse() {
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");

        assertFalse(mapping.matches("sql query"));
        assertFalse(mapping.matches("SQL"));
        assertFalse(mapping.matches(""));
    }

    @Test
    void regexPatternWithPipeAndParensIsRegex() {
        PropertyMapping mapping = PropertyMapping.of("(SQL Query|db-fetch-sql-query)", "query.sql");

        assertTrue(mapping.isRegex());
    }

    @Test
    void regexPatternMatchesEitherAlternative() {
        PropertyMapping mapping = PropertyMapping.of("(SQL Query|db-fetch-sql-query)", "query.sql");

        assertTrue(mapping.matches("SQL Query"));
        assertTrue(mapping.matches("db-fetch-sql-query"));
        assertFalse(mapping.matches("other"));
    }

    @Test
    void regexPatternDotStarMatchesAnyString() {
        PropertyMapping mapping = PropertyMapping.of("Script.*", "script.groovy");

        assertTrue(mapping.matches("Script Body"));
        assertTrue(mapping.matches("Script File"));
        assertFalse(mapping.matches("script body"));
    }

    @Test
    void regexPatternGetCompiledPatternReturnsPattern() {
        PropertyMapping mapping = PropertyMapping.of("(a|b)", "file.txt");

        assertNotNull(mapping.getCompiledPattern());
        assertEquals("(a|b)", mapping.getCompiledPattern().pattern());
    }

    @Test
    void exactNameGetCompiledPatternThrowsIllegalStateException() {
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");

        IllegalStateException e = assertThrows(IllegalStateException.class, mapping::getCompiledPattern);
        assertTrue(e.getMessage().contains("SQL Query"));
    }

    @Test
    void invalidRegexThrowsPatternSyntaxException() {
        assertThrows(PatternSyntaxException.class, () -> PropertyMapping.of("[invalid", "file.txt"));
    }

    @Test
    void specialCharsRecognizedAsRegex() {
        assertTrue(PropertyMapping.of("a.b", "f.txt").isRegex());
        assertTrue(PropertyMapping.of("a*b", "f.txt").isRegex());
        assertTrue(PropertyMapping.of("a+b", "f.txt").isRegex());
        assertTrue(PropertyMapping.of("a?b", "f.txt").isRegex());
        assertTrue(PropertyMapping.of("a^b", "f.txt").isRegex());
        assertTrue(PropertyMapping.of("a$b", "f.txt").isRegex());
        assertTrue(PropertyMapping.of("a|b", "f.txt").isRegex());
    }

    @Test
    void hyphenAndSpaceNotRegex() {
        assertFalse(PropertyMapping.of("db-fetch-sql-query", "query.sql").isRegex());
        assertFalse(PropertyMapping.of("SQL Query", "query.sql").isRegex());
    }
}

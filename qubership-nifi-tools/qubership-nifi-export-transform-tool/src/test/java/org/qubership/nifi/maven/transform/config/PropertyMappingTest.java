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
    void ofAlwaysCreatesLiteralMappingEvenWithSpecialChars() {
        assertFalse(PropertyMapping.of("a.b", "f.txt").isRegex());
        assertFalse(PropertyMapping.of("a*b", "f.txt").isRegex());
        assertFalse(PropertyMapping.of("sql.args.1.value", "f.txt").isRegex());
        assertFalse(PropertyMapping.of("a|b", "f.txt").isRegex());
        assertFalse(PropertyMapping.of("db-fetch-sql-query", "query.sql").isRegex());
        assertFalse(PropertyMapping.of("SQL Query", "query.sql").isRegex());
    }

    @Test
    void ofRegexCreatesRegexMapping() {
        PropertyMapping mapping = PropertyMapping.ofRegex("SQL Query|db-fetch-sql-query", "query.sql");

        assertTrue(mapping.isRegex());
        assertEquals("SQL Query|db-fetch-sql-query", mapping.getPropertyNameOrRegex());
    }

    @Test
    void ofRegexGetCompiledPatternReturnsPattern() {
        PropertyMapping mapping = PropertyMapping.ofRegex("(a|b)", "file.txt");

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
    void ofRegexInvalidPatternThrowsPatternSyntaxException() {
        assertThrows(PatternSyntaxException.class,
                () -> PropertyMapping.ofRegex("[invalid", "file.txt"));
    }
}

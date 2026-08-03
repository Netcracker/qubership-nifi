package org.qubership.nifi.maven.flowdiff.report;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ValueFormatter}: control-character escaping, truncation with the dropped-count marker, and the
 * truncation-disabling behavior of a zero budget.
 */
class ValueFormatterTest {

    @Test
    void escapesControlCharacters() {
        assertEquals("a\\nb\\rc\\td", ValueFormatter.format(new TextNode("a\nb\rc\td"), 0));
    }

    @Test
    void truncatesAfterBudgetWithDroppedCount() {
        String result = ValueFormatter.format(new TextNode("abcdefghij"), 4);
        assertEquals("abcd...(+6 chars)", result);
    }

    @Test
    void zeroBudgetDisablesTruncation() {
        String value = "0123456789012345678901234567890123456789";
        assertEquals(value, ValueFormatter.format(new TextNode(value), 0));
    }

    @Test
    void countsEscapedLengthWhenTruncating() {
        String result = ValueFormatter.format(new TextNode("\n\n\n\n\n"), 4);
        assertEquals("\\n\\n...(+6 chars)", result);
    }

    @Test
    void rendersMissingAsAbsent() {
        assertEquals("(absent)", ValueFormatter.format(null, 0));
    }

    @Test
    void rendersNumberViaText() {
        assertEquals("42", ValueFormatter.format(new IntNode(42), 0));
    }
}

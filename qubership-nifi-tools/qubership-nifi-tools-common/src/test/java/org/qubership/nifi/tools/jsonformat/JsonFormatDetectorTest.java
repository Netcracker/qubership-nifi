package org.qubership.nifi.tools.jsonformat;

import com.fasterxml.jackson.core.util.Separators;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFormatDetectorTest {

    @Test
    void detectsTwoSpaceExpandedLfFormat() {
        JsonFormat format = JsonFormatDetector.detect("{\n  \"a\": 1,\n  \"b\": 2\n}");
        assertThat(format.indent()).isEqualTo("  ");
        assertThat(format.eol()).isEqualTo("\n");
        assertThat(format.objectStyle()).isEqualTo(ContainerStyle.EXPANDED);
        assertThat(format.objectColonSpacing()).isEqualTo(Separators.Spacing.AFTER);
        assertThat(format.trailingNewline()).isFalse();
    }

    @Test
    void detectsFourSpaceIndent() {
        JsonFormat format = JsonFormatDetector.detect("{\n    \"a\": 1\n}");
        assertThat(format.indent()).isEqualTo("    ");
    }

    @Test
    void detectsTabIndent() {
        JsonFormat format = JsonFormatDetector.detect("{\n\t\"a\": 1\n}");
        assertThat(format.indent()).isEqualTo("\t");
    }

    @Test
    void detectsCrlfLineEndings() {
        JsonFormat format = JsonFormatDetector.detect("{\r\n  \"a\": 1\r\n}");
        assertThat(format.eol()).isEqualTo("\r\n");
    }

    @Test
    void detectsCompactInlineFormat() {
        JsonFormat format = JsonFormatDetector.detect("{\"a\":1,\"b\":[1,2]}");
        assertThat(format.objectStyle()).isEqualTo(ContainerStyle.INLINE);
        assertThat(format.arrayStyle()).isEqualTo(ContainerStyle.INLINE);
        assertThat(format.objectColonSpacing()).isEqualTo(Separators.Spacing.NONE);
        assertThat(format.objectEntrySpacing()).isEqualTo(Separators.Spacing.NONE);
        assertThat(format.arrayElementSpacing()).isEqualTo(Separators.Spacing.NONE);
    }

    @Test
    void detectsJacksonDefaultFixedSpaceArrays() {
        // Jackson's default pretty printer: expanded objects, fixed-space arrays, colon padded both sides.
        JsonFormat format = JsonFormatDetector.detect("{\n  \"a\" : [ 1, 2 ],\n  \"b\" : [ \"x\" ]\n}");
        assertThat(format.objectStyle()).isEqualTo(ContainerStyle.EXPANDED);
        assertThat(format.arrayStyle()).isEqualTo(ContainerStyle.FIXED_SPACE);
        assertThat(format.objectColonSpacing()).isEqualTo(Separators.Spacing.BOTH);
    }

    @Test
    void detectsSpacedColon() {
        JsonFormat format = JsonFormatDetector.detect("{\"a\" : 1}");
        assertThat(format.objectColonSpacing()).isEqualTo(Separators.Spacing.BOTH);
    }

    @Test
    void detectsSpaceAfterInlineComma() {
        JsonFormat format = JsonFormatDetector.detect("{\"a\": 1, \"b\": 2}");
        assertThat(format.objectEntrySpacing()).isEqualTo(Separators.Spacing.AFTER);
    }

    @Test
    void detectsEmptyContainerSeparators() {
        JsonFormat compact = JsonFormatDetector.detect("{\"a\":{},\"b\":[]}");
        assertThat(compact.objectEmptySeparator()).isEqualTo("");
        assertThat(compact.arrayEmptySeparator()).isEqualTo("");

        JsonFormat spaced = JsonFormatDetector.detect("{\"a\":{ },\"b\":[ ]}");
        assertThat(spaced.objectEmptySeparator()).isEqualTo(" ");
        assertThat(spaced.arrayEmptySeparator()).isEqualTo(" ");
    }

    @Test
    void detectsTrailingNewline() {
        assertThat(JsonFormatDetector.detect("{\"a\":1}\n").trailingNewline()).isTrue();
        assertThat(JsonFormatDetector.detect("{\"a\":1}").trailingNewline()).isFalse();
    }

    @Test
    void picksDominantIndentWhenMixed() {
        // Three two-space lines versus one four-space line: two-space wins.
        String json = "{\n  \"a\": 1,\n  \"b\": 2,\n  \"c\": {\n      \"d\": 3\n  }\n}";
        JsonFormat format = JsonFormatDetector.detect(json);
        assertThat(format.indent()).isEqualTo("  ");
    }
}

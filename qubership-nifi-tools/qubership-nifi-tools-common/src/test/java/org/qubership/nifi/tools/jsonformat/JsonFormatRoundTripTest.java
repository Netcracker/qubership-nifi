package org.qubership.nifi.tools.jsonformat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFormatRoundTripTest {

    private final JsonFormatReformatter reformatter = new JsonFormatReformatter();

    static Stream<Arguments> stableSamples() {
        String twoSpace = "{\n  \"a\": 1,\n  \"b\": [\n    1,\n    2\n  ]\n}";
        String fourSpace = "{\n    \"a\": 1,\n    \"b\": [\n        1,\n        2\n    ]\n}";
        String tabIndent = "{\n\t\"a\": 1,\n\t\"b\": [\n\t\t1,\n\t\t2\n\t]\n}";
        String crlf = twoSpace.replace("\n", "\r\n");
        String compact = "{\"a\":1,\"b\":[1,2],\"c\":\"x\"}";
        String inlineSpacedColon = "{\"a\" : 1,\"b\" : 2}";
        String inlineSpacedComma = "{\"a\": 1, \"b\": 2}";
        String emptyContainers = "{\"a\":{},\"b\":[]}";
        String emptyContainersSpaced = "{\"a\":{ },\"b\":[ ]}";
        String trailingNewline = twoSpace + "\n";
        String stringWithStructuralChars = "{\n  \"a\": \"{not:a,brace}\",\n  \"b\": \"[1,2]\"\n}";
        // Jackson's default pretty printer: expanded objects, fixed-space arrays, colon padded both sides.
        String jacksonDefault = "{\n  \"a\" : [ 1, 2 ],\n  \"b\" : [ {\n    \"c\" : \"x\"\n  } ],\n  \"d\" : [ ]\n}";

        return Stream.of(
                Arguments.of("two-space expanded", twoSpace),
                Arguments.of("four-space expanded", fourSpace),
                Arguments.of("tab expanded", tabIndent),
                Arguments.of("CRLF expanded", crlf),
                Arguments.of("compact inline", compact),
                Arguments.of("inline spaced colon", inlineSpacedColon),
                Arguments.of("inline spaced comma", inlineSpacedComma),
                Arguments.of("empty containers", emptyContainers),
                Arguments.of("empty containers spaced", emptyContainersSpaced),
                Arguments.of("trailing newline", trailingNewline),
                Arguments.of("structural chars inside strings", stringWithStructuralChars),
                Arguments.of("jackson default fixed-space arrays", jacksonDefault));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stableSamples")
    void reproducesFormatting(String name, String json) throws Exception {
        assertThat(reformatter.reformat(json)).isEqualTo(json);
    }
}

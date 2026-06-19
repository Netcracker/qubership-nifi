package org.qubership.nifi.tools.jsonformat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFormatReformatterTest {

    private final JsonFormatReformatter reformatter = new JsonFormatReformatter();

    @Test
    void reformatFilePreservesFormatting() throws IOException {
        String json = "{\n  \"a\": 1,\n  \"b\": [\n    1,\n    2\n  ]\n}\n";
        Path input = Files.createTempFile("input", ".json");
        Path output = Files.createTempFile("output", ".json");
        try {
            Files.writeString(input, json, StandardCharsets.UTF_8);
            reformatter.reformatFile(input, output);
            assertThat(Files.readString(output, StandardCharsets.UTF_8)).isEqualTo(json);
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    @Test
    void keepsFormattingAfterTreeMutation() throws IOException {
        String json = "{\n  \"a\": 1,\n  \"b\": 2\n}";
        ObjectMapper mapper = JsonMapper.builder().build();

        JsonFormat format = reformatter.detect(json);
        ObjectNode tree = (ObjectNode) mapper.readTree(json);
        tree.put("b", 99);

        String expected = "{\n  \"a\": 1,\n  \"b\": 99\n}";
        assertThat(reformatter.write(tree, format)).isEqualTo(expected);
    }

    @Test
    void documentedLimitationScalarsAreNormalized() throws IOException {
        // Jackson normalizes scalar literals; only structure and whitespace are guaranteed.
        String json = "{\n  \"a\": 1.50\n}";
        JsonNode tree = JsonMapper.builder().build().readTree(json);
        assertThat(tree.get("a").asText()).isEqualTo("1.5");
        assertThat(reformatter.reformat(json)).isEqualTo("{\n  \"a\": 1.5\n}");
    }
}

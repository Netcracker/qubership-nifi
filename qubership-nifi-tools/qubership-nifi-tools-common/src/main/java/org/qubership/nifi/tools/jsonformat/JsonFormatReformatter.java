package org.qubership.nifi.tools.jsonformat;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Re-serializes JSON while preserving the original document's formatting.
 *
 * <p>The typical flow is: detect the input's formatting, parse it into a Jackson tree, optionally
 * mutate the tree, then write it back using a pretty printer derived from the detected format.
 * This keeps the output's formatting identical to the input's.</p>
 */
public final class JsonFormatReformatter {

    private final ObjectMapper mapper;

    /**
     * Creates a reformatter with a default Jackson {@link ObjectMapper}.
     */
    public JsonFormatReformatter() {
        this(JsonMapper.builder().build());
    }

    /**
     * Creates a reformatter using the given mapper for parsing and writing.
     *
     * @param mapperValue the Jackson mapper to use
     */
    public JsonFormatReformatter(final ObjectMapper mapperValue) {
        this.mapper = mapperValue;
    }

    /**
     * Detects the formatting of the given JSON text.
     *
     * @param json the JSON text to inspect
     * @return the detected formatting
     */
    public JsonFormat detect(final String json) {
        return JsonFormatDetector.detect(json);
    }

    /**
     * Reformats a JSON string, reproducing its own detected formatting after a parse/serialize
     * round trip.
     *
     * @param json the input JSON text
     * @return the reformatted JSON text
     * @throws IOException if the input is not valid JSON
     */
    public String reformat(final String json) throws IOException {
        return write(mapper.readTree(json), JsonFormatDetector.detect(json));
    }

    /**
     * Serializes a JSON tree using the formatting described by {@code format}.
     *
     * @param tree the JSON tree to serialize
     * @param format the formatting to reproduce
     * @return the serialized JSON text
     * @throws IOException if serialization fails
     */
    public String write(final JsonNode tree, final JsonFormat format) throws IOException {
        DefaultPrettyPrinter printer = JsonFormatPrettyPrinters.from(format);
        ObjectWriter writer = mapper.writer(printer);
        String body = writer.writeValueAsString(tree);
        if (format.trailingNewline()) {
            return body + format.eol();
        }
        return body;
    }

    /**
     * Reads {@code input}, reformats it to match its own formatting, and writes the result to
     * {@code output}. Both files are read and written as UTF-8.
     *
     * @param input path to the input JSON file
     * @param output path to the output JSON file
     * @throws IOException if reading, parsing, or writing fails
     */
    public void reformatFile(final Path input, final Path output) throws IOException {
        String content = Files.readString(input, StandardCharsets.UTF_8);
        String result = reformat(content);
        Files.write(output, result.getBytes(StandardCharsets.UTF_8));
    }
}

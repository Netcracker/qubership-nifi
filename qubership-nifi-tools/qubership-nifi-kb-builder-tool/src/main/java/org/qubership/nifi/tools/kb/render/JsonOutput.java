/*
 * Copyright 2020-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.nifi.tools.kb.render;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Serializes JSON deterministically: UTF-8, LF line endings, two-space indentation, a conventional
 * {@code "key": value} field separator, and a trailing newline. Source field order is preserved
 * because Jackson object nodes retain insertion order.
 */
public final class JsonOutput {

    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    /**
     * Creates a JSON output helper with a private mapper.
     */
    public JsonOutput() {
        this(new ObjectMapper());
    }

    /**
     * Creates a JSON output helper backed by the given mapper.
     *
     * @param objectMapper the mapper
     */
    public JsonOutput(final ObjectMapper objectMapper) {
        this.mapper = objectMapper;
        final DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        final Separators separators = Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER);
        final DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(separators);
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        this.writer = objectMapper.writer(printer);
    }

    /**
     * Returns the backing mapper for building nodes.
     *
     * @return the mapper
     */
    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Serializes a JSON tree to UTF-8 bytes with a trailing newline.
     *
     * @param node the JSON tree
     * @return the serialized bytes
     */
    public byte[] toBytes(final JsonNode node) {
        try {
            final String text = writer.writeValueAsString(node);
            return (text + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to serialize JSON", e);
        }
    }
}

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

package org.qubership.nifi.processors.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.UncheckedIOException;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the {@code _errors} array that {@link JsonValidationHandler} adds to a document with an expected array.
 *
 * <p>The order of the errors is ignored, because the schema library reports them as a set, but the number of times
 * each error occurs is not. The fields inside each error are compared exactly.</p>
 */
public final class ValidationErrorAssertions {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ValidationErrorAssertions() {
    }

    /**
     * Asserts that {@code actualErrors} is an array holding the same errors as {@code expectedErrorsJson}, in any
     * order.
     *
     * @param expectedErrorsJson the expected {@code _errors} array as JSON text
     * @param actualErrors the {@code _errors} node of the validated document, or {@code null} if it has none
     */
    public static void assertErrors(String expectedErrorsJson, JsonNode actualErrors) {
        if (actualErrors != null) {
            assertTrue(actualErrors.isArray(), () -> "_errors is an array, got " + actualErrors);
        }
        assertEquals(counted(readTree(expectedErrorsJson)), counted(actualErrors),
                "_errors, each error with its number of occurrences, in any order");
    }

    /**
     * Parses JSON text written in a test or produced by the processor.
     *
     * @param json JSON text
     * @return the parsed tree
     * @throws UncheckedIOException if the text is not JSON or has anything but whitespace after the first value
     */
    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<JsonNode, Long> counted(JsonNode errors) {
        if (errors == null) {
            return null;
        }
        return StreamSupport.stream(errors.spliterator(), false)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}

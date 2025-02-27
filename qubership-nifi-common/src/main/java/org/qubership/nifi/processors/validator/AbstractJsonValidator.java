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

package org.qubership.nifi.processors.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.ValidationResult.Builder;
import org.apache.nifi.components.Validator;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Abstract NiFi component validator for properties containing JSON.
 * @param <T> defines type of output value, e.g. JsonNode.
 */
@AllArgsConstructor
public abstract class AbstractJsonValidator<T> implements Validator {

    @Getter
    private final ObjectMapper mapper;

    @Getter
    private final boolean allowEmpty;

    @Override
    public ValidationResult validate(String subject, String input, ValidationContext context) {
        Builder builder = new Builder().subject(subject);

        String json = input;
        if (context.isExpressionLanguagePresent(input)) {
            try {
                json = context.newPropertyValue(input).evaluateAttributeExpressions().getValue();
            } catch (final Exception e) {
                return builder.valid(false).
                    explanation("Failed to evaluate Expression Language due to " + e.toString()).
                    build();
            }
        }

        if (allowEmpty && (json == null || "".equals(json))) {
            return builder.valid(true).explanation("JSON is valid.").build();
        }
        try {
            List<String> validationMessages = validate(convert(json));
            if (!validationMessages.isEmpty()) {
                return builder.valid(false).explanation(String.valueOf(validationMessages)).build();
            }
        } catch (IOException e) {
            String explanation = e.getMessage();
            if (explanation != null && explanation.contains("at ")) {
                explanation = explanation.split("at ")[0];
            }
            return builder.valid(false).explanation(explanation).build();
        } catch (Exception e) {
            return builder.valid(false).explanation(e.getMessage()).build();
        }

        return builder.valid(true).explanation("JSON is valid.").build();
    }

    /**
     * Converts JSON string into JSON object.
     * @param json JSON string from property
     * @return parsed JSON object
     * @throws IOException
     */
    protected abstract T convert(String json) throws IOException;

    /**
     * Validates JSON object and returns a list of errors
     * @param obj input JSON object
     * @return list of errors
     */
    protected List<String> validate(T obj) {
        return Collections.emptyList();
    }
}
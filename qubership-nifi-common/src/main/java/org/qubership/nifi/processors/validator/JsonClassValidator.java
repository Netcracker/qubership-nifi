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

import java.io.IOException;

/**
 * Abstract NiFi component validator for properties containing JSON.
 * @param <T> defines type of output value, e.g. JsonNode.
 */
public class JsonClassValidator<T> extends AbstractJsonValidator<T> {

    private final Class<T> clazz;

    /**
     * Create new instance of JsonClassValidator
     * @param mapper ObjectMapper to use
     * @param clazz JSON object class type
     */
    public JsonClassValidator(ObjectMapper mapper, Class<T> clazz) {
        super(mapper, false);
        this.clazz = clazz;
    }

    @Override
    protected T convert(String json) throws IOException {
        return getMapper().readValue(json, clazz);
    }
}

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Json Tree component validator for properties containing JSON. Uses JsonTree to parse JSON.
 */
public class JsonTreeValidator extends AbstractJsonValidator<JsonNode> {

    /**
     * Create instance of JsonTreeValidator
     * @param mapper ObjectMapper to use
     */
    public JsonTreeValidator(ObjectMapper mapper) {
        super(mapper, false);
    }

    /**
     * Create instance of JsonTreeValidator
     * @param mapper ObjectMapper to use
     * @param allowEmpty if true, treats empty values as valid
     */
    public JsonTreeValidator(ObjectMapper mapper, boolean allowEmpty) {
        super(mapper, allowEmpty);
    }

    @Override
    protected JsonNode convert(String json) throws IOException {
        return getMapper().readTree(json);
    }
}

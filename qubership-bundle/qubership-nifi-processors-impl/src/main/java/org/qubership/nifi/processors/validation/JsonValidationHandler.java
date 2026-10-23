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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.exception.ProcessException;
import org.qubership.nifi.processors.json.JsonPathHelper;


import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class JsonValidationHandler {

    private static final String DEFAULT_BE_TYPE_VALUE = "object";
    private static final String DEFAULT_SOURCE_ID_VALUE = "";

    private static final String TOP_DELIMITER = "'";
    private static final String JSON_PATH_DELIMITER = ".";
    private static final String KEY_SOURCE_ATTR = "source";
    private static final String KEY_VALIDATION_ERROR_PASS = "path";
    private static final String KEY_ERROR_CODE = "code";
    private static final String KEY_TITLE = "title";
    private static final String KEY_DETAIL = "details";
    private static final String KEY_ERROR_ARRAY = "_errors";

    private static final String ROOT_OBJECT_IN_JSON_PATH = "$.";

    // Keywords whose error is located at the parent object, with the missing or extra property reported separately.
    // The error path includes that property. A dependencies error also reports a property, the one that triggered
    // the check, and its path stays at the parent object.
    private static final Set<String> PROPERTY_PATH_KEYWORDS = Set.of("required", "additionalProperties");

    private JsonSchema jsonSchema;
    private JsonNode originalJsonNode;
    private ProcessorProperty property;
    private ComponentLog logger;

    public JsonValidationHandler(JsonNode originalJsonNode, ProcessorProperty property, ComponentLog componentLog) {
        this.jsonSchema = property.getSchema();
        this.originalJsonNode = originalJsonNode;
        this.property = property;
        this.logger = componentLog;
    }

    public ValidationContext process() {
        Set<ValidationMessage> errors = jsonSchema.validate(originalJsonNode);
        if (errors.isEmpty()) {
            return new ValidationContext(originalJsonNode, true);
        }

        ArrayNode errorArray = JsonNodeFactory.instance.arrayNode();
        for (ValidationMessage error : errors) {
            JsonNode errorJson = createErrorJson(error);
            errorArray.add(errorJson);
        }

        if (originalJsonNode.isObject()) {
            JsonNode validatedJson = addErrorArrayToJson(errorArray, (ObjectNode) this.originalJsonNode);
            return new ValidationContext(validatedJson, false);

        } else {
            throw new ProcessException("Can not cast JsonNode to JsonObject");
        }
    }

    private JsonNode createErrorJson(ValidationMessage error) {
        StringBuilder titleBuilder = new StringBuilder();
        String path = extractPath(error);
        String shortPath = extractShortPath(path);

        String beType = extractBeTypeValue();

        titleBuilder
                .append("Validation error ").append(TOP_DELIMITER).append(error.getType()).append(TOP_DELIMITER)
                .append(" in ").append(TOP_DELIMITER).append(beType).append(TOP_DELIMITER).append(" Business Entity ")
                .append(TOP_DELIMITER).append(shortPath).append(TOP_DELIMITER);

        ObjectNode sourceAttr = property.getRegex() == null ? defaultSourceNode(path) : unwrappedSourceNode(path);


        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node
                .put(KEY_ERROR_CODE, property.getErrorCode())
                .put(KEY_TITLE, titleBuilder.toString())
                .put(KEY_DETAIL, error.getMessage() == null ? null : error.getMessage().replace(ROOT_OBJECT_IN_JSON_PATH, ""));

        node.set(KEY_SOURCE_ATTR, sourceAttr);
        return node;

    }

    private ObjectNode unwrappedSourceNode(String path) {
        Pattern regex = property.getRegex();
        Matcher matcher = regex.matcher(path);
        if (!matcher.find()) {
            logger.warn("No matches found for '{}' regex in {}", new Object[]{property.getRegex(), path});
            return defaultSourceNode(path);
        }

        try {
            String wrapperPath = matcher.group();
            path = path.replace(wrapperPath, "");
            DocumentContext jsonPath = JsonPath.parse(this.originalJsonNode, JsonPathHelper.DEFAULT_JACKSON_CONFIGURATION);
            TextNode instanceId = jsonPath.read(wrapperPath + this.property.getSourceIdAttrName());

            return createSourceNode(path, instanceId.asText());
        } catch (Exception e) {
            logger.warn(e.getMessage());
            return defaultSourceNode(path);
        }
    }


    private ObjectNode defaultSourceNode(String path) {
        path = path.replace(ROOT_OBJECT_IN_JSON_PATH, "");
        return createSourceNode(path, extractSourceIdValue());
    }

    private ObjectNode createSourceNode(String path, String sourceId) {
        return JsonNodeFactory.instance.objectNode()
                .put(property.getSourceIdAttrName(), sourceId)
                .put(KEY_VALIDATION_ERROR_PASS, path);

    }

    private String extractPath(ValidationMessage error) {
        String path = error.getInstanceLocation().toString();
        if (PROPERTY_PATH_KEYWORDS.contains(error.getType()) && error.getProperty() != null) {
            return path + JSON_PATH_DELIMITER + error.getProperty();
        }
        return path;
    }

    private JsonNode addErrorArrayToJson(ArrayNode errorArray, ObjectNode objectNode) {
        objectNode.set(KEY_ERROR_ARRAY, errorArray);
        return objectNode;
    }

    private String extractShortPath(String path) {
        String[] strings = path.split(Pattern.quote(JSON_PATH_DELIMITER));
        int deepLevel = strings.length;
        String shortPath;
        if (deepLevel < 2) {
            shortPath = path;
        } else {
            shortPath = strings[deepLevel - 2] + JSON_PATH_DELIMITER + strings[deepLevel - 1];
        }
        return format(shortPath);
    }

    private String format(String shortPath) {
        if (shortPath.contains("[") && shortPath.contains("]")) {
            StringBuilder sb = new StringBuilder(shortPath);
            shortPath = sb.delete(sb.indexOf("["), sb.indexOf("]") + 1).toString();
        }
        return shortPath;
    }

    private String extractBeTypeValue() {
        JsonNode beType = this.originalJsonNode.get(property.getBeTypeAttrName());
        if (beType != null && beType.isValueNode()) return beType.asText();
        else {
            logger.warn("Can't get business entity type value by $.{}", new Object[]{property.getBeTypeAttrName()});
            return DEFAULT_BE_TYPE_VALUE;
        }
    }

    private String extractSourceIdValue() {
        JsonNode sourceId = this.originalJsonNode.get(property.getSourceIdAttrName());
        if (sourceId != null && sourceId.isValueNode()) return sourceId.asText();
        else {
            logger.warn("Can't get sourceId value by $.{}", new Object[]{property.getSourceIdAttrName()});
            return DEFAULT_SOURCE_ID_VALUE;
        }
    }
}

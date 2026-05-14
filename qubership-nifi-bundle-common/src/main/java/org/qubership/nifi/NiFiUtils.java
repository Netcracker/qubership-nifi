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

package org.qubership.nifi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utilities class to handle common IO operations with NiFi FlowFiles.
 */
public final class NiFiUtils {
    /**
     * Object Mapper to use for JSON deserialization.
     */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private NiFiUtils() {
    }

    /**
     * Read FlowFile content and parse it as JsonNode.
     * @param session process session
     * @param inputFlowFile input FlowFile
     * @return JsonNode with parsed content
     */
    public static JsonNode readJsonNodeFromFlowFile(
            ProcessSession session,
            FlowFile inputFlowFile
    ) {
        AtomicReference<JsonNode> result = new AtomicReference<>();
        session.read(inputFlowFile, in -> result.set(MAPPER.readTree(in)));

        return result.get();
    }

    /**
     * Read FlowFile content and parse it as JSON with specified class type.
     * @param session process session
     * @param inputFlowFile input FlowFile
     * @param clazz target class
     * @return parsed content
     * @param <T> type of target class
     */
    public static <T> T readJsonNodeFromFlowFile(
            ProcessSession session,
            FlowFile inputFlowFile,
            Class<T> clazz
    ) {
        AtomicReference<T> result = new AtomicReference<>();
        session.read(inputFlowFile, in -> result.set(MAPPER.readValue(in, clazz)));

        return result.get();
    }

    /**
     * Read FlowFile content and parse it as JSON with specified type reference.
     * @param session process session
     * @param inputFlowFile input FlowFile
     * @param typeReference type reference to use in JSON parsing
     * @return parsed content
     * @param <T> parameter for type reference
     */
    public static <T> T readJsonNodeFromFlowFile(
            ProcessSession session,
            FlowFile inputFlowFile,
            TypeReference<T> typeReference
    ) {
        AtomicReference<T> result = new AtomicReference<>();
        session.read(inputFlowFile, in -> result.set(MAPPER.readValue(in, typeReference)));

        return result.get();
    }

    /**
     * Read FlowFile content as UTF-8 encoded String.
     * @param session process session
     * @param flowFile input FlowFile
     * @return content as a String
     */
    public static String extractContent(
            ProcessSession session,
            FlowFile flowFile
    ) {
        try (InputStream flowStream = session.read(flowFile)) {
            return IOUtils.toString(flowStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ProcessException("Can't extract content form the FlowFile", e);
        }
    }

    /**
     * Gets specified property value and evaluates attribute expressions for it using input FlowFile.
     * @param descriptor property descriptor
     * @param context process context
     * @param flowFile input FlowFile
     * @return calculated property value
     */
    public static String getEvaluatedValue(PropertyDescriptor descriptor, ProcessContext context, FlowFile flowFile) {
        return context
                .getProperty(descriptor)
                .evaluateAttributeExpressions(flowFile)
                .getValue();
    }

    /**
     * Gets specified property value and evaluates attribute expressions for it.
     * @param descriptor property descriptor
     * @param context process context
     * @return calculated property value
     */
    public static String getEvaluatedValue(PropertyDescriptor descriptor, ProcessContext context) {
        return context
                .getProperty(descriptor)
                .evaluateAttributeExpressions()
                .getValue();
    }

    /**
     * Creates FlowFile with specified attributes and content as array of source_ids.
     * @param session process session
     * @param attributes map with FlowFile attributes
     * @param sourceIdCollection sourceId collection
     * @return created FlowFile
     */
    public static FlowFile createFlowFileInListFormat(ProcessSession session, Map<String, String> attributes,
                                                      Collection<String> sourceIdCollection) {
        FlowFile ff = session.write(session.create(),
                out -> MAPPER.writeValue(
                        out,
                        transformToListFormat(sourceIdCollection)
                ));

        return session.putAllAttributes(ff, attributes);
    }

    private static JsonNode transformToListFormat(Collection<String> sourceIdSet) {
        ArrayNode arrayNode = MAPPER.createArrayNode();
        sourceIdSet.forEach(sourceId -> arrayNode.add(
                MAPPER.createObjectNode().put("source_id", sourceId)));
        return arrayNode;
    }
}

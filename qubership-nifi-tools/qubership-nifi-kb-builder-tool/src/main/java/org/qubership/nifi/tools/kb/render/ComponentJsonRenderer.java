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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.nifi.tools.kb.model.AdditionalDocumentationState;
import org.qubership.nifi.tools.kb.model.ComponentRecord;
import org.qubership.nifi.tools.kb.model.KnowledgeBaseFormat;

/**
 * Renders a component's lossless {@code component.json}. The record has exactly three top-level
 * objects: {@code documentedType}, {@code definition}, and the derived {@code additionalDocumentation}.
 * The retained source trees are written without filtering unknown fields.
 */
public final class ComponentJsonRenderer {

    private final JsonOutput json;

    /**
     * Creates a new renderer.
     *
     * @param jsonOutput the deterministic JSON output helper
     */
    public ComponentJsonRenderer(final JsonOutput jsonOutput) {
        this.json = jsonOutput;
    }

    /**
     * Renders the given component to {@code component.json} bytes.
     *
     * @param componentRecord the component record
     * @return the serialized bytes
     */
    public byte[] render(final ComponentRecord componentRecord) {
        final ObjectNode root = json.mapper().createObjectNode();
        root.set(KnowledgeBaseFormat.DOCUMENTED_TYPE_FIELD, componentRecord.documentedType());
        root.set(KnowledgeBaseFormat.DEFINITION_FIELD, componentRecord.definition());

        final AdditionalDocumentationState state = componentRecord.additionalDocumentation();
        final ObjectNode additional = root.putObject(KnowledgeBaseFormat.ADDITIONAL_DOCUMENTATION_FIELD);
        additional.put(KnowledgeBaseFormat.ADVERTISED_FIELD, state.isAdvertised());
        additional.put(KnowledgeBaseFormat.REQUESTED_FIELD, state.isRequested());
        additional.put(KnowledgeBaseFormat.AVAILABLE_FIELD, state.isAvailable());
        state.path().ifPresent(path -> additional.put("path", path));

        return json.toBytes(root);
    }
}

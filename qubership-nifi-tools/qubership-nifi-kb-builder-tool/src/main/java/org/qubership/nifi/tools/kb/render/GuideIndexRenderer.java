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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.nifi.tools.kb.model.GuideDocument;
import org.qubership.nifi.tools.kb.model.GuidesResult;
import org.qubership.nifi.tools.kb.model.KnowledgeBaseFormat;

/**
 * Renders {@code guides/index.json}: guide provenance and, for the Developer's Guide, the selected
 * heading names. The index records source URL, fetched content type, and selected headings; it does
 * not contain output-file checksums or authentication data.
 */
public final class GuideIndexRenderer {

    private final JsonOutput json;

    /**
     * Creates a new guide index renderer.
     *
     * @param jsonOutput the deterministic JSON output helper
     */
    public GuideIndexRenderer(final JsonOutput jsonOutput) {
        this.json = jsonOutput;
    }

    /**
     * Renders the guide index bytes.
     *
     * @param guides the collected guides (required mode)
     * @return the serialized bytes
     */
    public byte[] render(final GuidesResult guides) {
        final ObjectNode root = json.mapper().createObjectNode();
        final ArrayNode array = root.putArray(KnowledgeBaseFormat.GUIDES_FIELD);
        for (final GuideDocument document : guides.documents()) {
            final ObjectNode node = array.addObject();
            node.put("guide", document.type().getManifestKey());
            node.put("title", document.type().getTitle());
            node.put("sourceUrl", document.sourceUrl());
            node.put("contentType", document.contentType());
            node.put(KnowledgeBaseFormat.OUTPUT_PATH_FIELD, document.type().getOutputPath());
            final ArrayNode headings = node.putArray("selectedHeadings");
            document.selectedHeadings().forEach(headings::add);
        }
        return json.toBytes(root);
    }
}

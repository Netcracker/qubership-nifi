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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.nifi.tools.kb.model.ComponentIdentity;
import org.qubership.nifi.tools.kb.model.ComponentKindLayout;
import org.qubership.nifi.tools.kb.model.ComponentRecord;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;

import java.util.List;

/**
 * Renders the component indexes: a compact, machine-readable {@code index.json} for programmatic
 * filtering and a human-readable {@code index.md} grouped by kind and bundle.
 */
public final class IndexRenderer {

    private static final String LF = "\n";
    private static final String COMPONENT_MD = "component.md";

    private final JsonOutput json;

    /**
     * Creates a new index renderer.
     *
     * @param jsonOutput the deterministic JSON output helper
     */
    public IndexRenderer(final JsonOutput jsonOutput) {
        this.json = jsonOutput;
    }

    /**
     * Renders {@code components/index.json} bytes.
     *
     * @param sortedComponents the components in canonical order
     * @return the serialized bytes
     */
    public byte[] renderJson(final List<ComponentRecord> sortedComponents) {
        final ArrayNode array = json.mapper().createArrayNode();
        for (final ComponentRecord record : sortedComponents) {
            array.add(compactEntry(record));
        }
        return json.toBytes(array);
    }

    private ObjectNode compactEntry(final ComponentRecord record) {
        final ComponentIdentity identity = record.identity();
        final JsonNode documented = record.documentedType();
        final ObjectNode entry = json.mapper().createObjectNode();
        entry.put("kind", identity.getKind().name());
        entry.put("group", identity.getGroup());
        entry.put("artifact", identity.getArtifact());
        entry.put("version", identity.getVersion());
        entry.put("type", identity.getType());

        final ArrayNode tags = entry.putArray("tags");
        final JsonNode sourceTags = documented.get("tags");
        if (sourceTags != null && sourceTags.isArray()) {
            sourceTags.forEach(tag -> tags.add(tag.asText()));
        }
        entry.put("deprecated", documented.path("deprecationReason").asText("").isBlank()
                ? documented.path("deprecated").asBoolean(false) : true);

        final ArrayNode apis = entry.putArray("controllerServiceApis");
        final JsonNode sourceApis = documented.get("controllerServiceApis");
        if (sourceApis != null && sourceApis.isArray()) {
            sourceApis.forEach(api -> apis.add(api.has("type") ? api.get("type").asText() : api.asText()));
        }

        entry.put("path", ComponentSorting.directoryPath(identity));

        entry.put("additionalDetailsAvailable", record.additionalDocumentation().isAvailable());
        return entry;
    }

    /**
     * Renders {@code components/index.md}, grouping components by kind and bundle.
     *
     * @param sortedComponents the components in canonical order
     * @return the Markdown content
     */
    public String renderMarkdown(final List<ComponentRecord> sortedComponents) {
        final StringBuilder md = new StringBuilder();
        md.append("# Component index").append(LF).append(LF);
        for (final NiFiComponentKind kind : NiFiComponentKind.values()) {
            final List<ComponentRecord> ofKind = sortedComponents.stream()
                    .filter(record -> record.identity().getKind() == kind).toList();
            if (ofKind.isEmpty()) {
                continue;
            }
            md.append("## ").append(ComponentKindLayout.displayLabel(kind)).append(LF).append(LF);
            String currentBundle = null;
            for (final ComponentRecord record : ofKind) {
                final ComponentIdentity identity = record.identity();
                final String bundle = identity.getGroup() + ':' + identity.getArtifact()
                        + ':' + identity.getVersion();
                if (!bundle.equals(currentBundle)) {
                    md.append(LF).append("### `").append(bundle).append('`').append(LF).append(LF);
                    currentBundle = bundle;
                }
                md.append("- [").append(identity.simpleName()).append("](")
                        .append(ComponentSorting.relativePath(identity, COMPONENT_MD)).append(')').append(LF);
            }
            md.append(LF);
        }
        return md.toString();
    }
}

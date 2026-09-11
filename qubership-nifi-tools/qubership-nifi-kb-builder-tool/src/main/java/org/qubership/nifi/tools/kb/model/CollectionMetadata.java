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

package org.qubership.nifi.tools.kb.model;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;
import org.qubership.nifi.tools.nifi.common.api.NiFiTemporaryComponentSession;

import java.util.List;

/**
 * Describes the meaning and sources of the component definitions in a Knowledge Base.
 *
 * <p>The description is split by how often its parts change. A build targets one NiFi instance, so
 * the format, the source of each definition field, and the list of metadata the source cannot
 * supply are the same for every component: {@link #describe} renders them once for the manifest.
 * Only the documentation paths differ per component, and {@link #documentationSources} renders
 * those.
 */
public final class CollectionMetadata {

    /**
     * Fields a NiFi 1.x definition takes from the component's type-list entry when the component
     * instance does not report them.
     */
    public static final List<String> TYPE_LIST_FALLBACK_FIELDS = List.of("description", "tags",
            "deprecationReason", "usageRestriction", "explicitRestrictions", "controllerServiceApis");

    private static final String TYPE_LIST_API = "type-list-api";
    private static final String INSTANCE_API = "instance-api";

    private CollectionMetadata() {
        // utility class
    }

    /**
     * Describes what the definitions in a Knowledge Base mean and where their fields came from.
     *
     * <p>A consumer must read this before interpreting a normalized definition: a field that NiFi
     * 1.x cannot report is listed in {@code unavailableMetadata}, and its absence from a component
     * therefore means unknown rather than unsupported.
     *
     * @param format the definition format shared by every component in the build
     * @return the manifest collection section
     */
    public static ObjectNode describe(final DefinitionFormat format) {
        return switch (format) {
            case NATIVE_NIFI_2X -> describeNative();
            case NORMALIZED_NIFI_1X -> describeNormalized();
        };
    }

    /**
     * Records where a NiFi 1.x component's documentation was read from.
     *
     * <p>Native NiFi 2.x components have no per-component sources: their additional details arrive
     * as Markdown from the API, and the format is recorded once in the manifest.
     *
     * @param sourcePath the resolved component documentation path
     * @param additionalPath the linked additional documentation path, or {@code null} when absent
     * @return the per-component documentation sources
     */
    public static ObjectNode documentationSources(final String sourcePath, final String additionalPath) {
        final ObjectNode result = JsonNodeFactory.instance.objectNode().put("componentPath", sourcePath);
        if (additionalPath != null) {
            result.put("additionalDetailsPath", additionalPath);
        }
        return result;
    }

    private static ObjectNode describeNative() {
        final ObjectNode result = base(DefinitionFormat.NATIVE_NIFI_2X,
                "API-derived native NiFi 2.x definition and type metadata; additional details are native Markdown.");
        result.putObject("fieldSources").put("documentedType", TYPE_LIST_API).put("definition", "definition-api");
        final ObjectNode endpoints = result.putObject("definitionEndpoints");
        for (final NiFiComponentKind kind : NiFiComponentKind.values()) {
            endpoints.put(ComponentKindLayout.manifestCountField(kind), kind.getDefinitionPathPrefix());
        }
        result.putObject("documentationFormats").put("additionalDetails", "markdown");
        return result;
    }

    private static ObjectNode describeNormalized() {
        final ObjectNode result = base(DefinitionFormat.NORMALIZED_NIFI_1X,
                "API-derived descriptors, relationships, and capabilities; "
                        + "HTML-derived attributes, dynamic properties, and state/resource documentation.");
        final ObjectNode fieldSources = result.putObject("fieldSources").put("documentedType", TYPE_LIST_API)
                .put("type", TYPE_LIST_API).put("bundle", TYPE_LIST_API)
                .put("propertyDescriptors", INSTANCE_API).put("supportedRelationships", INSTANCE_API);
        for (final String field : NiFiTemporaryComponentSession.OPTIONAL_INSTANCE_FIELDS) {
            fieldSources.put(field, "instance-api-when-present");
        }
        for (final String field : TYPE_LIST_FALLBACK_FIELDS) {
            // Where the instance can also report the field, its value takes precedence over the type list.
            fieldSources.put(field, fieldSources.has(field) ? "instance-api-else-type-list-api" : TYPE_LIST_API);
        }
        for (final String field : List.of("readsAttributes", "writesAttributes", "dynamicProperties",
                "stateManagement", "systemResourceConsiderations")) {
            fieldSources.put(field, "component-html");
        }
        result.putObject("documentationFormats").put("component", "html-to-markdown")
                .put("componentOutput", "componentDocumentation.md")
                .put("additionalDetails", "html-to-markdown");
        result.withArray("unavailableMetadata").add("expression-language-scope-enum")
                .add("structured-property-resource-definitions");
        return result;
    }

    private static ObjectNode base(final DefinitionFormat format, final String summary) {
        final ObjectNode result = JsonNodeFactory.instance.objectNode()
                .put(KnowledgeBaseFormat.DEFINITION_FORMAT_FIELD, format.token())
                .put("summary", summary);
        result.putArray("unavailableMetadata");
        return result;
    }
}

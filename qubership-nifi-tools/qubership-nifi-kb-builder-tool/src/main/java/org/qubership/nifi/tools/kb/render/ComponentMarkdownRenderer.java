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
import org.qubership.nifi.tools.kb.model.AdditionalDocumentationState;
import org.qubership.nifi.tools.kb.model.ComponentIdentity;
import org.qubership.nifi.tools.kb.model.ComponentKindLayout;
import org.qubership.nifi.tools.kb.model.ComponentRecord;
import org.qubership.nifi.tools.kb.model.KnowledgeBaseFormat;

import java.util.Iterator;
import java.util.Map;

/**
 * Renders a component's searchable {@code component.md}. Sections are emitted only when the source
 * supplies the relevant data. The matching {@code component.json} remains the lossless authority;
 * fields without a dedicated Markdown section stay available there.
 */
public final class ComponentMarkdownRenderer {

    private static final String LF = "\n";

    /**
     * Renders the given component to Markdown.
     *
     * @param componentRecord the component record
     * @return the Markdown content
     */
    public String render(final ComponentRecord componentRecord) {
        final ComponentIdentity identity = componentRecord.identity();
        final JsonNode definition = componentRecord.definition();
        final JsonNode componentType = componentRecord.documentedType();
        final StringBuilder md = new StringBuilder();

        md.append("# ").append(identity.simpleName()).append(LF).append(LF);

        appendIdentity(md, identity);
        appendMetadata(md, definition, componentType);
        appendInputAndRelationships(md, definition);
        appendProperties(md, definition);
        appendControllerServiceApis(md, componentType, definition);
        appendAttributes(md, definition);
        appendLinks(md, componentRecord);

        return md.toString();
    }

    private void appendIdentity(final StringBuilder md, final ComponentIdentity identity) {
        md.append("## Identity").append(LF).append(LF);
        md.append("- Kind: ").append(ComponentKindLayout.displayLabel(identity.getKind())).append(LF);
        md.append("- Type: `").append(identity.getType()).append('`').append(LF);
        md.append("- Bundle: `").append(identity.getGroup()).append(':').append(identity.getArtifact())
                .append(':').append(identity.getVersion()).append('`').append(LF).append(LF);
    }

    private void appendMetadata(final StringBuilder md, final JsonNode definition, final JsonNode componentType) {
        if (isMissingMetadata(definition, componentType)) {
            return;
        }
        appendDescriptionAndTags(md, definition, componentType);
        appendDeprecationAndRestrictions(md, definition, componentType);
    }

    private void appendDescriptionAndTags(final StringBuilder md, final JsonNode definition,
                                          final JsonNode componentType) {
        if (isMissingMetadata(definition, componentType)) {
            return;
        }
        final String description = firstNonBlank(text(definition, ComponentFields.DESCRIPTION),
                text(componentType, ComponentFields.DESCRIPTION));
        if (!description.isBlank()) {
            md.append("## Description").append(LF).append(LF)
                    .append(Markdown.inline(description)).append(LF).append(LF);
        }
        final JsonNode tags = definition.has(ComponentFields.TAGS)
                ? definition.get(ComponentFields.TAGS) : componentType.get(ComponentFields.TAGS);
        if (tags != null && tags.isArray() && !tags.isEmpty()) {
            md.append("Tags: ");
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) {
                    md.append(", ");
                }
                md.append('`').append(Markdown.inline(tags.get(i).asText())).append('`');
            }
            md.append(LF).append(LF);
        }
    }

    private void appendDeprecationAndRestrictions(final StringBuilder md, final JsonNode definition,
                                                  final JsonNode componentType) {
        if (isMissingMetadata(definition, componentType)) {
            return;
        }
        final String deprecationReason = firstNonBlank(text(definition, ComponentFields.DEPRECATION_REASON),
                text(componentType, ComponentFields.DEPRECATION_REASON));
        final boolean restricted = definition.path(ComponentFields.RESTRICTED)
                .asBoolean(componentType.path(ComponentFields.RESTRICTED).asBoolean());
        if (!deprecationReason.isBlank() || restricted) {
            md.append("## Deprecation and restrictions").append(LF).append(LF);
            if (!deprecationReason.isBlank()) {
                md.append("- Deprecated: ").append(Markdown.inline(deprecationReason)).append(LF);
            }
            if (restricted) {
                md.append("- Restricted component: requires appropriate policies.").append(LF);
            }
            md.append(LF);
        }
    }

    private void appendInputAndRelationships(final StringBuilder md, final JsonNode definition) {
        if (definition == null) {
            //skip if null
            return;
        }
        final String inputRequirement = text(definition, "inputRequirement");
        final JsonNode relationships = definition.get("supportedRelationships");
        if (inputRequirement.isBlank() && (relationships == null || !relationships.isArray())) {
            return;
        }
        md.append("## Input and relationships").append(LF).append(LF);
        if (!inputRequirement.isBlank()) {
            md.append("- Input requirement: `").append(Markdown.inline(inputRequirement)).append('`')
                    .append(LF).append(LF);
        }
        if (relationships != null && relationships.isArray() && !relationships.isEmpty()) {
            md.append("| Relationship | Description |").append(LF);
            md.append("|---|---|").append(LF);
            for (final JsonNode relationship : relationships) {
                md.append("| ").append(Markdown.cell(text(relationship, ComponentFields.NAME)))
                        .append(" | ").append(Markdown.cell(text(relationship, ComponentFields.DESCRIPTION)))
                        .append(" |").append(LF);
            }
            md.append(LF);
        }
    }

    private void appendProperties(final StringBuilder md, final JsonNode definition) {
        if (definition == null) {
            //skip if null
            return;
        }
        final JsonNode properties = definition.get("propertyDescriptors");
        if (properties == null || !properties.isObject() || properties.isEmpty()) {
            return;
        }
        md.append("## Properties").append(LF).append(LF);
        md.append("| Name | Display Name | Required | Sensitive | EL Scope | Default | Allowable Values |")
                .append(LF);
        md.append("|---|---|---|---|---|---|---|").append(LF);
        final Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> entry = fields.next();
            final JsonNode descriptor = entry.getValue();
            md.append("| ").append(Markdown.cell(text(descriptor, ComponentFields.NAME, entry.getKey())))
                    .append(" | ").append(Markdown.cell(text(descriptor, ComponentFields.DISPLAY_NAME)))
                    .append(" | ").append(descriptor.path("required").asBoolean() ? "yes" : "no")
                    .append(" | ").append(descriptor.path("sensitive").asBoolean() ? "yes" : "no")
                    .append(" | ").append(Markdown.cell(text(descriptor, "expressionLanguageScope")))
                    .append(" | ").append(Markdown.cell(text(descriptor, "defaultValue")))
                    .append(" | ").append(Markdown.cell(allowableValues(descriptor)))
                    .append(" |").append(LF);
        }
        md.append(LF);
    }

    private void appendControllerServiceApis(final StringBuilder md, final JsonNode componentType,
                                             final JsonNode definition) {
        if (isMissingMetadata(definition, componentType)) {
            return;
        }
        final JsonNode apis = componentType.has(ComponentFields.CONTROLLER_SERVICE_APIS)
                ? componentType.get(ComponentFields.CONTROLLER_SERVICE_APIS)
                : definition.get("providedApiImplementations");
        if (apis == null || !apis.isArray() || apis.isEmpty()) {
            return;
        }
        md.append("## Controller service APIs").append(LF).append(LF);
        for (final JsonNode api : apis) {
            final String apiType = api.has(ComponentFields.TYPE)
                    ? text(api, ComponentFields.TYPE) : Markdown.inline(api.asText());
            md.append("- `").append(apiType).append('`').append(LF);
        }
        md.append(LF);
    }

    private void appendAttributes(final StringBuilder md, final JsonNode definition) {
        if (definition == null) {
            //skip if null
            return;
        }
        appendAttributeTable(md, definition.get("readsAttributes"), "FlowFile attributes read");
        appendAttributeTable(md, definition.get("writesAttributes"), "FlowFile attributes written");
    }

    private void appendAttributeTable(final StringBuilder md, final JsonNode attributes, final String heading) {
        if (attributes == null || !attributes.isArray() || attributes.isEmpty()) {
            return;
        }
        md.append("## ").append(heading).append(LF).append(LF);
        md.append("| Attribute | Description |").append(LF);
        md.append("|---|---|").append(LF);
        for (final JsonNode attribute : attributes) {
            md.append("| ").append(Markdown.cell(text(attribute, ComponentFields.NAME)))
                    .append(" | ").append(Markdown.cell(text(attribute, ComponentFields.DESCRIPTION)))
                    .append(" |").append(LF);
        }
        md.append(LF);
    }

    private void appendLinks(final StringBuilder md, final ComponentRecord componentRecord) {
        md.append("## References").append(LF).append(LF);
        final AdditionalDocumentationState state = componentRecord.additionalDocumentation();
        if (state.isAvailable()) {
            md.append("- Additional component documentation: [")
                    .append(AdditionalDocumentationState.ADDITIONAL_DETAILS_FILE).append("](")
                    .append(AdditionalDocumentationState.ADDITIONAL_DETAILS_FILE).append(')').append(LF);
        }
        md.append("- Lossless definition: [").append(KnowledgeBaseFormat.COMPONENT_JSON_FILE).append("](")
                .append(KnowledgeBaseFormat.COMPONENT_JSON_FILE).append(')').append(LF).append(LF);
    }

    private static String allowableValues(final JsonNode descriptor) {
        final JsonNode values = descriptor.get("allowableValues");
        if (values == null || !values.isArray() || values.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            final JsonNode entry = values.get(i);
            final JsonNode holder = entry.has(ComponentFields.ALLOWABLE_VALUE)
                    ? entry.get(ComponentFields.ALLOWABLE_VALUE) : entry;
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(holder.path("value").asText(holder.path(ComponentFields.DISPLAY_NAME).asText("")));
        }
        return sb.toString();
    }

    private static String text(final JsonNode node, final String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    private static String text(final JsonNode node, final String field, final String fallback) {
        final String value = text(node, field);
        return value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(final String first, final String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }

    private static boolean isMissingMetadata(final JsonNode definition, final JsonNode componentType) {
        return definition == null || componentType == null;
    }
}

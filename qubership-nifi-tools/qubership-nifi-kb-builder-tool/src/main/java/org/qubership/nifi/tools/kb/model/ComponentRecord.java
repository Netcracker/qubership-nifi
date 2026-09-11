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

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * A single collected component: its identity, the type and definition trees, the
 * derived additional-documentation state, and the verbatim additional-details content when present.
 *
 * <p>The two JSON trees are held and handed back by reference rather than copied, so a caller that
 * mutates one changes what every other holder of this record sees. Treat them as read-only.
 *
 * <p>Everything but the additional-details content is required, and the constructor rejects a null.
 * The renderers each read the same trees, so a missing one must fail where it is introduced rather
 * than reach some outputs as an empty value and others as a literal {@code null}.
 */
public final class ComponentRecord {

    private final ComponentIdentity identity;
    private final JsonNode documentedType;
    private final JsonNode definition;
    private final AdditionalDocumentationState additionalDocumentation;
    private final String additionalDetailsContent;
    private final DefinitionFormat definitionFormat;
    private final JsonNode documentationSources;
    private final String componentDocumentation;

    /**
     * Returns how this component's definition was produced.
     *
     * @return the definition format
     */
    public DefinitionFormat definitionFormat() {
        return definitionFormat;
    }

    /**
     * Returns the paths this component's documentation was read from.
     *
     * @return the documentation sources, or empty for a native definition that has none
     */
    public Optional<JsonNode> documentationSources() {
        return Optional.ofNullable(documentationSources);
    }

    /**
     * Returns the full converted component page when the backend supplies one.
     *
     * @return the requested value
     */
    public Optional<String> componentDocumentation() {
        return Optional.ofNullable(componentDocumentation);
    }


    /**
     * Creates a new component record.
     *
     * @param componentIdentity       the canonical identity
     * @param documentedTypeNode      the complete type tree
     * @param definitionNode          the complete definition tree
     * @param additionalDocState      the derived additional-documentation state
     * @param additionalDetailsText   the verbatim additional-details content, or {@code null}
     * @throws NullPointerException if any argument other than {@code additionalDetailsText} is null
     */
    public ComponentRecord(final ComponentIdentity componentIdentity, final JsonNode documentedTypeNode,
                           final JsonNode definitionNode, final AdditionalDocumentationState additionalDocState,
                           final String additionalDetailsText) {
        this(componentIdentity, documentedTypeNode, definitionNode, additionalDocState, additionalDetailsText,
                ComponentProvenance.nativeDefinition(), null);
    }

    /**
     * Retains metadata and documentation together with the provenance of this component.
     *
     * @param componentIdentity the canonical component identity
     * @param documentedTypeNode the full type metadata
     * @param definitionNode the native or normalized static definition
     * @param additionalDocState the additional documentation outcome
     * @param additionalDetailsText the available additional Markdown, or null
     * @param componentProvenance where this component's definition and documentation came from
     * @param documentationMarkdown the full component Markdown, or null for native definitions
     */
    public ComponentRecord(final ComponentIdentity componentIdentity, final JsonNode documentedTypeNode,
                           final JsonNode definitionNode, final AdditionalDocumentationState additionalDocState,
                           final String additionalDetailsText, final ComponentProvenance componentProvenance,
                           final String documentationMarkdown) {
        final ComponentProvenance provenance = Objects.requireNonNull(componentProvenance, "componentProvenance");
        this.definitionFormat = provenance.format();
        this.documentationSources = provenance.documentationSources();
        this.componentDocumentation = documentationMarkdown;
        this.identity = Objects.requireNonNull(componentIdentity, "componentIdentity");
        this.documentedType = Objects.requireNonNull(documentedTypeNode, "documentedTypeNode");
        this.definition = Objects.requireNonNull(definitionNode, "definitionNode");
        this.additionalDocumentation = Objects.requireNonNull(additionalDocState, "additionalDocState");
        this.additionalDetailsContent = additionalDetailsText;
    }

    /**
     * Returns the canonical identity.
     *
     * @return the identity
     */
    public ComponentIdentity identity() {
        return identity;
    }

    /**
     * Returns the type tree.
     *
     * @return the type tree
     */
    public JsonNode documentedType() {
        return documentedType;
    }

    /**
     * Returns the complete definition tree.
     *
     * @return the definition tree
     */
    public JsonNode definition() {
        return definition;
    }

    /**
     * Returns the derived additional-documentation state.
     *
     * @return the additional-documentation state
     */
    public AdditionalDocumentationState additionalDocumentation() {
        return additionalDocumentation;
    }

    /**
     * Returns the verbatim additional-details content, present only when available.
     *
     * @return the optional additional-details content
     */
    public Optional<String> additionalDetailsContent() {
        return Optional.ofNullable(additionalDetailsContent);
    }
}

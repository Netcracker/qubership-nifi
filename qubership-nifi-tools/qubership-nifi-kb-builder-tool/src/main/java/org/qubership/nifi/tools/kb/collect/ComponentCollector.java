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

package org.qubership.nifi.tools.kb.collect;

import com.fasterxml.jackson.databind.JsonNode;
import org.qubership.nifi.tools.kb.model.AdditionalDocumentationState;
import org.qubership.nifi.tools.kb.model.ComponentIdentity;
import org.qubership.nifi.tools.kb.model.ComponentRecord;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentCatalogClient;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Collects the complete, lossless catalog of processors, controller services, and reporting tasks.
 * For each component it retains the full list entry and definition, then applies the
 * advertised/requested/available tri-state for optional additional documentation.
 *
 * <p>Any missing type, incomplete bundle coordinates, mismatched definition identity, duplicate
 * identity, or malformed {@code additionalDetails} field fails the whole build rather than producing
 * a silently incomplete catalog.</p>
 */
public final class ComponentCollector {

    private static final Logger LOG = LoggerFactory.getLogger(ComponentCollector.class);
    private static final String ADDITIONAL_DETAILS = "additionalDetails";

    /**
     * Collects all components of all three kinds.
     *
     * @param catalog the component catalog client bound to the current run
     * @return the collected component records
     */
    public List<ComponentRecord> collectAll(final NiFiComponentCatalogClient catalog) {
        final List<ComponentRecord> records = new ArrayList<>();
        final Set<ComponentIdentity> seen = new HashSet<>();
        for (final NiFiComponentKind kind : NiFiComponentKind.values()) {
            LOG.info("Collecting {} components", kind);
            final JsonNode types = catalog.listTypes(kind);
            for (final JsonNode entry : types) {
                final ComponentRecord record = collectOne(catalog, kind, entry);
                if (!seen.add(record.identity())) {
                    throw new CollectionException("Duplicate component identity: " + record.identity());
                }
                records.add(record);
            }
            LOG.info("Collected {} {} components", types.size(), kind);
        }
        return records;
    }

    private ComponentRecord collectOne(final NiFiComponentCatalogClient catalog, final NiFiComponentKind kind,
                                       final JsonNode entry) {
        final String type = requireText(entry, "type", "list entry is missing a type");
        final JsonNode bundle = entry.path("bundle");
        final String group = requireText(bundle, "group", "list entry " + type + " is missing bundle group");
        final String artifact = requireText(bundle, "artifact", "list entry " + type + " is missing bundle artifact");
        final String version = requireText(bundle, "version", "list entry " + type + " is missing bundle version");

        final ComponentIdentity identity = new ComponentIdentity(kind, group, artifact, version, type);
        final JsonNode definition = catalog.getDefinition(kind, group, artifact, version, type);
        verifyDefinitionIdentity(identity, definition);

        final AdditionalDetailsOutcome outcome = resolveAdditionalDetails(catalog, kind, identity, definition);
        return new ComponentRecord(identity, entry, definition, outcome.state(), outcome.content());
    }

    private void verifyDefinitionIdentity(final ComponentIdentity identity, final JsonNode definition) {
        final String definitionType = definition.path("type").asText("");
        if (!definitionType.isBlank() && !definitionType.equals(identity.getType())) {
            throw new CollectionException("Definition identity " + definitionType
                    + " disagrees with requested identity " + identity.getType());
        }
    }

    private AdditionalDetailsOutcome resolveAdditionalDetails(final NiFiComponentCatalogClient catalog,
                                                              final NiFiComponentKind kind,
                                                              final ComponentIdentity identity,
                                                              final JsonNode definition) {
        final JsonNode field = definition.get(ADDITIONAL_DETAILS);
        if (field == null || field.isNull()) {
            return new AdditionalDetailsOutcome(AdditionalDocumentationState.notAdvertised(), null);
        }
        if (!field.isBoolean()) {
            throw new CollectionException("Definition " + identity.getType()
                    + " has a non-Boolean additionalDetails field, which violates the NiFi 2.x API contract");
        }
        if (!field.booleanValue()) {
            return new AdditionalDetailsOutcome(AdditionalDocumentationState.notAdvertised(), null);
        }
        final Optional<String> content = catalog.getAdditionalDetails(kind, identity.getGroup(),
                identity.getArtifact(), identity.getVersion(), identity.getType());
        if (content.isEmpty()) {
            return new AdditionalDetailsOutcome(AdditionalDocumentationState.advertisedUnavailable(), null);
        }
        return new AdditionalDetailsOutcome(AdditionalDocumentationState.advertisedAvailable(), content.get());
    }

    private static String requireText(final JsonNode node, final String field, final String failure) {
        final String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new CollectionException(failure);
        }
        return value;
    }

    private record AdditionalDetailsOutcome(AdditionalDocumentationState state, String content) {
    }
}

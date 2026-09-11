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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.nifi.tools.kb.docs.ComponentDocumentationCollector;
import org.qubership.nifi.tools.kb.model.CollectionMetadata;
import org.qubership.nifi.tools.kb.model.ComponentProvenance;
import org.qubership.nifi.tools.kb.model.DefinitionFormat;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentReference;
import org.qubership.nifi.tools.nifi.common.api.NiFi1xComponentMetadataProvider;
import org.qubership.nifi.tools.nifi.common.api.NiFi2xComponentMetadataProvider;
import org.qubership.nifi.tools.kb.model.AdditionalDocumentationState;
import org.qubership.nifi.tools.kb.model.ComponentIdentity;
import org.qubership.nifi.tools.kb.model.ComponentRecord;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentCatalogClient;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Collects the complete, lossless catalog of processors, controller services, and reporting tasks.
 * For each component it retains the full list entry and definition, then applies the
 * advertised/requested/available tri-state for optional additional documentation.
 *
 * <p>A component that violates the NiFi API contract fails the whole build rather than producing a
 * silently incomplete catalog; {@link CollectionException} names the violations.</p>
 */
public final class ComponentCollector {

    private static final Logger LOG = LoggerFactory.getLogger(ComponentCollector.class);
    private static final String ADDITIONAL_DETAILS = "additionalDetails";
    private static final int PROGRESS_INTERVAL = 50;

    /**
     * Collects all components of all three kinds.
     *
     * @param catalog the component catalog client bound to the current run
     * @return the collected component records, in the order the catalog listed each kind
     * @throws CollectionException when any component violates the NiFi API contract
     */
    public List<ComponentRecord> collectAll(final NiFiComponentCatalogClient catalog) {
        final List<ComponentRecord> records = new ArrayList<>();
        final Set<ComponentIdentity> seen = new HashSet<>();
        for (final NiFiComponentKind kind : NiFiComponentKind.values()) {
            LOG.info("Collecting {} components", kind);
            final JsonNode types = catalog.listTypes(kind);
            int collected = 0;
            for (final JsonNode type : types) {
                final ComponentRecord componentRecord = collectOne(catalog, kind, type);
                if (!seen.add(componentRecord.identity())) {
                    throw new CollectionException("Duplicate component identity: " + componentRecord.identity());
                }
                records.add(componentRecord);
                collected++;
                // Each component costs one or two round trips and a full catalog runs into the
                // hundreds, so a run without interim progress looks stalled for minutes at a time.
                if (collected % PROGRESS_INTERVAL == 0) {
                    LOG.info("Collected {} of {} {} components", collected, types.size(), kind);
                }
            }
            LOG.info("Collected {} {} components", types.size(), kind);
        }
        return records;
    }

    /**
     * Reads and validates the complete catalog before any temporary resource is created.
     *
     * @param catalog the target component catalog
     * @return the requested value
     */
    public List<CatalogEntry> preflight(final NiFiComponentCatalogClient catalog) {
        List<CatalogEntry> entries = new ArrayList<>();
        Set<NiFiComponentReference> seen = new HashSet<>();
        for (NiFiComponentKind kind : NiFiComponentKind.values()) {
            for (JsonNode type : catalog.listTypes(kind)) {
                NiFiComponentReference reference;
                try {
                    reference = NiFiComponentReference.from(kind, type);
                } catch (IllegalArgumentException failure) {
                    throw new CollectionException("Invalid catalog identity: " + failure.getMessage());
                }
                if (!seen.add(reference)) {
                    throw new CollectionException("Duplicate component identity: " + reference);
                }
                ObjectNode documented = type.deepCopy();
                if (type.path("explicitRestrictions").isArray()) {
                    List<JsonNode> restrictions = new ArrayList<>();
                    type.path("explicitRestrictions").forEach(restrictions::add);
                    restrictions.sort(Comparator.comparing(value ->
                            value.path("requiredPermission").path("id").asText() + ":"
                                    + value.path("explanation").asText()));
                    documented.putArray("explicitRestrictions").addAll(restrictions);
                }
                entries.add(new CatalogEntry(reference, documented));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Collects the validated catalog, checking each page before creating its component. No definition
     * carries the {@code allowableValues} of a controller-service reference property.
     *
     * @param entries the validated complete catalog
     * @param provider the NiFi 1.x metadata provider
     * @param documentation the component HTML collector
     * @return the collected metadata
     */
    public List<ComponentRecord> collectAll(final List<CatalogEntry> entries,
                                           final NiFi1xComponentMetadataProvider provider,
                                           final ComponentDocumentationCollector documentation) {
        List<ComponentRecord> records = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            var reference = entry.reference();
            var page = documentation.collect(reference);
            ObjectNode definition = (ObjectNode) provider.collect(reference);
            removeServiceInstanceChoices(definition);
            page.metadata().fields().forEachRemaining(field -> definition.set(field.getKey(), field.getValue()));
            for (String field : CollectionMetadata.TYPE_LIST_FALLBACK_FIELDS) {
                if (entry.type().has(field) && !definition.has(field)) {
                    definition.set(field, entry.type().get(field));
                }
            }
            var identity = new ComponentIdentity(reference.kind(), reference.group(), reference.artifact(),
                    reference.version(), reference.type());
            records.add(new ComponentRecord(identity, entry.type(), definition, page.additionalState(),
                    page.additionalMarkdown(),
                    new ComponentProvenance(DefinitionFormat.NORMALIZED_NIFI_1X,
                            CollectionMetadata.documentationSources(page.sourcePath(),
                                    page.additionalSourcePath())),
                    page.markdown()));
            if (records.size() % PROGRESS_INTERVAL == 0) {
                LOG.info("Collected {} of {} components", records.size(), entries.size());
            }
        }
        LOG.info("Collected {} components", records.size());
        return records;
    }

    public record CatalogEntry(NiFiComponentReference reference, JsonNode type) { }

    private static void removeServiceInstanceChoices(final ObjectNode definition) {
        // A service reference lists the service instances visible from the temporary group: instance
        // state that differs between targets, not static metadata.
        definition.path("propertyDescriptors").forEach(descriptor -> {
            if (descriptor.isObject() && !descriptor.path("identifiesControllerService").asText("").isBlank()) {
                ((ObjectNode) descriptor).remove("allowableValues");
            }
        });
    }

    private ComponentRecord collectOne(final NiFiComponentCatalogClient catalog, final NiFiComponentKind kind,
                                       final JsonNode typeEntry) {
        final String type = requireText(typeEntry, "type", "typeEntry is missing a type");
        final JsonNode bundle = typeEntry.path("bundle");
        final String group = requireBundleText(bundle, type, "group");
        final String artifact = requireBundleText(bundle, type, "artifact");
        final String version = requireBundleText(bundle, type, "version");

        final ComponentIdentity identity = new ComponentIdentity(kind, group, artifact, version, type);
        final JsonNode definition = new NiFi2xComponentMetadataProvider(catalog).collect(
                new NiFiComponentReference(kind, group, artifact, version, type));
        verifyDefinitionIdentity(identity, definition);

        final AdditionalDetailsOutcome outcome = resolveAdditionalDetails(catalog, kind, identity, definition);
        return new ComponentRecord(identity, typeEntry, definition, outcome.state(), outcome.content());
    }

    /**
     * Verifies that a definition describes the component that was requested. An absent type is a
     * violation in its own right: an empty or truncated body parses into a node with no {@code type},
     * and accepting it would write a component with no properties and no relationships, which is the
     * silently incomplete catalog this class exists to prevent.
     *
     * @param identity   the requested component identity
     * @param definition the definition the endpoint returned
     */
    private void verifyDefinitionIdentity(final ComponentIdentity identity, final JsonNode definition) {
        final String definitionType = definition.path("type").asText("");
        if (definitionType.isBlank()) {
            throw new CollectionException("Definition for " + identity.getType()
                    + " carries no type, so it cannot be verified against the requested identity");
        }
        if (!definitionType.equals(identity.getType())) {
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
        // Blank content is the same outcome as a 404: there is nothing to write, and calling it
        // available would publish an empty additionalDetails.md that a consumer has to open to
        // discover is worthless.
        if (content.isEmpty() || content.get().isBlank()) {
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

    private static String requireBundleText(final JsonNode bundle, final String type, final String field) {
        return requireText(bundle, field, "typeEntry " + type + " is missing bundle " + field);
    }

    private record AdditionalDetailsOutcome(AdditionalDocumentationState state, String content) {
    }
}

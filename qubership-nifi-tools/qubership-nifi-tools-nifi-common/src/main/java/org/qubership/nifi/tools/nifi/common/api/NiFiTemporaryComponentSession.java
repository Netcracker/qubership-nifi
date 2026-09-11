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

package org.qubership.nifi.tools.nifi.common.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.core5.http.HttpStatus;
import org.qubership.nifi.tools.nifi.common.http.NiFiApiException;
import org.qubership.nifi.tools.nifi.common.http.NiFiRestClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiUriResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Serial, lazy session owning a temporary group and every created component. No component is
 * configured, scheduled, or enabled. Creation still invokes extension initialization callbacks.
 * POST and DELETE are never retried by the transport. A stale revision allows one verified retry.
 */
public final class NiFiTemporaryComponentSession implements AutoCloseable {
    /**
     * Top-level fields that {@link #collect(NiFiComponentReference, boolean)} copies into the metadata
     * when the component instance reports them.
     */
    public static final List<String> OPTIONAL_INSTANCE_FIELDS = List.of("inputRequirement",
            "supportsParallelProcessing", "supportsEventDriven", "supportsBatching",
            "supportsSensitiveDynamicProperties", "persistsState", "restricted", "deprecated",
            "executionNodeRestricted", "controllerServiceApis");

    private static final Logger LOG = LoggerFactory.getLogger(NiFiTemporaryComponentSession.class);
    private final NiFiRestClient rest;
    private final NiFiUriResolver resolver;
    private final String parent;
    private final String marker = "nifi-metadata-" + UUID.randomUUID();
    private final List<OwnedResource> owned = new ArrayList<>();
    private OwnedResource group;
    private RuntimeException groupFailure;
    private boolean closed;
    private RuntimeException cleanupFailure;

    /**
     * Creates a lazy session; the first collection creates its owned group.
     *
     * @param client the authenticated client
     * @param uriResolver the deployment URI resolver
     * @param parentGroupId the parent ID, or null to resolve root
     */
    public NiFiTemporaryComponentSession(final NiFiRestClient client, final NiFiUriResolver uriResolver,
                                         final String parentGroupId) {
        rest = client;
        resolver = uriResolver;
        parent = parentGroupId == null ? "root" : safeId(parentGroupId);
    }

    /**
     * Collects the metadata of the requested component, creating a controller service in the temporary
     * group, as {@link #collect(NiFiComponentReference, boolean)} describes.
     *
     * @param reference the exact component and bundle identity
     * @return the collected metadata
     */
    public JsonNode collect(final NiFiComponentReference reference) {
        return collect(reference, false);
    }

    /**
     * Collects the metadata of the requested component from a temporary instance, then removes the
     * instance. A failed creation never triggers another POST in a different scope.
     *
     * <p>Descriptors are returned as the instance reports them, so the {@code allowableValues} of a
     * controller-service reference property list the service instances visible from the temporary group.</p>
     *
     * @param reference the exact component and bundle identity
     * @param controllerScope whether to create a controller service at controller level rather than in the
     *        temporary group; reporting tasks are created at controller level either way
     * @return the collected metadata
     * @throws NiFiCleanupException when this component cannot be removed after collection, or when an
     *         earlier cleanup in this session failed, in which case no request is sent
     * @throws IllegalStateException when the session is closed or its group creation failed earlier; no
     *         request is sent
     */
    public JsonNode collect(final NiFiComponentReference reference, final boolean controllerScope) {
        if (cleanupFailure != null) {
            throw new NiFiCleanupException("Temporary session stopped after a cleanup failure for marker "
                    + marker, cleanupFailure);
        }
        if (closed) {
            throw new IllegalStateException("Temporary session is closed");
        }
        ensureGroup();
        NiFiComponentKind kind = reference.kind();
        String name = marker + "-" + UUID.randomUUID();
        ObjectNode component = JsonNodeFactory.instance.objectNode().put("name", name).put("type", reference.type());
        component.set("bundle", reference.bundle());
        OwnedResource resource = create(kind.getCreatePath(group.id, controllerScope),
                kind.getInstanceListPath(group.id, controllerScope), kind.getInstanceListKey(),
                kind.getInstancePathPrefix(), name, reference, component);
        RuntimeException primary = null;
        try {
            JsonNode node = resource.entity.path("component");
            if (!hasMetadata(reference, node)) {
                node = rest.getJson(resolver.resolve(resource.endpoint())).path("component");
            }
            reference.verify(node);
            if (node.path("extensionMissing").asBoolean()) {
                throw new IllegalStateException("Missing extension for " + reference);
            }
            if (!hasMetadata(reference, node)) {
                throw new IllegalStateException("Missing descriptors or relationships for " + reference);
            }
            return normalize(reference, node);
        } catch (RuntimeException failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                delete(resource);
            } catch (RuntimeException failure) {
                cleanupFailure = failure;
                LOG.error("Failed to clean up resource (id: {}, kind: {})", resource.id, reference.kind(), failure);
                if (primary != null) {
                    primary.addSuppressed(failure);
                }
            }
        }
    }

    private static boolean hasMetadata(final NiFiComponentReference reference, final JsonNode component) {
        return descriptors(reference, component).isObject()
                && (reference.kind() != NiFiComponentKind.PROCESSOR || component.path("relationships").isArray());
    }

    private static JsonNode descriptors(final NiFiComponentReference reference, final JsonNode component) {
        return (reference.kind() == NiFiComponentKind.PROCESSOR ? component.path("config") : component)
                .path("descriptors");
    }

    private static JsonNode normalize(final NiFiComponentReference reference, final JsonNode component) {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("type", reference.type());
        result.set("bundle", reference.bundle());
        result.set("propertyDescriptors", descriptors(reference, component).deepCopy());
        for (String field : OPTIONAL_INSTANCE_FIELDS) {
            if (component.has(field)) {
                result.set(field, component.get(field).deepCopy());
            }
        }
        if (reference.kind() == NiFiComponentKind.PROCESSOR) {
            var relationships = result.putArray("supportedRelationships");
            component.path("relationships").forEach(relationship -> {
                ObjectNode target = relationships.addObject();
                for (String field : List.of("name", "description")) {
                    if (relationship.has(field)) {
                        target.set(field, relationship.get(field).deepCopy());
                    }
                }
            });
        }
        return result;
    }

    private void ensureGroup() {
        if (group != null) {
            return;
        }
        if (groupFailure != null) {
            throw new IllegalStateException("Temporary group creation failed for marker " + marker
                    + "; no component can be created in this session", groupFailure);
        }
        try {
            JsonNode parentEntity = rest.getJson(resolver.resolve("/nifi-api/process-groups/" + parent));
            String parentId = safeId(parentEntity.path("component").path("id").asText());
            LOG.info("Temporary component run marker {}", marker);
            String path = "/nifi-api/process-groups/" + parentId + "/process-groups";
            group = create(path, path, "processGroups", "/nifi-api/process-groups/", marker, null,
                    JsonNodeFactory.instance.objectNode().put("name", marker));
        } catch (RuntimeException failure) {
            groupFailure = failure;
            throw failure;
        }
    }

    private OwnedResource create(final String path, final String listPath, final String listKey,
                                 final String prefix, final String name, final NiFiComponentReference reference,
                                 final ObjectNode component) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.putObject("revision").put("version", 0);
        body.set("component", component);
        LOG.debug("Creating temporary resource name={} marker={} endpoint={}", name, marker, path);
        try {
            JsonNode entity = rest.postJson(resolver.resolve(path), body.toString());
            OwnedResource resource = register(entity, prefix, name, reference);
            if (resource.revision < 0) {
                throw new IllegalStateException("Create response lacks revision for " + resource.endpoint());
            }
            return resource;
        } catch (RuntimeException primary) {
            // Even a malformed successful response can leave an instance behind. Never repeat POST.
            try {
                JsonNode list = rest.getJson(resolver.resolve(listPath)).path(listKey);
                if (!list.isArray()) {
                    throw new IllegalStateException("Reconciliation response lacks " + listKey);
                }
                boolean reconciled = false;
                for (JsonNode entity : list) {
                    if (name.equals(entity.path("component").path("name").asText())) {
                        OwnedResource resource = register(entity, prefix, name, reference);
                        delete(resource);
                        reconciled = true;
                    }
                }
                if (!reconciled && !(primary instanceof NiFiApiException apiFailure
                        && apiFailure.getStatusCode() >= HttpStatus.SC_BAD_REQUEST
                        && apiFailure.getStatusCode() < HttpStatus.SC_SERVER_ERROR)) {
                    throw new IllegalStateException("Cannot establish the outcome of creation for exact name " + name);
                }
            } catch (RuntimeException recovery) {
                cleanupFailure = new NiFiCleanupException("Cannot reconcile or remove the resource requested at "
                        + path + "; inspect exact marker/name " + name + " and owned resource inventory", recovery);
                LOG.error("Failed to reconcile or remove temporary resource {}; the session sends no further "
                        + "collection requests", name, cleanupFailure);
                primary.addSuppressed(cleanupFailure);
            }
            throw primary;
        }
    }

    private OwnedResource register(final JsonNode entity, final String prefix, final String name,
                                   final NiFiComponentReference reference) {
        String id = safeId(entity.path("component").path("id").asText());
        for (OwnedResource resource : owned) {
            if (resource.prefix.equals(prefix) && resource.id.equals(id)) {
                return resource;
            }
        }
        JsonNode revision = entity.path("revision").path("version");
        OwnedResource resource = new OwnedResource(id, prefix, name, reference,
                revision.isIntegralNumber() && revision.canConvertToLong() ? revision.longValue() : -1, entity);
        owned.add(resource);
        LOG.debug("Owned {} id={} marker={} endpoint={}", resource.kindLabel(), id, marker, resource.endpoint());
        return resource;
    }

    private void delete(final OwnedResource resource) {
        try {
            if (resource.revision < 0 && !refresh(resource)) {
                owned.remove(resource);
                return;
            }
            try {
                rest.delete(resolver.resolve(resource.endpoint() + "?version=" + resource.revision));
            } catch (NiFiApiException failure) {
                if (failure.getStatusCode() != HttpStatus.SC_NOT_FOUND && !isStaleRevision(failure)) {
                    throw failure;
                }
                if (refresh(resource)) {
                    rest.delete(resolver.resolve(resource.endpoint() + "?version=" + resource.revision));
                }
            }
            owned.remove(resource);
        } catch (RuntimeException failure) {
            resource.cleanupFailed = true;
            throw new NiFiCleanupException("Failed cleanup of " + resource.kindLabel() + " id=" + resource.id
                    + " endpoint=" + resource.endpoint() + " marker=" + marker, failure);
        }
    }

    private static boolean isStaleRevision(final NiFiApiException failure) {
        // NiFi maps InvalidRevisionException to 400 with a message naming the revision. A 409 reports a
        // component state, such as running, that a current revision cannot change.
        return failure.getStatusCode() == HttpStatus.SC_BAD_REQUEST
                && Objects.requireNonNullElse(failure.getResponseExcerpt(), "").toLowerCase(Locale.ROOT)
                        .contains("revision");
    }

    private boolean refresh(final OwnedResource resource) {
        var current = rest.getJsonAllowingNotFound(resolver.resolve(resource.endpoint()));
        if (current.isEmpty()) {
            return false;
        }
        JsonNode entity = current.get();
        JsonNode component = entity.path("component");
        if (!resource.id.equals(component.path("id").asText())
                || !resource.name.equals(component.path("name").asText())
                || !resource.entity.path("component").path("parentGroupId")
                        .equals(component.path("parentGroupId"))) {
            throw new IllegalStateException("Ownership changed for " + resource.endpoint());
        }
        if (resource.reference != null) {
            resource.reference.verify(component);
        }
        JsonNode revision = entity.path("revision").path("version");
        if (!revision.isIntegralNumber() || !revision.canConvertToLong() || revision.longValue() < 0) {
            throw new IllegalStateException("Missing current revision for " + resource.endpoint());
        }
        resource.revision = revision.longValue();
        return true;
    }

    private static String safeId(final String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]+")) {
            throw new IllegalStateException("Missing or invalid resource ID");
        }
        return id;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        LOG.info("Cleaning up temporary component run {}", marker);
        RuntimeException failure = cleanupFailure;
        List<OwnedResource> remaining = new ArrayList<>(owned);
        Collections.reverse(remaining);
        for (OwnedResource resource : remaining) {
            if (resource.cleanupFailed) {
                continue;
            }
            // A group deletion must never conceal failed child cleanup.
            if (resource.isGroup() && owned.size() > 1) {
                continue;
            }
            try {
                delete(resource);
            } catch (RuntimeException next) {
                if (failure == null) {
                    failure = next;
                } else if (failure != next) {
                    failure.addSuppressed(next);
                }
            }
        }
        if (failure != null) {
            throw new NiFiCleanupException("Temporary component cleanup failed for marker " + marker, failure);
        }
    }

    private static final class OwnedResource {
        private final String id;
        private final String prefix;
        private final String name;
        private final NiFiComponentReference reference;
        private final JsonNode entity;
        private long revision;
        private boolean cleanupFailed;

        private OwnedResource(final String resourceId, final String endpointPrefix, final String resourceName,
                              final NiFiComponentReference componentReference, final long version,
                              final JsonNode response) {
            id = resourceId;
            prefix = endpointPrefix;
            name = resourceName;
            reference = componentReference;
            revision = version;
            entity = response;
        }

        private String endpoint() {
            return prefix + id;
        }

        private boolean isGroup() {
            return reference == null;
        }

        private String kindLabel() {
            return isGroup() ? "PROCESS_GROUP" : reference.kind().name();
        }
    }
}

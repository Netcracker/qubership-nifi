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

package org.qubership.nifi.tools.nifi.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Optional;

/**
 * Retrieves NiFi component catalog data through read-only endpoints: type lists, component definitions,
 * and optional additional component documentation.
 */
public final class NiFiComponentCatalogClient {

    private static final String ADDITIONAL_DETAILS_FIELD = "additionalDetails";

    private final NiFiRestClient restClient;
    private final NiFiUriResolver resolver;

    /**
     * Creates a new catalog client.
     *
     * @param client      the REST client
     * @param uriResolver the URI resolver
     */
    public NiFiComponentCatalogClient(final NiFiRestClient client, final NiFiUriResolver uriResolver) {
        this.restClient = client;
        this.resolver = uriResolver;
    }

    /**
     * Lists all component types of the given kind, returning the complete list-entry array.
     *
     * @param kind the component kind
     * @return the array node of list entries
     * @throws NiFiApiException when the list response lacks its expected array
     */
    public JsonNode listTypes(final NiFiComponentKind kind) {
        final URI uri = resolver.resolve(kind.getListPath());
        final JsonNode response = restClient.getJson(uri);
        final JsonNode types = response.get(kind.getListKey());
        if (types == null || !types.isArray()) {
            throw new NiFiApiException("GET", NiFiHttpClient.redact(uri), NiFiApiException.NO_STATUS, "",
                    "List response for " + kind + " is missing the '" + kind.getListKey() + "' array");
        }
        return types;
    }

    /**
     * Retrieves the complete definition for a component.
     *
     * @param kind     the component kind
     * @param group    the bundle group
     * @param artifact the bundle artifact
     * @param version  the bundle version
     * @param type     the fully qualified type
     * @return the complete definition tree
     */
    public JsonNode getDefinition(final NiFiComponentKind kind, final String group, final String artifact,
                                  final String version, final String type) {
        final URI uri = resolver.resolveDefinition(kind, group, artifact, version, type);
        return restClient.getJson(uri);
    }

    /**
     * Retrieves optional additional component documentation. Callers must only invoke this when the
     * definition advertised additional details.
     *
     * @param kind     the component kind
     * @param group    the bundle group
     * @param artifact the bundle artifact
     * @param version  the bundle version
     * @param type     the fully qualified type
     * @return the verbatim additional-details string, or an empty optional when the endpoint returns
     *         {@code 404}
     * @throws NiFiApiException when a {@code 200} response lacks a string additional-details field
     */
    public Optional<String> getAdditionalDetails(final NiFiComponentKind kind, final String group,
                                                 final String artifact, final String version, final String type) {
        final URI uri = resolver.resolveAdditionalDetails(kind, group, artifact, version, type);
        final Optional<JsonNode> envelope = restClient.getJsonAllowingNotFound(uri);
        if (envelope.isEmpty()) {
            return Optional.empty();
        }
        final JsonNode field = envelope.get().get(ADDITIONAL_DETAILS_FIELD);
        if (field == null || !field.isTextual()) {
            throw new NiFiApiException("GET", NiFiHttpClient.redact(uri), NiFiApiException.NO_STATUS, "",
                    "Additional-details response lacks a string '" + ADDITIONAL_DETAILS_FIELD + "' field");
        }
        return Optional.of(field.textValue());
    }
}

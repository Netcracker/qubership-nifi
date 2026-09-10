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

package org.qubership.nifi.tools.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentCatalogClient;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentReference;
import org.qubership.nifi.tools.nifi.common.api.NiFi2xComponentMetadataProvider;
import org.qubership.nifi.tools.nifi.common.api.NiFiCleanupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Adapts the shared metadata provider to the exporter's descriptor-only format. */
public final class NiFi2xStrategy implements NiFiVersionStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(NiFi2xStrategy.class);
    private final NiFiApiClient apiClient;

    /**
     * Uses shared native definitions for descriptor export.
     *
     * @param client the authenticated client
     */
    public NiFi2xStrategy(final NiFiApiClient client) {
        apiClient = client;
    }

    @Override
    public List<Map<String, Object>> collect(final NiFiComponentKind kind) {
        NiFiComponentCatalogClient catalog = new NiFiComponentCatalogClient(apiClient.restClient(),
                apiClient.resolver());
        List<Map<String, Object>> result = new ArrayList<>();

        var provider = new NiFi2xComponentMetadataProvider(catalog);
        for (JsonNode entry : catalog.listTypes(kind)) {
            try {
                var reference = NiFiComponentReference.from(kind, entry);
                JsonNode definition = provider.collect(reference);
                JsonNode descriptors = definition.path("propertyDescriptors");
                result.add(Map.of("type", reference.type(), "propertyDescriptors", descriptors.isObject()
                        ? descriptors : JsonNodeFactory.instance.objectNode()));
            } catch (NiFiCleanupException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                LOG.warn("Failed to collect descriptors for {} ({})", entry.path("type").asText(), kind, failure);
            }
        }

        return result;
    }
}

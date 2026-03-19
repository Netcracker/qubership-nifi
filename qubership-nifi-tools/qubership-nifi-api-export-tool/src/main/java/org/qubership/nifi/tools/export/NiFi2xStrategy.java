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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NiFi 2.x strategy: uses definition endpoints to retrieve property descriptors
 * without creating component instances.
 */
public final class NiFi2xStrategy implements NiFiVersionStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(NiFi2xStrategy.class);

    private final NiFiApiClient apiClient;

    /**
     * Creates a new NiFi2xStrategy using the given API client.
     *
     * @param client the NiFi API client (must already be authenticated)
     */
    public NiFi2xStrategy(final NiFiApiClient client) {
        this.apiClient = client;
    }

    @Override
    public List<Map<String, Object>> collect(final ComponentKind kind) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();

        JsonNode listResponse = apiClient.get(kind.getListPath());
        JsonNode types = listResponse.get(kind.getListKey());
        if (types == null || !types.isArray()) {
            LOG.warn("No types found for {} at path {}", kind, kind.getListPath());
            return result;
        }

        for (JsonNode typeEntry : types) {
            String fqcn = typeEntry.path("type").asText();
            JsonNode bundle = typeEntry.path("bundle");
            String group = bundle.path("group").asText();
            String artifact = bundle.path("artifact").asText();
            String version = bundle.path("version").asText();

            try {
                String defPath = kind.getDefinitionPathPrefix()
                        + "/" + group + "/" + artifact + "/" + version + "/" + fqcn;
                JsonNode defResponse = apiClient.get(defPath);
                JsonNode rawDescriptors = defResponse.path("propertyDescriptors");
                JsonNode descriptors = rawDescriptors.isObject()
                        ? rawDescriptors
                        : JsonNodeFactory.instance.objectNode();

                Map<String, Object> entry = new HashMap<>();
                entry.put("type", fqcn);
                entry.put("propertyDescriptors", descriptors);
                result.add(entry);
            } catch (Exception e) {
                LOG.warn("Failed to get definition for {} ({})", fqcn, kind, e);
            }
        }

        return result;
    }
}

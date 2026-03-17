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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NiFi 1.x strategy: creates a component instance, extracts property descriptors, then deletes the instance.
 */
public final class NiFi1xStrategy implements NiFiVersionStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(NiFi1xStrategy.class);

    private final NiFiApiClient apiClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Creates a new NiFi1xStrategy using the given API client.
     *
     * @param client the NiFi API client (must already be authenticated)
     */
    public NiFi1xStrategy(final NiFiApiClient client) {
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
            try {
                Map<String, Object> entry = collectOne(kind, fqcn);
                result.add(entry);
            } catch (Exception e) {
                LOG.warn("Failed to collect descriptors for {} ({})", fqcn, kind, e);
            }
        }

        return result;
    }

    private Map<String, Object> collectOne(final ComponentKind kind, final String fqcn) throws Exception {
        ObjectNode revision = mapper.createObjectNode().put("version", 0);
        ObjectNode component = mapper.createObjectNode().put("type", fqcn);
        ObjectNode createBody = mapper.createObjectNode().set("revision", revision);
        createBody.set("component", component);
        String createBodyJson = mapper.writeValueAsString(createBody);

        switch (kind) {
            case PROCESSOR:
                return collectProcessor(fqcn, createBodyJson);
            case CONTROLLER_SERVICE:
                return collectControllerService(fqcn, createBodyJson);
            case REPORTING_TASK:
                return collectReportingTask(fqcn, createBodyJson);
            default:
                throw new IllegalArgumentException("Unknown kind: " + kind);
        }
    }

    private Map<String, Object> collectProcessor(final String fqcn, final String createBody) throws Exception {
        JsonNode response = apiClient.post("/nifi-api/process-groups/root/processors", createBody);
        String id = response.path("component").path("id").asText();
        if (id.isEmpty()) {
            throw new IllegalStateException("NiFi response missing component.id for " + fqcn);
        }
        long version = response.path("revision").path("version").asLong();
        JsonNode descriptors = response.path("component").path("config").path("descriptors");
        try {
            return buildEntry(fqcn, descriptors);
        } finally {
            deleteQuietly("/nifi-api/processors/" + id + "?version=" + version);
        }
    }

    private Map<String, Object> collectControllerService(final String fqcn, final String createBody) throws Exception {
        JsonNode response = apiClient.post("/nifi-api/process-groups/root/controller-services", createBody);
        String id = response.path("component").path("id").asText();
        if (id.isEmpty()) {
            throw new IllegalStateException("NiFi response missing component.id for " + fqcn);
        }
        long version = response.path("revision").path("version").asLong();
        JsonNode descriptors = response.path("component").path("descriptors");
        try {
            return buildEntry(fqcn, descriptors);
        } finally {
            deleteQuietly("/nifi-api/controller-services/" + id + "?version=" + version);
        }
    }

    private Map<String, Object> collectReportingTask(final String fqcn, final String createBody) throws Exception {
        JsonNode response = apiClient.post("/nifi-api/controller/reporting-tasks", createBody);
        String id = response.path("component").path("id").asText();
        if (id.isEmpty()) {
            throw new IllegalStateException("NiFi response missing component.id for " + fqcn);
        }
        long version = response.path("revision").path("version").asLong();
        JsonNode descriptors = response.path("component").path("descriptors");
        try {
            return buildEntry(fqcn, descriptors);
        } finally {
            deleteQuietly("/nifi-api/reporting-tasks/" + id + "?version=" + version);
        }
    }

    private Map<String, Object> buildEntry(final String fqcn, final JsonNode descriptors) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("type", fqcn);
        entry.put("propertyDescriptors", descriptors);
        return entry;
    }

    private void deleteQuietly(final String path) {
        try {
            apiClient.delete(path);
        } catch (Exception e) {
            LOG.warn("Failed to delete {}", path, e);
        }
    }
}

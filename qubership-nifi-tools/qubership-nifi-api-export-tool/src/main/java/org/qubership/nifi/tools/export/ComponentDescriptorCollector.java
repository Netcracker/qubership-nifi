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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Detects the NiFi version and delegates to the appropriate strategy.
 */
public final class ComponentDescriptorCollector {

    private static final Logger LOG = LoggerFactory.getLogger(ComponentDescriptorCollector.class);

    private final NiFiVersionStrategy strategy;

    /**
     * Creates a new collector, auto-detecting the NiFi major version to select the right strategy.
     *
     * @param client the NiFi API client (must already be authenticated)
     * @throws Exception if version detection or strategy initialisation fails
     */
    public ComponentDescriptorCollector(final NiFiApiClient client) throws Exception {
        int majorVersion = detectMajorVersion(client);
        LOG.info("Detected NiFi major version: {}", majorVersion);
        if (majorVersion >= 2) {
            strategy = new NiFi2xStrategy(client);
        } else {
            strategy = new NiFi1xStrategy(client);
        }
    }

    /**
     * Collects component descriptors for the given kind using the detected strategy.
     *
     * @param kind the component kind to collect
     * @return list of maps with "type" (String) and "propertyDescriptors" (JsonNode) entries
     * @throws Exception if collection fails
     */
    public List<Map<String, Object>> collect(final ComponentKind kind) throws Exception {
        return strategy.collect(kind);
    }

    private int detectMajorVersion(final NiFiApiClient apiClient) throws Exception {
        JsonNode about = apiClient.get("/nifi-api/flow/about");
        String version = about.path("about").path("version").asText();
        LOG.info("NiFi version string: {}", version);
        String majorStr = version.split("\\.")[0];
        return Integer.parseInt(majorStr);
    }
}

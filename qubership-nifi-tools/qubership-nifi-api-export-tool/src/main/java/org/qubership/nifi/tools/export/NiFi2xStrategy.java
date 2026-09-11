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

import org.qubership.nifi.tools.nifi.common.api.NiFiComponentCatalogClient;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;
import org.qubership.nifi.tools.nifi.common.api.NiFi2xComponentMetadataProvider;
import java.util.List;
import java.util.Map;

/** Adapts the shared metadata provider to the exporter's descriptor-only format. */
public final class NiFi2xStrategy implements NiFiVersionStrategy {
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
        return DescriptorExport.collect(catalog, new NiFi2xComponentMetadataProvider(catalog), kind);
    }
}

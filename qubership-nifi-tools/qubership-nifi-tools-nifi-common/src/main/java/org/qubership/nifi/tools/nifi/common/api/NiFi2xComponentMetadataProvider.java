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

/** Returns native NiFi 2.x definitions without modifying their trees. */
public final class NiFi2xComponentMetadataProvider implements NiFiComponentMetadataProvider {
    private final NiFiComponentCatalogClient catalog;

    /**
     * Uses the catalog client to retrieve native definitions.
     *
     * @param client the authenticated client
     */
    public NiFi2xComponentMetadataProvider(final NiFiComponentCatalogClient client) {
        catalog = client;
    }

    @Override
    public JsonNode collect(final NiFiComponentReference reference) {
        return catalog.getDefinition(reference.kind(), reference.group(), reference.artifact(),
                reference.version(), reference.type());
    }
}

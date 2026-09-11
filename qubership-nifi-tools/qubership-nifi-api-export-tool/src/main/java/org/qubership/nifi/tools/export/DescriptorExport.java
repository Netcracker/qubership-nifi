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
import org.qubership.nifi.tools.nifi.common.api.NiFiCleanupException;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentCatalogClient;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentMetadataProvider;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Collects the descriptors of every catalog type of one kind in the format {@link NiFiVersionStrategy}
 * returns, whichever NiFi version the metadata provider reads.
 */
final class DescriptorExport {
    private static final Logger LOG = LoggerFactory.getLogger(DescriptorExport.class);

    private DescriptorExport() {
        // utility class
    }

    /**
     * Collects the descriptors of every type the catalog lists for the kind. A type whose metadata cannot
     * be collected is logged and left out; the export continues with the next type.
     *
     * @param catalog the catalog that lists the types
     * @param provider the provider that collects each type's metadata
     * @param kind the component kind
     * @return one map per collected type, with {@code type} and {@code propertyDescriptors} entries; a
     *         missing or non-object {@code propertyDescriptors} becomes an empty object
     * @throws NiFiCleanupException when the provider cannot remove a temporary component; no further type
     *         is collected
     */
    static List<Map<String, Object>> collect(final NiFiComponentCatalogClient catalog,
                                             final NiFiComponentMetadataProvider provider,
                                             final NiFiComponentKind kind) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode entry : catalog.listTypes(kind)) {
            try {
                var reference = NiFiComponentReference.from(kind, entry);
                JsonNode descriptors = provider.collect(reference).path("propertyDescriptors");
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

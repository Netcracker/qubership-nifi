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
import java.util.Objects;

/**
 * Bundle-qualified identity, independent of any temporary instance.
 *
 * @param kind the component kind
 * @param group the bundle group
 * @param artifact the bundle artifact
 * @param version the bundle version
 * @param type the fully qualified component type
 */
public record NiFiComponentReference(NiFiComponentKind kind, String group, String artifact,
                                     String version, String type) {
    /** Validates that the identity has a kind, type, and complete bundle coordinates. */
    public NiFiComponentReference {
        Objects.requireNonNull(kind, "kind");
        for (String value : new String[]{group, artifact, version, type}) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Component type and complete bundle coordinates are required");
            }
        }
    }

    /**
     * Reads a bundle-qualified identity from a catalog entry.
     *
     * @param kind the component kind
     * @param entry the complete catalog entry
     * @return the requested value
     */
    public static NiFiComponentReference from(final NiFiComponentKind kind, final JsonNode entry) {
        JsonNode bundle = entry.path("bundle");
        return new NiFiComponentReference(kind, text(bundle, "group"), text(bundle, "artifact"),
                text(bundle, "version"), text(entry, "type"));
    }

    private static String text(final JsonNode node, final String field) {
        return node.path(field).isTextual() ? node.path(field).textValue() : "";
    }

    /**
     * Returns the exact bundle coordinates as a JSON object.
     *
     * @return the requested value
     */
    public ObjectNode bundle() {
        return JsonNodeFactory.instance.objectNode().put("group", group).put("artifact", artifact)
                .put("version", version);
    }

    /**
     * Rejects a returned component with a different type or bundle.
     *
     * @param component the returned component metadata
     */
    public void verify(final JsonNode component) {
        if (!type.equals(component.path("type").asText()) || !bundle().equals(component.path("bundle"))) {
            throw new IllegalStateException("Component identity does not match requested " + this);
        }
    }
}

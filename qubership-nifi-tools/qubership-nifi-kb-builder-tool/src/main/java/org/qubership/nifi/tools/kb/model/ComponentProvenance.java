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

package org.qubership.nifi.tools.kb.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Where a single component's definition and documentation came from.
 *
 * <p>Only what differs per component lives here. What the format means and which sources each
 * field group came from is the same for every component in a build, so it is described once in the
 * manifest by {@link CollectionMetadata#describe}.
 *
 * @param format             how the definition was produced
 * @param documentationSources the paths the documentation was read from, or {@code null} when the
 *                             component has none
 */
public record ComponentProvenance(DefinitionFormat format, JsonNode documentationSources) {

    /**
     * Creates component provenance.
     *
     * @throws NullPointerException when the format is null
     */
    public ComponentProvenance {
        Objects.requireNonNull(format, "format");
    }

    /**
     * Returns provenance for a component whose definition is an unmodified NiFi 2.x tree.
     *
     * @return the native provenance
     */
    public static ComponentProvenance nativeDefinition() {
        return new ComponentProvenance(DefinitionFormat.NATIVE_NIFI_2X, null);
    }
}

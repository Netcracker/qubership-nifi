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

import java.util.List;

/**
 * The in-memory Knowledge Base assembled before rendering: builder and NiFi provenance, the
 * collected components, and the guide result.
 */
public final class KnowledgeBase {

    private final KnowledgeBaseProvenance provenance;
    private final List<ComponentRecord> components;
    private final GuidesResult guides;

    /**
     * Creates a new Knowledge Base.
     *
     * @param kbProvenance     the builder and NiFi provenance
     * @param componentRecords the collected components
     * @param guideResult      the guide result
     */
    public KnowledgeBase(final KnowledgeBaseProvenance kbProvenance, final List<ComponentRecord> componentRecords,
                         final GuidesResult guideResult) {
        this.provenance = kbProvenance;
        this.components = List.copyOf(componentRecords);
        this.guides = guideResult;
    }

    /**
     * Returns the builder and NiFi provenance.
     *
     * @return the provenance
     */
    public KnowledgeBaseProvenance provenance() {
        return provenance;
    }

    /**
     * Returns the collected components.
     *
     * @return the components
     */
    public List<ComponentRecord> components() {
        return components;
    }

    /**
     * Returns the guide result.
     *
     * @return the guides
     */
    public GuidesResult guides() {
        return guides;
    }
}

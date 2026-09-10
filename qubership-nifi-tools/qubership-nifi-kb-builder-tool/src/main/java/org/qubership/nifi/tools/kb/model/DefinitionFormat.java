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

import org.qubership.nifi.tools.nifi.common.api.NiFiVersion;

/**
 * How a component definition in the Knowledge Base was produced.
 *
 * <p>A build targets one NiFi instance, so every component in a Knowledge Base shares one format.
 * The token is written once per build into the manifest and once per component into
 * {@code component.json}, so a consumer that opens a single component file still knows how to read
 * its definition without loading the manifest.
 */
public enum DefinitionFormat {

    /** An unmodified NiFi 2.x definition tree, taken from the definition endpoint. */
    NATIVE_NIFI_2X("native-nifi-2x"),

    /** A NiFi 1.x definition merged from instance descriptors and scraped component HTML. */
    NORMALIZED_NIFI_1X("normalized-nifi-1x");

    private final String token;

    DefinitionFormat(final String formatToken) {
        this.token = formatToken;
    }

    /**
     * Returns the token written to the Knowledge Base output.
     *
     * @return the format token
     */
    public String token() {
        return token;
    }

    /**
     * Returns the format a build against the given NiFi version produces.
     *
     * <p>NiFi 1.x has no definition endpoint, so its definitions are normalized; every later
     * version serves definitions directly.
     *
     * @param nifiVersion the detected NiFi version
     * @return the definition format for that version
     */
    public static DefinitionFormat forNiFiVersion(final String nifiVersion) {
        return NiFiVersion.isNiFi1x(nifiVersion) ? NORMALIZED_NIFI_1X : NATIVE_NIFI_2X;
    }
}

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

package org.qubership.nifi.tools.nifi.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Reads NiFi version information from {@code /nifi-api/flow/about}.
 */
public final class NiFiAboutClient {

    private static final String ABOUT_PATH = "/nifi-api/flow/about";

    private final NiFiRestClient restClient;
    private final NiFiUriResolver resolver;

    /**
     * Creates a new about client.
     *
     * @param client      the REST client
     * @param uriResolver the URI resolver
     */
    public NiFiAboutClient(final NiFiRestClient client, final NiFiUriResolver uriResolver) {
        this.restClient = client;
        this.resolver = uriResolver;
    }

    /**
     * Reads the raw NiFi version string from the about endpoint.
     *
     * @return the raw version string
     */
    public String readVersionString() {
        final JsonNode about = restClient.getJson(resolver.resolve(ABOUT_PATH));
        return about.path("about").path("version").asText("");
    }

    /**
     * Reads and parses the NiFi version.
     *
     * @return the parsed version, or an empty optional when the version cannot be parsed
     */
    public Optional<NiFiVersion> readVersion() {
        return NiFiVersion.parse(readVersionString());
    }
}

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
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/**
 * Sends JSON GET, POST, and DELETE requests over a {@link NiFiHttpClient} and maps non-success
 * responses to {@link NiFiApiException}. It accepts JSON only from API endpoints and preserves the
 * complete response tree without discarding unknown fields.
 */
public final class NiFiRestClient {

    private static final String ACCEPT_JSON = "application/json";
    private static final int NOT_FOUND = 404;

    private final NiFiHttpClient httpClient;
    private final ObjectMapper mapper;

    /**
     * Creates a new REST client.
     *
     * @param client       the underlying NiFi HTTP client
     * @param objectMapper the Jackson mapper used to parse responses
     */
    public NiFiRestClient(final NiFiHttpClient client, final ObjectMapper objectMapper) {
        this.httpClient = client;
        this.mapper = objectMapper;
    }

    /**
     * Returns the underlying HTTP client for callers that need non-JSON transport (for example the
     * export tool's form-encoded token request).
     *
     * @return the underlying HTTP client
     */
    public NiFiHttpClient httpClient() {
        return httpClient;
    }

    /**
     * Performs a JSON GET request that must succeed.
     *
     * @param uri the request URI
     * @return the parsed JSON tree
     * @throws NiFiApiException when the response is not a 2xx JSON response
     */
    public JsonNode getJson(final URI uri) {
        final NiFiHttpResponse response = httpClient.get(uri, ACCEPT_JSON);
        if (!response.isSuccess()) {
            throw error(uri, response, "GET request did not succeed");
        }
        return parse(uri, response);
    }

    /**
     * Performs a JSON GET request, treating a {@code 404} as an absent resource.
     *
     * @param uri the request URI
     * @return the parsed JSON tree, or an empty optional on {@code 404}
     * @throws NiFiApiException when the response is neither 2xx JSON nor {@code 404}
     */
    public Optional<JsonNode> getJsonAllowingNotFound(final URI uri) {
        final NiFiHttpResponse response = httpClient.get(uri, ACCEPT_JSON);
        if (response.statusCode() == NOT_FOUND) {
            return Optional.empty();
        }
        if (!response.isSuccess()) {
            throw error(uri, response, "GET request did not succeed");
        }
        return Optional.of(parse(uri, response));
    }

    /**
     * Performs a JSON POST request that must succeed.
     *
     * @param uri  the request URI
     * @param body the JSON request body
     * @return the parsed JSON tree
     * @throws NiFiApiException when the response is not a 2xx JSON response
     */
    public JsonNode postJson(final URI uri, final String body) {
        final NiFiHttpResponse response = httpClient.post(uri, body, ACCEPT_JSON, ACCEPT_JSON);
        if (!response.isSuccess()) {
            throw new NiFiApiException("POST", NiFiHttpClient.redact(uri), response.statusCode(),
                    NiFiHttpClient.excerpt(response.bodyAsText()),
                    "POST request did not succeed (status " + response.statusCode() + ")");
        }
        return parse(uri, response);
    }

    private JsonNode parse(final URI uri, final NiFiHttpResponse response) {
        final Optional<String> contentType = response.contentType();
        if (contentType.isPresent() && !contentType.get().toLowerCase(java.util.Locale.ROOT).contains("json")) {
            throw error(uri, response, "Expected a JSON response but received content type " + contentType.get());
        }
        try {
            return mapper.readTree(response.body());
        } catch (final IOException e) {
            throw error(uri, response, "Response body is not valid JSON");
        }
    }

    private static NiFiApiException error(final URI uri, final NiFiHttpResponse response, final String reason) {
        return new NiFiApiException("GET", NiFiHttpClient.redact(uri), response.statusCode(),
                NiFiHttpClient.excerpt(response.bodyAsText()), reason + " (status " + response.statusCode() + ")");
    }
}

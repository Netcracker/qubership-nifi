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
package org.qubership.nifi.dev.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Writes and removes single Consul KV entries over plain HTTP, so a test can put a configuration
 * where the script under test looks for it.
 *
 * <p>No ACL token is sent. The stack these tests run against has ACLs disabled, and a test that
 * needs the token header covers it by setting {@code CONSUL_ACL_TOKEN} for the script instead.
 *
 * <p>The caller supplies the {@link HttpClient}, rather than this class calling
 * {@link HttpClient#newHttpClient()}, so that a JVM with no usable default trust store can still
 * reach a plain-HTTP Consul: the default client builds the default {@code SSLContext} eagerly, and
 * fails where that trust store cannot be read.
 */
final class ConsulKvClient {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulKvClient.class);
    private static final int HTTP_OK = 200;

    private final String consulUrl;
    private final HttpClient httpClient;

    /**
     * @param url    base URL of the Consul HTTP API, without the {@code /v1/kv/} path
     * @param client client the requests go through; its TLS configuration is never used, because
     *               the Consul of this stack listens on plain HTTP
     */
    ConsulKvClient(final String url, final HttpClient client) {
        this.consulUrl = url;
        this.httpClient = client;
    }

    /**
     * Stores a value under a KV key, replacing whatever was there.
     *
     * @param key   KV key without the {@code /v1/kv/} prefix
     * @param value value to store, sent as UTF-8 bytes
     */
    void put(final String key, final String value) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(consulUrl + "/v1/kv/" + key))
                .PUT(HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(HTTP_OK, resp.statusCode(),
                "Expected HTTP 200 when writing Consul key " + key + ". Response: " + resp.body());
        LOG.info("Wrote Consul key {} ({} bytes)", key, value.length());
    }

    /**
     * Removes a KV key. A key that is not there is not an error, so this is safe in teardown.
     *
     * @param key KV key without the {@code /v1/kv/} prefix
     */
    void delete(final String key) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(consulUrl + "/v1/kv/" + key))
                .DELETE()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            LOG.warn("DELETE Consul key {} returned status {}; body: {}", key, resp.statusCode(), resp.body());
        }
    }
}

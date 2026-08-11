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

package org.qubership.nifi.tools.nifi.common.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.tools.nifi.common.auth.NoAuthentication;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fails when REST handling stops distinguishing JSON success, absence, and protocol errors.
 *
 * <p>Successful GET and POST responses must produce their complete JSON trees. Only an allowed
 * {@code 404} may become an empty result; other failed statuses, non-JSON content, and malformed
 * bodies must produce redacted API exceptions. Classify any new response category explicitly in
 * both the client and these cases.</p>
 */
class NiFiRestClientTest {

    private static final String POST = "POST";

    private MockWebServer server;
    private NiFiUriResolver resolver;
    private NiFiRestClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        resolver = NiFiUriResolver.fromBaseUrl(server.url("/").toString(), false);
        final CloseableHttpClient transport = NiFiHttpClient.newHttpClient(null, Duration.ofSeconds(5));
        final NiFiHttpClient.Config noRetries = new NiFiHttpClient.Config(
                Duration.ofSeconds(5), 1024 * 1024, 0, Duration.ofMillis(1), Duration.ofMillis(5), 3);
        final NiFiHttpClient httpClient = new NiFiHttpClient(
                transport, resolver, NoAuthentication.INSTANCE, noRetries);
        client = new NiFiRestClient(httpClient, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.shutdown();
    }

    @Test
    void getsJsonWithPresentOrMissingContentType() {
        server.enqueue(jsonResponse(200, "{\"first\":true}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"second\":true}"));

        final JsonNode first = client.getJson(uri("/first"));
        final JsonNode second = client.getJsonAllowingNotFound(uri("/second")).orElseThrow();

        assertThat(first.path("first").asBoolean()).isTrue();
        assertThat(second.path("second").asBoolean()).isTrue();
        assertThat(client.httpClient()).isNotNull();
    }

    @Test
    void treatsNotFoundAsAbsent() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("missing"));

        assertThat(client.getJsonAllowingNotFound(uri("/missing"))).isEmpty();
    }

    @Test
    void rejectsFailedGetRequests() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("failed"));
        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));

        assertThatThrownBy(() -> client.getJson(uri("/failed?token=secret")))
                .isInstanceOf(NiFiApiException.class)
                .hasMessageContaining("status 500")
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> client.getJsonAllowingNotFound(uri("/forbidden")))
                .isInstanceOf(NiFiApiException.class)
                .hasMessageContaining("status 403");
    }

    @Test
    void postsJsonAndRejectsFailedPostRequests() throws InterruptedException {
        server.enqueue(jsonResponse(201, "{\"created\":true}"));
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));

        assertThat(client.postJson(uri("/items"), "{\"name\":\"item\"}")
                .path("created").asBoolean()).isTrue();
        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo(POST);
        assertThat(request.getBody().readUtf8()).isEqualTo("{\"name\":\"item\"}");
        assertThatThrownBy(() -> client.postJson(uri("/items"), "{}"))
                .isInstanceOfSatisfying(NiFiApiException.class,
                        exception -> assertThat(exception.getMethod()).isEqualTo(POST))
                .hasMessageContaining("status 400");
    }

    @Test
    void rejectsNonJsonContentTypesAndMalformedBodies() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/plain").setBody("{}"));
        server.enqueue(jsonResponse(200, "not-json"));

        assertThatThrownBy(() -> client.getJson(uri("/plain")))
                .isInstanceOf(NiFiApiException.class)
                .hasMessageContaining("Expected a JSON response");
        assertThatThrownBy(() -> client.getJson(uri("/malformed")))
                .isInstanceOf(NiFiApiException.class)
                .hasMessageContaining("not valid JSON");
    }

    private URI uri(final String path) {
        return resolver.resolve(path);
    }

    private static MockResponse jsonResponse(final int status, final String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", NiFiHttpClient.APPLICATION_JSON + "; charset=utf-8")
                .setBody(body);
    }
}

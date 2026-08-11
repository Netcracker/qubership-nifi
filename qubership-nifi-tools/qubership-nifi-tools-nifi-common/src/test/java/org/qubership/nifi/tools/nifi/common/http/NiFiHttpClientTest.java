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

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.tools.nifi.common.auth.AuthorizationBearerCookieAuthenticator;
import org.qubership.nifi.tools.nifi.common.auth.BearerTokenAuthenticator;
import org.qubership.nifi.tools.nifi.common.auth.NiFiRequestAuthenticator;
import org.qubership.nifi.tools.nifi.common.auth.NoAuthentication;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NiFiHttpClientTest {

    private MockWebServer server;
    private NiFiUriResolver resolver;
    private CloseableHttpClient transport;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        resolver = NiFiUriResolver.fromBaseUrl(server.url("/").toString(), false);
        transport = NiFiHttpClient.newHttpClient(null, Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() throws IOException {
        transport.close();
        server.shutdown();
    }

    private NiFiHttpClient client(final NiFiRequestAuthenticator auth, final NiFiHttpClient.Config config) {
        return new NiFiHttpClient(transport, resolver, auth, config);
    }

    @Test
    void getReturnsBodyAndContentType() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody("{\"ok\":true}"));
        final NiFiHttpResponse response =
                client(NoAuthentication.INSTANCE, NiFiHttpClient.Config.defaults())
                        .get(resolver.resolve("/nifi-api/flow/about"), "application/json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.contentType()).contains("application/json");
        assertThat(response.bodyAsText()).contains("ok");
    }

    @Test
    void appliesBearerHeader() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        client(new BearerTokenAuthenticator("secret-token"), NiFiHttpClient.Config.defaults())
                .get(resolver.resolve("/nifi-api/flow/about"), "application/json");
        final RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer secret-token");
    }

    @Test
    void appliesAuthorizationBearerCookie() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        client(new AuthorizationBearerCookieAuthenticator("secret-cookie"), NiFiHttpClient.Config.defaults())
                .get(resolver.resolve("/nifi-api/flow/about"), "application/json");
        final RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("Cookie"))
                .isEqualTo("__Secure-Authorization-Bearer=secret-cookie");
    }

    @Test
    void rejectsCrossOriginRequest() {
        assertThatThrownBy(() -> client(NoAuthentication.INSTANCE, NiFiHttpClient.Config.defaults())
                .get(URI.create("http://other.example.com/nifi-api/flow/about"), "application/json"))
                .isInstanceOf(NiFiApiException.class)
                .hasMessageContaining("permitted NiFi origin");
    }

    @Test
    void retriesRetryableStatusThenSucceeds() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("busy"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        final NiFiHttpClient.Config fastRetry = new NiFiHttpClient.Config(
                Duration.ofSeconds(5), 1024, 3, Duration.ofMillis(1), Duration.ofMillis(5), 3);
        final NiFiHttpResponse response = client(NoAuthentication.INSTANCE, fastRetry)
                .get(resolver.resolve("/nifi-api/flow/about"), "application/json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void boundsResponseBodySize() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("x".repeat(2000)));
        final NiFiHttpClient.Config tinyBody = new NiFiHttpClient.Config(
                Duration.ofSeconds(5), 100, 0, Duration.ofMillis(1), Duration.ofMillis(5), 3);
        assertThatThrownBy(() -> client(NoAuthentication.INSTANCE, tinyBody)
                .get(resolver.resolve("/nifi-api/flow/about"), "application/json"))
                .isInstanceOf(NiFiApiException.class);
    }

    @Test
    void rejectsCrossOriginRedirect() {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "https://evil.example.com/steal"));
        assertThatThrownBy(() -> client(NoAuthentication.INSTANCE, NiFiHttpClient.Config.defaults())
                .get(resolver.resolve("/nifi-api/flow/about"), "application/json"))
                .isInstanceOf(NiFiApiException.class)
                .hasMessageContaining("Rejected redirect");
    }

    @Test
    void honorsRetryAfterHeaderWithinTheBackoffCeiling() {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "1").setBody("slow down"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        final NiFiHttpClient.Config cappedBackoff = new NiFiHttpClient.Config(
                Duration.ofSeconds(5), 1024, 3, Duration.ofMillis(1), Duration.ofMillis(5), 3);
        final NiFiHttpResponse response = client(NoAuthentication.INSTANCE, cappedBackoff)
                .get(resolver.resolve("/nifi-api/flow/about"), "application/json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void doesNotNegotiateContentEncoding() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        client(NoAuthentication.INSTANCE, NiFiHttpClient.Config.defaults())
                .get(resolver.resolve("/nifi-api/flow/about"), "application/json");
        assertThat(server.takeRequest().getHeader("Accept-Encoding")).isNull();
    }

    @Test
    void excerptCollapsesAndBounds() {
        assertThat(NiFiHttpClient.excerpt("  a\n b  ")).isEqualTo("a b");
        assertThat(NiFiHttpClient.excerpt(null)).isEmpty();
    }

    @Test
    void rejectsBlankAuthenticationCredentials() {
        assertThatThrownBy(() -> new BearerTokenAuthenticator(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BearerTokenAuthenticator("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthorizationBearerCookieAuthenticator(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthorizationBearerCookieAuthenticator("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifiesSuccessStatusBoundaries() {
        assertThat(new NiFiHttpResponse(199, null, new byte[0]).isSuccess()).isFalse();
        assertThat(new NiFiHttpResponse(200, null, new byte[0]).isSuccess()).isTrue();
        assertThat(new NiFiHttpResponse(299, null, new byte[0]).isSuccess()).isTrue();
        assertThat(new NiFiHttpResponse(300, null, new byte[0]).isSuccess()).isFalse();
    }
}

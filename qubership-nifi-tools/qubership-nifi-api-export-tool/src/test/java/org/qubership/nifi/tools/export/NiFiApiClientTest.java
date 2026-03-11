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
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for NiFiApiClient using OkHttp MockWebServer (plain HTTP).
 * Since NiFiApiClient accepts baseUrl as a constructor argument, we pass
 * the MockWebServer's HTTP address to avoid needing real HTTPS certificates.
 */
class NiFiApiClientTest {

    private MockWebServer server;
    private NiFiApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new NiFiApiClient(
            "http://localhost:" + server.getPort(), "admin", "testpass",
            HttpClient.newBuilder().build()
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void authenticate201StoresTokenUsedInSubsequentRequests() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("my-bearer-token"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        client.authenticate();
        client.get("/nifi-api/flow/about");

        server.takeRequest(); // consume auth request
        RecordedRequest getReq = server.takeRequest();
        assertEquals("Bearer my-bearer-token", getReq.getHeader("Authorization"));
    }

    @Test
    void authenticate200AlsoAccepted() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("token-from-200"));
        assertDoesNotThrow(() -> client.authenticate());
    }

    @Test
    void authenticateNon2xxThrows() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("Forbidden"));
        assertThrows(RuntimeException.class, () -> client.authenticate());
    }

    @Test
    void authenticateSendsFormEncodedCredentials() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("tok"));
        client.authenticate();

        RecordedRequest req = server.takeRequest();
        assertEquals("application/x-www-form-urlencoded", req.getHeader("Content-Type"));
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("username=admin"), "Body must contain username");
        assertTrue(body.contains("password=testpass"), "Body must contain password");
        assertEquals("/nifi-api/access/token", req.getPath());
    }

    @Test
    void get200ParsesJsonResponse() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("tok"));
        client.authenticate();

        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"about\":{\"version\":\"2.7.2\"}}"));
        JsonNode result = client.get("/nifi-api/flow/about");

        assertEquals("2.7.2", result.path("about").path("version").asText());
    }

    @Test
    void getNon200ThrowsRuntimeException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("tok"));
        client.authenticate();

        server.enqueue(new MockResponse().setResponseCode(404).setBody("Not Found"));
        assertThrows(RuntimeException.class, () -> client.get("/nifi-api/missing"));
    }

    @Test
    void post201ParsesJsonResponse() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("tok"));
        client.authenticate();

        server.enqueue(new MockResponse().setResponseCode(201)
                .setBody("{\"component\":{\"id\":\"abc\"}}"));
        JsonNode result = client.post("/nifi-api/process-groups/root/processors", "{\"type\":\"foo\"}");

        assertEquals("abc", result.path("component").path("id").asText());
    }

    @Test
    void post200AlsoAccepted() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("tok"));
        client.authenticate();

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
        assertDoesNotThrow(() -> client.post("/some/path", "{}"));
    }

    @Test
    void postNon2xxThrows() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("tok"));
        client.authenticate();

        server.enqueue(new MockResponse().setResponseCode(400).setBody("Bad Request"));
        assertThrows(RuntimeException.class, () -> client.post("/bad", "{}"));
    }

    @Test
    void delete200NoException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("tok"));
        client.authenticate();

        server.enqueue(new MockResponse().setResponseCode(200).setBody("OK"));
        assertDoesNotThrow(() -> client.delete("/nifi-api/processors/abc?version=0"));
    }

    @Test
    void deleteNon200LogsWarningNoException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("tok"));
        client.authenticate();

        server.enqueue(new MockResponse().setResponseCode(500).setBody("Server Error"));
        assertDoesNotThrow(() -> client.delete("/nifi-api/processors/abc?version=0"));
    }
}

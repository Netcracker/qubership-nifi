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
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.tools.nifi.common.auth.NoAuthentication;
import org.qubership.nifi.tools.nifi.common.http.NiFiApiException;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiRestClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiUriResolver;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NiFiComponentCatalogClientTest {

    private static final String JSON = "application/json";

    private MockWebServer server;
    private NiFiRestClient rest;
    private NiFiComponentCatalogClient catalog;
    private NiFiAboutClient about;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        final NiFiUriResolver resolver = NiFiUriResolver.fromBaseUrl(server.url("/").toString(), false);
        final CloseableHttpClient transport = NiFiHttpClient.newHttpClient(null, Duration.ofSeconds(5));
        final NiFiHttpClient http = new NiFiHttpClient(transport, resolver, NoAuthentication.INSTANCE);
        rest = new NiFiRestClient(http, new ObjectMapper());
        catalog = new NiFiComponentCatalogClient(rest, resolver);
        about = new NiFiAboutClient(rest, resolver);
    }

    @AfterEach
    void tearDown() throws IOException {
        rest.close();
        server.shutdown();
    }

    @Test
    void readsAndParsesVersion() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", JSON)
                .setBody("{\"about\":{\"version\":\"2.5.0\"}}"));
        assertThat(about.readVersion().orElseThrow().getMinor()).isEqualTo(5);
    }

    @Test
    void listsTypesArray() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", JSON)
                .setBody("{\"processorTypes\":[{\"type\":\"org.Foo\"}]}"));
        final JsonNode types = catalog.listTypes(NiFiComponentKind.PROCESSOR);
        assertThat(types.isArray()).isTrue();
        assertThat(types.get(0).path("type").asText()).isEqualTo("org.Foo");
    }

    @Test
    void failsWhenListArrayMissing() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", JSON)
                .setBody("{\"unexpected\":true}"));
        assertThatThrownBy(() -> catalog.listTypes(NiFiComponentKind.PROCESSOR))
                .isInstanceOf(NiFiApiException.class)
                .hasMessageContaining("processorTypes");
    }

    @Test
    void retainsUnknownDefinitionFields() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", JSON)
                .setBody("{\"type\":\"org.Foo\",\"futureField\":{\"nested\":1},\"additionalDetails\":true}"));
        final JsonNode def = catalog.getDefinition(NiFiComponentKind.PROCESSOR,
                "g", "a", "1.0", "org.Foo");
        assertThat(def.path("futureField").path("nested").asInt()).isEqualTo(1);
        assertThat(def.path("additionalDetails").asBoolean()).isTrue();
    }

    @Test
    void returnsAdditionalDetailsString() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", JSON)
                .setBody("{\"additionalDetails\":\"# Title\\nBody\"}"));
        final Optional<String> details = catalog.getAdditionalDetails(NiFiComponentKind.PROCESSOR,
                "g", "a", "1.0", "org.Foo");
        assertThat(details).contains("# Title\nBody");
    }

    @Test
    void treatsAdditionalDetailsNotFoundAsAbsent() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("missing"));
        assertThat(catalog.getAdditionalDetails(NiFiComponentKind.PROCESSOR, "g", "a", "1.0", "org.Foo"))
                .isEmpty();
    }

    @Test
    void failsWhenAdditionalDetailsFieldNotString() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", JSON)
                .setBody("{\"additionalDetails\":123}"));
        assertThatThrownBy(() -> catalog.getAdditionalDetails(NiFiComponentKind.PROCESSOR,
                "g", "a", "1.0", "org.Foo"))
                .isInstanceOf(NiFiApiException.class)
                .hasMessageContaining("string");
    }

    @Test
    void mapsUnauthorizedToTypedException() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("nope"));
        assertThatThrownBy(() -> catalog.listTypes(NiFiComponentKind.CONTROLLER_SERVICE))
                .isInstanceOfSatisfying(NiFiApiException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(401));
    }
}

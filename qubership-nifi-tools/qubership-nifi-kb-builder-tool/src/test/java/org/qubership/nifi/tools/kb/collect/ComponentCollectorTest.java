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

package org.qubership.nifi.tools.kb.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.tools.kb.model.ComponentRecord;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentCatalogClient;
import org.qubership.nifi.tools.nifi.common.auth.NoAuthentication;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiRestClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiUriResolver;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComponentCollectorTest {

    private static final String JSON = "application/json";
    private static final String PROC_BUNDLE =
            "\"bundle\":{\"group\":\"g\",\"artifact\":\"a\",\"version\":\"2.5.0\"}";

    private MockWebServer server;
    private NiFiRestClient rest;
    private NiFiComponentCatalogClient catalog;
    private ComponentCollector collector;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        final NiFiUriResolver resolver = NiFiUriResolver.fromBaseUrl(server.url("/").toString(), false);
        final CloseableHttpClient transport = NiFiHttpClient.newHttpClient(null, Duration.ofSeconds(5));
        final NiFiHttpClient http = new NiFiHttpClient(transport, resolver, NoAuthentication.INSTANCE);
        rest = new NiFiRestClient(http, new ObjectMapper());
        catalog = new NiFiComponentCatalogClient(rest, resolver);
        collector = new ComponentCollector();
    }

    @AfterEach
    void tearDown() throws IOException {
        rest.close();
        server.shutdown();
    }

    private void json(final String body) {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", JSON).setBody(body));
    }

    private void enqueueOneProcessor(final String definitionBody) {
        json("{\"processorTypes\":[{\"type\":\"org.P\"," + PROC_BUNDLE + "}]}");
        json(definitionBody);
    }

    private void enqueueEmptyRemaining() {
        json("{\"controllerServiceTypes\":[]}");
        json("{\"reportingTaskTypes\":[]}");
    }

    @Test
    void requestsAdditionalDetailsWhenAdvertisedAndAvailable() {
        enqueueOneProcessor("{\"type\":\"org.P\",\"additionalDetails\":true}");
        json("{\"additionalDetails\":\"# Details\"}");
        enqueueEmptyRemaining();

        final List<ComponentRecord> records = collector.collectAll(catalog);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).additionalDocumentation().isAvailable()).isTrue();
        assertThat(records.get(0).additionalDetailsContent()).contains("# Details");
    }

    @Test
    void doesNotRequestAdditionalDetailsWhenNotAdvertised() {
        enqueueOneProcessor("{\"type\":\"org.P\",\"additionalDetails\":false}");
        enqueueEmptyRemaining();

        final List<ComponentRecord> records = collector.collectAll(catalog);
        assertThat(records.get(0).additionalDocumentation().isAdvertised()).isFalse();
        assertThat(records.get(0).additionalDocumentation().isRequested()).isFalse();
    }

    @Test
    void treatsAdvertisedNotFoundAsUnavailable() {
        enqueueOneProcessor("{\"type\":\"org.P\",\"additionalDetails\":true}");
        server.enqueue(new MockResponse().setResponseCode(404).setBody("missing"));
        enqueueEmptyRemaining();

        final List<ComponentRecord> records = collector.collectAll(catalog);
        assertThat(records.get(0).additionalDocumentation().isAdvertised()).isTrue();
        assertThat(records.get(0).additionalDocumentation().isAvailable()).isFalse();
    }

    @Test
    void failsOnNonBooleanAdditionalDetails() {
        enqueueOneProcessor("{\"type\":\"org.P\",\"additionalDetails\":\"yes\"}");
        assertThatThrownBy(() -> collector.collectAll(catalog))
                .isInstanceOf(CollectionException.class).hasMessageContaining("non-Boolean");
    }

    @Test
    void failsOnMissingBundleCoordinates() {
        json("{\"processorTypes\":[{\"type\":\"org.P\"}]}");
        assertThatThrownBy(() -> collector.collectAll(catalog))
                .isInstanceOf(CollectionException.class).hasMessageContaining("bundle");
    }

    @Test
    void issuesOnlyGetRequests() throws InterruptedException {
        enqueueOneProcessor("{\"type\":\"org.P\",\"additionalDetails\":true}");
        json("{\"additionalDetails\":\"# Details\"}");
        enqueueEmptyRemaining();

        collector.collectAll(catalog);

        final int requestCount = server.getRequestCount();
        assertThat(requestCount).isPositive();
        for (int i = 0; i < requestCount; i++) {
            final RecordedRequest request = server.takeRequest();
            assertThat(request.getMethod()).isEqualTo("GET");
        }
    }
}

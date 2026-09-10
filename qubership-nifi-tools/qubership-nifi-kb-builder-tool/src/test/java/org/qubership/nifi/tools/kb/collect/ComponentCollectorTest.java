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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.tools.kb.docs.ComponentDocumentationCollector;
import org.qubership.nifi.tools.kb.model.AdditionalDocumentationState;
import org.qubership.nifi.tools.kb.model.ComponentRecord;
import org.qubership.nifi.tools.nifi.common.api.NiFi1xComponentMetadataProvider;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentCatalogClient;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentReference;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private ComponentRecord collectOneNiFi1x(final String typeEntry, final String providerDefinition)
            throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        final NiFiComponentReference reference = new NiFiComponentReference(NiFiComponentKind.PROCESSOR,
                "g", "a", "1.28.1", "org.P");
        final NiFi1xComponentMetadataProvider provider = mock(NiFi1xComponentMetadataProvider.class);
        when(provider.collect(reference)).thenReturn(mapper.readTree(providerDefinition));
        final ComponentDocumentationCollector documentation = mock(ComponentDocumentationCollector.class);
        when(documentation.collect(reference)).thenReturn(new ComponentDocumentationCollector.Page(
                "/nifi-docs/components/g/a/1.28.1/org.P/index.html", "# P", mapper.createObjectNode(),
                AdditionalDocumentationState.notAdvertised(), null, null));
        return collector.collectAll(List.of(new ComponentCollector.CatalogEntry(reference,
                mapper.readTree(typeEntry))), provider, documentation).getFirst();
    }

    @Test
    void niFi1xServiceReferenceDropsTheServiceInstancesItsProviderLists() throws IOException {
        final ComponentRecord component = collectOneNiFi1x("{\"type\":\"org.P\"}", """
                {"type":"org.P","propertyDescriptors":{
                "choice":{"allowableValues":[{"allowableValue":{"value":"fast"}}]},
                "service":{"identifiesControllerService":"org.Api",
                "allowableValues":[{"allowableValue":{"value":"instance-id"}}]}}}""");

        final JsonNode descriptors = component.definition().path("propertyDescriptors");
        assertThat(descriptors.path("service").get("allowableValues")).isNull();
        assertThat(descriptors.path("choice").path("allowableValues")).hasSize(1);
    }

    @Test
    void niFi1xDefinitionCarriesTheRestrictionsOfItsTypeEntry() throws IOException {
        final ComponentRecord component = collectOneNiFi1x("""
                {"type":"org.P","usageRestriction":"Reads local files",
                "explicitRestrictions":[{"requiredPermission":{"id":"read-filesystem"},
                "explanation":"Reads any file"}]}""", "{\"type\":\"org.P\",\"propertyDescriptors\":{}}");

        assertThat(component.definition().path("usageRestriction").asText()).isEqualTo("Reads local files");
        assertThat(component.definition().path("explicitRestrictions").path(0).path("requiredPermission")
                .path("id").asText()).isEqualTo("read-filesystem");
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
    void treatsBlankAdvertisedDetailsAsUnavailable() {
        enqueueOneProcessor("{\"type\":\"org.P\",\"additionalDetails\":true}");
        json("{\"additionalDetails\":\"   \"}");
        enqueueEmptyRemaining();

        final List<ComponentRecord> records = collector.collectAll(catalog);
        assertThat(records.get(0).additionalDocumentation().isAdvertised()).isTrue();
        assertThat(records.get(0).additionalDocumentation().isAvailable()).isFalse();
        assertThat(records.get(0).additionalDetailsContent()).isEmpty();
    }

    @Test
    void failsWhenTheDefinitionCarriesNoType() {
        enqueueOneProcessor("{}");

        assertThatThrownBy(() -> collector.collectAll(catalog))
                .isInstanceOf(CollectionException.class).hasMessageContaining("carries no type");
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

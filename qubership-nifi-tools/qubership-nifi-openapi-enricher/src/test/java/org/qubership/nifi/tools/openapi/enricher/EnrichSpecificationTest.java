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
package org.qubership.nifi.tools.openapi.enricher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichSpecificationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode load(String resourceName) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/" + resourceName)) {
            assertNotNull(in, "Test resource not found: " + resourceName);
            return MAPPER.readTree(in);
        }
    }

    private JsonNode enrich(String resourceName) throws IOException {
        return new EnrichSpecification().enrichNiFi(load(resourceName));
    }

    @Test
    void enrichNiFiAddsServersWhenMissing() throws IOException {
        JsonNode result = enrich("spec-no-servers.json");
        assertTrue(result.path("servers").isArray());
        assertEquals(1, result.path("servers").size());
        assertEquals("/nifi-api", result.path("servers").get(0).path("url").asText());
    }

    @Test
    void enrichNiFiDoesNotReplaceExistingServers() throws IOException {
        JsonNode result = enrich("spec-with-servers.json");
        assertEquals(1, result.path("servers").size());
        assertEquals("https://example.com", result.path("servers").get(0).path("url").asText());
    }

    @Test
    void enrichNiFiCopiesDescriptionFromSummaryWhenMissing() throws IOException {
        JsonNode result = enrich("spec-no-servers.json");
        assertEquals("Get about info",
                result.path("paths").path("/flow/about").path("get").path("description").asText());
    }

    @Test
    void enrichNiFiDoesNotOverwriteExistingDescription() throws IOException {
        JsonNode result = enrich("spec-multi-path.json");
        assertEquals("Pre-existing description",
                result.path("paths").path("/processors/{id}").path("get").path("description").asText());
    }

    @Test
    void enrichNiFiAddsDescriptionToAllPathsAndMethods() throws IOException {
        JsonNode result = enrich("spec-multi-path.json");
        assertEquals("Get about info",
                result.path("paths").path("/flow/about").path("get").path("description").asText());
        assertEquals("Delete processor",
                result.path("paths").path("/processors/{id}").path("delete").path("description").asText());
    }

    @Test
    void enrichNiFiAdds200ResponseDescription() throws IOException {
        JsonNode result = enrich("spec-response-codes.json");
        JsonNode responses = result.path("paths").path("/processors").path("post").path("responses");
        assertEquals("successful operation", responses.path("200").path("description").asText());
    }

    @Test
    void enrichNiFiAdds201ResponseDescription() throws IOException {
        JsonNode result = enrich("spec-response-codes.json");
        JsonNode responses = result.path("paths").path("/processors").path("post").path("responses");
        assertEquals("successful operation", responses.path("201").path("description").asText());
    }

    @Test
    void enrichNiFiAdds202ResponseDescription() throws IOException {
        JsonNode result = enrich("spec-response-codes.json");
        JsonNode responses = result.path("paths").path("/processors").path("post").path("responses");
        assertEquals("successful operation", responses.path("202").path("description").asText());
    }

    @Test
    void enrichNiFiAddsDefaultResponseDescription() throws IOException {
        JsonNode result = enrich("spec-response-codes.json");
        JsonNode responses = result.path("paths").path("/processors").path("post").path("responses");
        assertEquals("successful operation", responses.path("default").path("description").asText());
    }

    @Test
    void enrichNiFiDoesNotAdd404ResponseDescription() throws IOException {
        JsonNode result = enrich("spec-response-codes.json");
        JsonNode responses = result.path("paths").path("/processors").path("post").path("responses");
        assertTrue(responses.path("404").path("description").isMissingNode());
    }

    @Test
    void enrichNiFiDoesNotAdd400ResponseDescription() throws IOException {
        JsonNode result = enrich("spec-response-codes.json");
        JsonNode responses = result.path("paths").path("/processors").path("post").path("responses");
        assertTrue(responses.path("400").path("description").isMissingNode());
    }

    @Test
    void enrichNiFiDoesNotOverwriteExistingResponseDescription() throws IOException {
        JsonNode result = enrich("spec-no-servers.json");
        JsonNode responses = result.path("paths").path("/flow/about").path("get").path("responses");
        assertEquals("Not found", responses.path("404").path("description").asText());
    }

    @Test
    void enrichNiFiAddsTagsWhenMissing() throws IOException {
        JsonNode result = enrich("spec-no-servers.json");
        assertTrue(result.path("tags").isArray());
        assertEquals(28, result.path("tags").size());
    }

    @Test
    void enrichNiFiDoesNotReplaceExistingTags() throws IOException {
        JsonNode result = enrich("spec-with-tags.json");
        assertEquals(1, result.path("tags").size());
        assertEquals("Flow", result.path("tags").get(0).path("name").asText());
        assertEquals("Custom Flow description", result.path("tags").get(0).path("description").asText());
    }

    @Test
    void enrichNiFiReturnsSameNodeInstance() throws IOException {
        JsonNode spec = load("spec-no-servers.json");
        JsonNode result = new EnrichSpecification().enrichNiFi(spec);
        assertSame(spec, result);
    }
}

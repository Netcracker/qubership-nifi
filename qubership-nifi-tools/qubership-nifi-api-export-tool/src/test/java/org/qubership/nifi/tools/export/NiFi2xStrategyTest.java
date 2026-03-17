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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NiFi2xStrategyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NiFiApiClient apiClient;
    private NiFi2xStrategy strategy;

    @BeforeEach
    void setUp() {
        apiClient = mock(NiFiApiClient.class);
        strategy = new NiFi2xStrategy(apiClient);
    }

    @Test
    void collectProcessorCallsDefinitionEndpointAndReturnsDescriptors() throws Exception {
        String processorTypesJson = "{\"processorTypes\":[{\"type\":\"org.foo.Bar\","
                + "\"bundle\":{\"group\":\"g\",\"artifact\":\"a\",\"version\":\"1.0\"}}]}";
        when(apiClient.get("/nifi-api/flow/processor-types")).thenReturn(MAPPER.readTree(processorTypesJson));
        when(apiClient.get("/nifi-api/flow/processor-definition/g/a/1.0/org.foo.Bar")).thenReturn(MAPPER.readTree(
                "{\"propertyDescriptors\":{\"prop1\":{\"name\":\"prop1\",\"displayName\":\"Prop One\"}}}"));

        List<Map<String, Object>> result = strategy.collect(ComponentKind.PROCESSOR);

        assertEquals(1, result.size());
        assertEquals("org.foo.Bar", result.get(0).get("type"));
        JsonNode descriptors = (JsonNode) result.get(0).get("propertyDescriptors");
        assertEquals("prop1", descriptors.path("prop1").path("name").asText());
    }

    @Test
    void collectControllerServiceCallsCorrectEndpoints() throws Exception {
        String csTypesJson = "{\"controllerServiceTypes\":[{\"type\":\"org.foo.Svc\","
                + "\"bundle\":{\"group\":\"g\",\"artifact\":\"a\",\"version\":\"2.0\"}}]}";
        when(apiClient.get("/nifi-api/flow/controller-service-types")).thenReturn(MAPPER.readTree(csTypesJson));
        when(apiClient.get("/nifi-api/flow/controller-service-definition/g/a/2.0/org.foo.Svc"))
                .thenReturn(MAPPER.readTree("{\"propertyDescriptors\":{}}"));

        List<Map<String, Object>> result = strategy.collect(ComponentKind.CONTROLLER_SERVICE);

        assertEquals(1, result.size());
        assertEquals("org.foo.Svc", result.get(0).get("type"));
        verify(apiClient).get("/nifi-api/flow/controller-service-definition/g/a/2.0/org.foo.Svc");
    }

    @Test
    void collectReportingTaskCallsCorrectEndpoints() throws Exception {
        String rtTypesJson = "{\"reportingTaskTypes\":[{\"type\":\"org.foo.Task\","
                + "\"bundle\":{\"group\":\"g\",\"artifact\":\"a\",\"version\":\"3.0\"}}]}";
        when(apiClient.get("/nifi-api/flow/reporting-task-types")).thenReturn(MAPPER.readTree(rtTypesJson));
        when(apiClient.get("/nifi-api/flow/reporting-task-definition/g/a/3.0/org.foo.Task")).thenReturn(MAPPER.readTree(
                "{\"propertyDescriptors\":{}}"));

        List<Map<String, Object>> result = strategy.collect(ComponentKind.REPORTING_TASK);

        assertEquals(1, result.size());
        assertEquals("org.foo.Task", result.get(0).get("type"));
        verify(apiClient).get("/nifi-api/flow/reporting-task-definition/g/a/3.0/org.foo.Task");
    }

    @Test
    void collectSkipsComponentWhenDefinitionCallFails() throws Exception {
        when(apiClient.get("/nifi-api/flow/processor-types")).thenReturn(MAPPER.readTree(
                "{\"processorTypes\":["
                + "{\"type\":\"org.foo.A\",\"bundle\":{\"group\":\"g\",\"artifact\":\"a\",\"version\":\"1.0\"}},"
                + "{\"type\":\"org.foo.B\",\"bundle\":{\"group\":\"g\",\"artifact\":\"a\",\"version\":\"1.0\"}}"
                + "]}"));
        when(apiClient.get("/nifi-api/flow/processor-definition/g/a/1.0/org.foo.A"))
                .thenThrow(new RuntimeException("404 Not Found"));
        when(apiClient.get("/nifi-api/flow/processor-definition/g/a/1.0/org.foo.B"))
                .thenReturn(MAPPER.readTree("{\"propertyDescriptors\":{}}"));

        List<Map<String, Object>> result = strategy.collect(ComponentKind.PROCESSOR);

        assertEquals(1, result.size());
        assertEquals("org.foo.B", result.get(0).get("type"));
    }

    @Test
    void collectReturnsEmptyWhenResponseKeyMissing() throws Exception {
        when(apiClient.get("/nifi-api/flow/processor-types")).thenReturn(MAPPER.readTree("{}"));

        List<Map<String, Object>> result = strategy.collect(ComponentKind.PROCESSOR);

        assertTrue(result.isEmpty());
    }

    @Test
    void collectReturnsMultipleComponents() throws Exception {
        when(apiClient.get("/nifi-api/flow/processor-types")).thenReturn(MAPPER.readTree(
                "{\"processorTypes\":["
                + "{\"type\":\"org.foo.P1\",\"bundle\":{\"group\":\"g\",\"artifact\":\"a\",\"version\":\"1.0\"}},"
                + "{\"type\":\"org.foo.P2\",\"bundle\":{\"group\":\"g\",\"artifact\":\"b\",\"version\":\"2.0\"}}"
                + "]}"));
        when(apiClient.get("/nifi-api/flow/processor-definition/g/a/1.0/org.foo.P1"))
                .thenReturn(MAPPER.readTree("{\"propertyDescriptors\":{}}"));
        when(apiClient.get("/nifi-api/flow/processor-definition/g/b/2.0/org.foo.P2"))
                .thenReturn(MAPPER.readTree("{\"propertyDescriptors\":{}}"));

        List<Map<String, Object>> result = strategy.collect(ComponentKind.PROCESSOR);

        assertEquals(2, result.size());
    }
}

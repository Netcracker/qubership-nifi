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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NiFi1xStrategyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NiFiApiClient apiClient;
    private NiFi1xStrategy strategy;

    @BeforeEach
    void setUp() {
        apiClient = mock(NiFiApiClient.class);
        strategy = new NiFi1xStrategy(apiClient);
    }

    @Test
    void collectProcessorCreatesInstanceAndDeletesIt() throws Exception {
        when(apiClient.get("/nifi-api/flow/processor-types")).thenReturn(MAPPER.readTree(
                "{\"processorTypes\":[{\"type\":\"org.foo.MyProc\"}]}"));
        String processorJson = "{\"component\":{\"id\":\"abc\","
                + "\"config\":{\"descriptors\":{\"prop1\":{\"name\":\"prop1\"}}}},\"revision\":{\"version\":3}}";
        when(apiClient.post(eq("/nifi-api/process-groups/root/processors"), anyString()))
                .thenReturn(MAPPER.readTree(processorJson));

        List<Map<String, Object>> result = strategy.collect(ComponentKind.PROCESSOR);

        assertEquals(1, result.size());
        assertEquals("org.foo.MyProc", result.get(0).get("type"));
        JsonNode descriptors = (JsonNode) result.get(0).get("propertyDescriptors");
        assertEquals("prop1", descriptors.path("prop1").path("name").asText());
        verify(apiClient).delete("/nifi-api/processors/abc?version=3");
    }

    @Test
    void collectControllerServiceCreatesAndDeletes() throws Exception {
        when(apiClient.get("/nifi-api/flow/controller-service-types")).thenReturn(MAPPER.readTree(
                "{\"controllerServiceTypes\":[{\"type\":\"org.foo.MySvc\"}]}"));
        when(apiClient.post(eq("/nifi-api/process-groups/root/controller-services"), anyString()))
                .thenReturn(MAPPER.readTree(
                        "{\"component\":{\"id\":\"def\",\"descriptors\":{\"p\":{}}},\"revision\":{\"version\":5}}"));

        List<Map<String, Object>> result = strategy.collect(ComponentKind.CONTROLLER_SERVICE);

        assertEquals(1, result.size());
        assertEquals("org.foo.MySvc", result.get(0).get("type"));
        verify(apiClient).delete("/nifi-api/controller-services/def?version=5");
    }

    @Test
    void collectReportingTaskCreatesAndDeletes() throws Exception {
        when(apiClient.get("/nifi-api/flow/reporting-task-types")).thenReturn(MAPPER.readTree(
                "{\"reportingTaskTypes\":[{\"type\":\"org.foo.MyTask\"}]}"));
        when(apiClient.post(eq("/nifi-api/controller/reporting-tasks"), anyString())).thenReturn(MAPPER.readTree(
                "{\"component\":{\"id\":\"ghi\",\"descriptors\":{}},\"revision\":{\"version\":7}}"));

        List<Map<String, Object>> result = strategy.collect(ComponentKind.REPORTING_TASK);

        assertEquals(1, result.size());
        assertEquals("org.foo.MyTask", result.get(0).get("type"));
        verify(apiClient).delete("/nifi-api/reporting-tasks/ghi?version=7");
    }

    @Test
    void collectSkipsComponentWhenPostThrows() throws Exception {
        when(apiClient.get("/nifi-api/flow/processor-types")).thenReturn(MAPPER.readTree(
                "{\"processorTypes\":["
                + "{\"type\":\"org.foo.A\"},"
                + "{\"type\":\"org.foo.B\"}"
                + "]}"));
        when(apiClient.post(eq("/nifi-api/process-groups/root/processors"), contains("org.foo.A")))
                .thenThrow(new RuntimeException("Create failed"));
        when(apiClient.post(eq("/nifi-api/process-groups/root/processors"), contains("org.foo.B")))
                .thenReturn(MAPPER.readTree(
                        "{\"component\":{\"id\":\"xyz\",\"config\":{\"descriptors\":{}}},"
                        + "\"revision\":{\"version\":0}}"));

        List<Map<String, Object>> result = strategy.collect(ComponentKind.PROCESSOR);

        assertEquals(1, result.size());
        assertEquals("org.foo.B", result.get(0).get("type"));
    }

    @Test
    void collectReturnsEmptyWhenListKeyMissing() throws Exception {
        when(apiClient.get("/nifi-api/flow/processor-types")).thenReturn(MAPPER.readTree("{}"));

        List<Map<String, Object>> result = strategy.collect(ComponentKind.PROCESSOR);

        assertTrue(result.isEmpty());
        verify(apiClient, never()).post(anyString(), anyString());
    }

    @Test
    void collectPostBodyContainsFqcn() throws Exception {
        when(apiClient.get("/nifi-api/flow/processor-types")).thenReturn(MAPPER.readTree(
                "{\"processorTypes\":[{\"type\":\"org.apache.nifi.processors.standard.GenerateFlowFile\"}]}"));
        when(apiClient.post(eq("/nifi-api/process-groups/root/processors"),
                contains("org.apache.nifi.processors.standard.GenerateFlowFile")))
                .thenReturn(MAPPER.readTree(
                        "{\"component\":{\"id\":\"q\",\"config\":{\"descriptors\":{}}},\"revision\":{\"version\":0}}"));

        strategy.collect(ComponentKind.PROCESSOR);

        verify(apiClient).post(
                eq("/nifi-api/process-groups/root/processors"),
                contains("org.apache.nifi.processors.standard.GenerateFlowFile"));
    }
}

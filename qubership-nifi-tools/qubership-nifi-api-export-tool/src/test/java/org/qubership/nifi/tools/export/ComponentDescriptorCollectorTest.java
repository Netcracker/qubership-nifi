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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that ComponentDescriptorCollector detects the NiFi version from /nifi-api/flow/about
 * and delegates to the correct strategy (2.x uses definition endpoints; 1.x uses create/delete cycle).
 */
class ComponentDescriptorCollectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void version2xUsesDefinitionEndpointsNotCreateDelete() throws Exception {
        NiFiApiClient apiClient = mock(NiFiApiClient.class);
        when(apiClient.get("/nifi-api/flow/about"))
                .thenReturn(MAPPER.readTree("{\"about\":{\"version\":\"2.7.2\"}}"));
        String processorTypesJson = "{\"processorTypes\":[{\"type\":\"org.foo.P\","
                + "\"bundle\":{\"group\":\"g\",\"artifact\":\"a\",\"version\":\"1.0\"}}]}";
        when(apiClient.get("/nifi-api/flow/processor-types"))
                .thenReturn(MAPPER.readTree(processorTypesJson));
        when(apiClient.get(contains("processor-definition")))
                .thenReturn(MAPPER.readTree("{\"descriptors\":{}}"));

        ComponentDescriptorCollector collector = new ComponentDescriptorCollector(apiClient);
        collector.collect(ComponentKind.PROCESSOR);

        verify(apiClient, atLeastOnce()).get(contains("processor-definition"));
        verify(apiClient, never()).post(anyString(), anyString());
    }

    @Test
    void version1xUsesCreateDeleteNotDefinitionEndpoints() throws Exception {
        NiFiApiClient apiClient = mock(NiFiApiClient.class);
        when(apiClient.get("/nifi-api/flow/about"))
                .thenReturn(MAPPER.readTree("{\"about\":{\"version\":\"1.28.1\"}}"));
        when(apiClient.get("/nifi-api/flow/processor-types"))
                .thenReturn(MAPPER.readTree("{\"processorTypes\":[{\"type\":\"org.foo.P\"}]}"));
        when(apiClient.post(eq("/nifi-api/process-groups/root/processors"), anyString()))
                .thenReturn(MAPPER.readTree(
                        "{\"component\":{\"id\":\"x\",\"config\":{\"descriptors\":{}}},\"revision\":{\"version\":0}}"));

        ComponentDescriptorCollector collector = new ComponentDescriptorCollector(apiClient);
        collector.collect(ComponentKind.PROCESSOR);

        verify(apiClient, atLeastOnce()).post(eq("/nifi-api/process-groups/root/processors"), anyString());
        verify(apiClient, never()).get(contains("processor-definition"));
    }

    @Test
    void version200TreatedAs2x() throws Exception {
        NiFiApiClient apiClient = mock(NiFiApiClient.class);
        when(apiClient.get("/nifi-api/flow/about"))
                .thenReturn(MAPPER.readTree("{\"about\":{\"version\":\"2.0.0\"}}"));
        when(apiClient.get("/nifi-api/flow/processor-types"))
                .thenReturn(MAPPER.readTree("{\"processorTypes\":[]}"));

        ComponentDescriptorCollector collector = new ComponentDescriptorCollector(apiClient);
        List<Map<String, Object>> result = collector.collect(ComponentKind.PROCESSOR);

        assertTrue(result.isEmpty());
        verify(apiClient, never()).post(anyString(), anyString());
    }

    @Test
    void version1210TreatedAs1x() throws Exception {
        NiFiApiClient apiClient = mock(NiFiApiClient.class);
        when(apiClient.get("/nifi-api/flow/about"))
                .thenReturn(MAPPER.readTree("{\"about\":{\"version\":\"1.21.0\"}}"));
        when(apiClient.get("/nifi-api/flow/processor-types"))
                .thenReturn(MAPPER.readTree("{\"processorTypes\":[]}"));

        ComponentDescriptorCollector collector = new ComponentDescriptorCollector(apiClient);
        List<Map<String, Object>> result = collector.collect(ComponentKind.PROCESSOR);

        assertTrue(result.isEmpty());
        verify(apiClient, never()).get(contains("processor-definition"));
    }

    @Test
    void collectReturnsResultsFromStrategy() throws Exception {
        NiFiApiClient apiClient = mock(NiFiApiClient.class);
        when(apiClient.get("/nifi-api/flow/about"))
                .thenReturn(MAPPER.readTree("{\"about\":{\"version\":\"2.7.2\"}}"));
        String csTypesJson = "{\"controllerServiceTypes\":[{\"type\":\"org.foo.Svc\","
                + "\"bundle\":{\"group\":\"g\",\"artifact\":\"a\",\"version\":\"1.0\"}}]}";
        when(apiClient.get("/nifi-api/flow/controller-service-types"))
                .thenReturn(MAPPER.readTree(csTypesJson));
        when(apiClient.get(contains("controller-service-definition")))
                .thenReturn(MAPPER.readTree("{\"descriptors\":{\"p1\":{}}}"));

        ComponentDescriptorCollector collector = new ComponentDescriptorCollector(apiClient);
        List<Map<String, Object>> result = collector.collect(ComponentKind.CONTROLLER_SERVICE);

        assertEquals(1, result.size());
        assertEquals("org.foo.Svc", result.get(0).get("type"));
    }
}

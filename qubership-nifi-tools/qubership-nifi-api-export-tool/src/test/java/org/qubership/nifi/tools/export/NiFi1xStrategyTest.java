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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;
import org.qubership.nifi.tools.nifi.common.api.NiFiCleanupException;
import org.qubership.nifi.tools.nifi.common.http.NiFiRestClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiUriResolver;
import java.net.URI;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class NiFi1xStrategyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sharedProviderPreservesExportShapeForEveryKind() throws Exception {
        for (var kind : NiFiComponentKind.values()) {
            NiFiApiClient api = configured(kind);
            var output = new NiFi1xStrategy(api).collect(kind);
            assertEquals(1, output.size());
            assertEquals(Set.of("type", "propertyDescriptors"), output.getFirst().keySet());
            assertEquals("org.example.Component", output.getFirst().get("type"));
            verify(api.restClient()).delete(URI.create("https://nifi/nifi-api/process-groups/group?version=0"));
            verify(api.restClient(), times(2)).delete(any());
        }
    }

    @Test
    void cleanupFailureFailsTheExport() throws Exception {
        NiFiApiClient api = configured(NiFiComponentKind.PROCESSOR);
        NiFiRestClient rest = api.restClient();
        doThrow(new IllegalStateException("403")).when(rest).delete(any());
        assertThrows(NiFiCleanupException.class, () -> new NiFi1xStrategy(api).collect(NiFiComponentKind.PROCESSOR));
    }

    static NiFiApiClient configured(final NiFiComponentKind kind) throws Exception {
        NiFiApiClient api = mock(NiFiApiClient.class);
        NiFiRestClient rest = mock(NiFiRestClient.class);
        var resolver = NiFiUriResolver.fromBaseUrl("https://nifi", true);
        when(api.restClient()).thenReturn(rest);
        when(api.resolver()).thenReturn(resolver);
        when(rest.getJson(resolver.resolve(kind.getListPath()))).thenReturn(JSON.readTree("{\"" + kind.getListKey()
                + "\":[{\"type\":\"org.example.Component\",\"bundle\":{\"g"
                        + "roup\":\"g\",\"artifact\":\"a\",\"version\":\"1.28.1\"}}]}"));
        when(rest.getJson(resolver.resolve("/nifi-api/process-groups/root")))
                .thenReturn(JSON.readTree("{\"component\":{\"id\":\"root-id\"}}"));
        when(rest.postJson(any(), anyString())).thenAnswer(call -> {
            ObjectNode entity = (ObjectNode) JSON.readTree((String) call.getArgument(1));
            ObjectNode component = (ObjectNode) entity.path("component");
            component.put("id", component.has("type") ? "child" : "group");
            if (component.has("type")) {
                component.putObject("descriptors");
                component.putObject("config").putObject("descriptors");
                component.putArray("relationships");
            }
            return entity;
        });
        return api;
    }
}

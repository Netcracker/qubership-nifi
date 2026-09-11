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
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class ComponentDescriptorCollectorTest {
    @Test
    void versionDetectionSelectsSharedProviders() throws Exception {
        for (String version : new String[]{"1.28.1", "2.7.2", "2.0.0"}) {
            var api = NiFi1xStrategyTest.configured(NiFiComponentKind.PROCESSOR);
            var json = new ObjectMapper();
            when(api.restClient().getJson(api.resolver().resolve("/nifi-api/flow/about")))
                    .thenReturn(json.readTree("{\"about\":{\"version\":\"" + version + "\"}}"));
            when(api.restClient().getJson(api.resolver()
                    .resolve("/nifi-api/flow/processor-definition/g/a/1.28.1/org.example.Component")))
                    .thenReturn(json.readTree("{\"propertyDescriptors\":{}}"));
            assertEquals(1, new ComponentDescriptorCollector(api).collect(NiFiComponentKind.PROCESSOR).size());
            if (version.startsWith("2.")) {
                verify(api.restClient(), never()).postJson(any(), anyString());
                verify(api.restClient(), never()).delete(any());
            } else {
                verify(api.restClient(), times(2)).postJson(any(), anyString());
                verify(api.restClient(), times(2)).delete(any());
            }
        }
    }
}

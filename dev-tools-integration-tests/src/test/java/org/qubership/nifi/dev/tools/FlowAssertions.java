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
package org.qubership.nifi.dev.tools;

import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assertion helpers for NiFi flow JSON produced by the update scripts.
 */
final class FlowAssertions {

    private FlowAssertions() {
    }

    /**
     * Returns {@code true} if the flow was transformed to NiFi 2.x format.
     * Detected by the presence of the 2.x JoltTransformJSON package name.
     *
     * @param flowContents the {@code flowContents} node from the exported flow JSON
     * @return {@code true} if the flow was transformed to NiFi 2.x
     */
    static boolean isTransformed(final JsonNode flowContents) {
        for (JsonNode proc : flowContents.path("processors")) {
            String name = proc.path("name").asText();
            if ("JoltTransform".equals(name)) {
                return proc.path("type").asText().contains("jolt.JoltTransformJSON");
            }
        }
        //
        return false;
    }

    /**
     * Asserts a flow that was transformed to NiFi 2.x has the expected processor types and properties.
     *
     * @param flowContents the {@code flowContents} node from the exported flow JSON
     */
    static void assertTransformed(final JsonNode flowContents) {
        boolean foundJolt = false;
        for (JsonNode proc : flowContents.path("processors")) {
            String type = proc.path("type").asText();
            String name = proc.path("name").asText();
            if ("JoltTransform".equals(name)) {
                foundJolt = true;
                assertEquals("org.apache.nifi.processors.jolt.JoltTransformJSON",
                        type, "JoltTransformJSON type should be "
                                + "org.apache.nifi.processors.jolt.JoltTransformJSON");
                assertEquals("nifi-jolt-nar",
                    proc.path("bundle").path("artifact").asText(),
                    "JoltTransformJSON artifact should be updated to nifi-jolt-nar");
                JsonNode props = proc.path("properties");
                assertTrue(props.has("Jolt Specification"),
                    "properties should contain 'Jolt Specification' after transformation");
                assertTrue(props.has("Jolt Transform"),
                    "properties should contain 'Jolt Transform' after transformation");
                assertTrue(props.has("Pretty Print"),
                    "properties should contain 'Pretty Print' after transformation");
            }
        }
        assertTrue(foundJolt, "Flow must contain JoltTransform processor");

        for (JsonNode svc : flowContents.path("controllerServices")) {
            String type = svc.path("type").asText();
            String name = svc.path("name").asText();
            if ("DistributedMapCacheServer".equals(name)) {
                assertFalse(type.contains("Distributed"),
                        "Controller service type '" + type
                                + "' should not contain 'Distributed' after transformation");
            }
            if ("DistributedMapCacheClient".equals(name)) {
                assertFalse(type.contains("Distributed"),
                        "Controller service type '" + type
                                + "' should not contain 'Distributed' after transformation");
            }
        }
    }

    /**
     * Asserts a flow that was NOT transformed (NiFi 1.x target) still has the original types.
     *
     * @param flowContents the {@code flowContents} node from the exported flow JSON
     */
    static void assertUntransformed(final JsonNode flowContents) {
        boolean foundJolt = false;
        for (JsonNode proc : flowContents.path("processors")) {
            String type = proc.path("type").asText();
            String name = proc.path("name").asText();
            if ("JoltTransform".equals(name)) {
                foundJolt = true;
                assertEquals("org.apache.nifi.processors.standard.JoltTransformJSON",
                        type, "JoltTransformJSON type should be "
                                + "org.apache.nifi.processors.standard.JoltTransformJSON");
                JsonNode props = proc.path("properties");
                assertTrue(props.has("jolt-spec"),
                        "properties should contain 'jolt-spec'");
                assertTrue(props.has("jolt-transform"),
                        "properties should contain 'jolt-transform'");
                assertTrue(props.has("pretty_print"),
                        "properties should contain 'pretty_print'");
            }
        }
        assertTrue(foundJolt, "Flow must contain JoltTransform processor");
        for (JsonNode svc : flowContents.path("controllerServices")) {
            String type = svc.path("type").asText();
            String name = svc.path("name").asText();
            if ("DistributedMapCacheServer".equals(name)) {
                assertTrue(type.contains("Distributed"),
                        "Controller service type '" + type
                                + "' should still contain 'Distributed' for 1.x target");
            }
            if ("DistributedMapCacheClient".equals(name)) {
                assertTrue(type.contains("Distributed"),
                        "Controller service type '" + type
                                + "' should still contain 'Distributed' for 1.x target");
            }
        }
    }
}

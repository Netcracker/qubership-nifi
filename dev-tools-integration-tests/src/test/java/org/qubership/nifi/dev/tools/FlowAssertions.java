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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
     * Asserts that the external controller service reference was rewritten to the target NiFi
     * controller service id everywhere it appears, while the in-flow (non-external) record
     * reader reference is left unchanged.
     *
     * @param snapshot       the full flow-export snapshot node (with {@code flowContents} and
     *                       {@code externalControllerServices})
     * @param expectedCsId   the target NiFi controller service id the reference must now use
     * @param inFlowReaderId the id of the in-flow record reader that must remain unchanged
     */
    static void assertExternalCsRewritten(final JsonNode snapshot,
                                          final String expectedCsId,
                                          final String inFlowReaderId) {
        JsonNode ext = snapshot.path("externalControllerServices");
        assertTrue(ext.has(expectedCsId),
                "externalControllerServices must be re-keyed to the target CS id " + expectedCsId
                        + " but was: " + ext.toString());
        assertEquals(expectedCsId, ext.path(expectedCsId).path("identifier").asText(),
                "external controller service identifier must be rewritten to the target CS id");

        // Assert on property values rather than fixed key names: the 2.x property migration may
        // rename put-record-sink / put-record-reader (e.g. to "Record Destination Service" /
        // "Record Reader" from 2.7), so the destination-service and reader references must be
        // matched by value. The external sink reference must now be the target CS id, and the
        // in-flow reader id must be left unchanged.
        JsonNode putRecord = findProcessorByName(snapshot.path("flowContents"), "PutRecord");
        assertNotNull(putRecord, "flow must contain a PutRecord processor");
        JsonNode props = putRecord.path("properties");
        assertEquals(1, countPropertyValue(props, expectedCsId),
                "PutRecord must reference the target CS id " + expectedCsId
                        + " exactly once after the external CS rewrite, but properties were: " + props);
        assertEquals(1, countPropertyValue(props, inFlowReaderId),
                "in-flow record reader id " + inFlowReaderId
                        + " must be left unchanged on PutRecord, but properties were: " + props);
    }

    private static int countPropertyValue(final JsonNode properties, final String value) {
        int count = 0;
        for (JsonNode propValue : properties) {
            if (propValue.isTextual() && value.equals(propValue.asText())) {
                count++;
            }
        }
        return count;
    }

    private static JsonNode findProcessorByName(final JsonNode flowContents, final String name) {
        for (JsonNode proc : flowContents.path("processors")) {
            if (name.equals(proc.path("name").asText())) {
                return proc;
            }
        }
        return null;
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

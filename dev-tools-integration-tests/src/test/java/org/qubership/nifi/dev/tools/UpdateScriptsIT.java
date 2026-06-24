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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.NifiFlowApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for the default (no-flag) run of the update scripts, where all three updates
 * (external controller services, component versions, and properties) run together. It transforms
 * the test flows, imports the result into a live NiFi instance via NiFi Registry, and validates the
 * imported components. The per-flag behavior is covered by {@code UpdateScriptsExternalCsIT},
 * {@code UpdateScriptsVersionsIT}, and {@code UpdateScriptsPropertiesIT}.
 *
 * <p>Shared setup, the scripts container, and the import/validation helpers live in
 * {@link UpdateScriptsTestHarness}.
 *
 * <p>Requires the {@code nifi.cert.dir} system property (otherwise the test is skipped) and the
 * {@code NIFI_CLIENT_PASSWORD} environment variable. Optional system properties: {@code nifi.url},
 * {@code nifi.registry.url}, {@code scripts.docker.twork}.
 */
class UpdateScriptsIT {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateScriptsIT.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UpdateScriptsTestHarness HARNESS = new UpdateScriptsTestHarness();

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeTrue(UpdateScriptsTestHarness.isConfigured(),
            "Skipping: system property 'nifi.cert.dir' is not set.");
        //no flags: run all three updates together (the default behavior):
        HARNESS.setUp();
    }

    @AfterAll
    static void cleanup() throws Exception {
        HARNESS.tearDown();
    }

    @AfterEach
    void cleanupCreatedResources() throws Exception {
        HARNESS.cleanupCreatedResources();
    }

    /**
     * Validates the transformation result, pushes the flow to NiFi Registry,
     * imports the process group via registry reference,
     * validates that all components are valid and deletes the process group afterward.
     */
    @Test
    void testTransformAndImport() throws Exception {
        JsonNode flowContents = HARNESS.readFlow("flows/flow-with-jolt-and-cache.json").path("flowContents");

        if (FlowAssertions.isTransformed(flowContents)) {
            FlowAssertions.assertTransformed(flowContents);
        } else {
            FlowAssertions.assertUntransformed(flowContents);
        }

        HARNESS.importAndValidate(flowContents, List.of());
    }

    /**
     * Pushes the flow to NiFi Registry, imports the process group via registry reference,
     * validates that all components are valid and deletes the process group afterward.
     *
     * <p>On NiFi 2.5.0, {@code PutS3Object} reports its sensitive dynamic properties as unsupported,
     * so that validation error is tolerated. On newer versions {@code PutS3Object} is valid and the
     * tolerated entry is a no-op.
     */
    @Test
    void testTransformAndImport2() throws Exception {
        JsonNode flowContents = HARNESS.readFlow("flows/Upgrade_Test_PG1.json").path("flowContents");
        HARNESS.importAndValidate(flowContents,
                List.of(new NifiFlowApiClient.IgnoredDifference(
                        "PutS3Object", "From 'Standard' to 'STANDARD'")),
                List.of(new NifiFlowApiClient.IgnoredValidationError(
                        "PutS3Object", "Sensitive Dynamic Properties")));
    }

    /**
     * Verifies the external-controller-service id rewrite end to end: the script replaces the
     * foreign id (key + identifier + referencing {@code put-record-sink} property) with the
     * precreated target CS id while leaving the in-flow record reader id unchanged, then the
     * referencing process group imports and validates ({@code invalidCount == 0}).
     */
    @Test
    void testExternalControllerServiceReference() throws Exception {
        Assumptions.assumeTrue(HARNESS.nifiVersion() != null && HARNESS.nifiVersion().startsWith("2."),
            "External controller service id rewrite only runs on NiFi 2.x targets");

        JsonNode snapshot = HARNESS.readFlow("flows/flow-with-external-cs.json");

        // The script must have rewritten the foreign external CS id to the precreated CS id
        // everywhere, and left the in-flow record reader id untouched.
        FlowAssertions.assertExternalCsRewritten(snapshot, HARNESS.externalCsId(),
                UpdateScriptsTestHarness.IN_FLOW_READER_ID);

        // Enable the (root) external controller service so PutRecord can resolve + validate against it.
        HARNESS.enableExternalControllerService();

        // invalidCount == 0 proves the external reference resolved to the precreated CS by id.
        HARNESS.importAndValidate(snapshot.path("flowContents"),
                snapshot.path("externalControllerServices"), List.of());
    }

    static Stream<String> controllerServiceFiles() {
        return UpdateScriptsTestHarness.controllerServiceFiles().stream();
    }

    /**
     * Creates a single controller service in NiFi using the (script-updated) resource
     * file and validates that it resolves to {@code VALID} status.
     *
     * @param fileName controller service JSON file name under {@code controller-services/}
     */
    @ParameterizedTest
    @MethodSource("controllerServiceFiles")
    void testControllerService(final String fileName) throws Exception {
        Path csFile = HARNESS.flowsDir().resolve("controller-services/" + fileName);
        ObjectNode csJson = (ObjectNode) MAPPER.readTree(csFile.toFile());

        // Clean for creation: remove server-assigned fields, reset revision version
        csJson.remove("id");
        csJson.remove("uri");
        ((ObjectNode) csJson.path("revision")).put("version", 0);
        ObjectNode component = (ObjectNode) csJson.path("component");
        component.remove("id");
        component.remove("parentGroupId");

        // Resolve the actual bundle version from this NiFi instance
        String csType = component.path("type").asText();
        String resolvedVersion = HARNESS.csVersionMap().get(csType);
        if (resolvedVersion == null) {
            throw new IllegalStateException(
                "Controller service type not found in NiFi: " + csType);
        }
        ((ObjectNode) component.path("bundle")).put("version", resolvedVersion);

        JsonNode respJson = HARNESS.api().createControllerService(MAPPER.writeValueAsString(csJson));
        LOG.info("Create controller service {}: id={}", fileName, respJson.path("id").asText());
        String createdId = respJson.path("id").asText();
        String createdVersion = respJson.path("revision").path("version").asText("0");
        HARNESS.trackCreatedControllerService(createdId, createdVersion);
        String validationStatus = respJson.path("status").path("validationStatus").asText();

        if ("VALIDATING".equals(validationStatus)) {
            //wait for up to 30 seconds for status to update
            LOG.info("Controller service {}: validationStatus={}, "
                    + "waiting for status to change to either valid or invalid", fileName, validationStatus);
            Awaitility.await().atMost(30, java.util.concurrent.TimeUnit.SECONDS).until(() -> {
                        JsonNode csNode = HARNESS.api().getControllerServiceById(createdId);
                        String validStatus = csNode.path("status").path("validationStatus").asText();
                        return "VALID".equals(validStatus) || "INVALID".equals(validStatus);
                    });
            //update cs data:
            respJson = HARNESS.api().getControllerServiceById(createdId);
            validationStatus = respJson.path("status").path("validationStatus").asText();
        }
        if (!"VALID".equals(validationStatus)) {
            StringBuilder errors = new StringBuilder();
            for (JsonNode err : respJson.path("component").path("validationErrors")) {
                errors.append(err.asText()).append("; ");
            }
            assertEquals("VALID", validationStatus,
                "Controller service " + fileName + " is not VALID. Errors: " + errors);
        }
    }
}

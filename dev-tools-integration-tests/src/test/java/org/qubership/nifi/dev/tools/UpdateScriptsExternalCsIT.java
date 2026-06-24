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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.NifiFlowApiClient;

import java.util.List;

/**
 * Integration test for the {@code --external-cs} flag in isolation: only the external controller
 * service references must be resolved, while component versions and properties are left untouched.
 *
 * <p>Shared setup and the scripts container live in {@link UpdateScriptsTestHarness}.
 */
class UpdateScriptsExternalCsIT {

    private static final UpdateScriptsTestHarness HARNESS = new UpdateScriptsTestHarness();

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeTrue(UpdateScriptsTestHarness.isConfigured(),
            "Skipping: system property 'nifi.cert.dir' is not set.");
        HARNESS.setUp("--external-cs");
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
     * Asserts that {@code --external-cs} rewrote the external controller service id (leaving the
     * in-flow reader id unchanged) and that neither component versions nor properties were modified.
     */
    @Test
    void testExternalCsFlagOnly() throws Exception {
        Assumptions.assumeTrue(HARNESS.nifiVersion() != null && HARNESS.nifiVersion().startsWith("2."),
            "External controller service id rewrite only runs on NiFi 2.x targets");

        JsonNode snapshot = HARNESS.readFlow("flows/flow-with-external-cs.json");

        FlowAssertions.assertExternalCsRewritten(snapshot, HARNESS.externalCsId(),
                UpdateScriptsTestHarness.IN_FLOW_READER_ID);
        FlowAssertions.assertAllApacheBundleVersions(snapshot, UpdateScriptsTestHarness.FIXTURE_BUNDLE_VERSION);
        FlowAssertions.assertProcessorPropertiesNotRenamed(snapshot, "PutRecord",
                "put-record-reader", "put-record-sink");
    }

    /**
     * Imports the {@code --external-cs}-transformed flow into the live NiFi and validates it. The
     * external sink reference was rewritten to the precreated controller service id, so it resolves
     * by id and the imported process group reaches {@code invalidCount == 0}. Because {@code --versions}
     * did not run, every {@code org.apache.nifi} bundle automatically resolves to the installed bundle;
     * those bundle-version changes are tolerated as benign local modifications.
     */
    @Test
    void testExternalCsFlagImport() throws Exception {
        JsonNode snapshot = HARNESS.readFlow("flows/flow-with-external-cs.json");

        HARNESS.enableExternalControllerService();
        HARNESS.importAndValidate(snapshot.path("flowContents"),
                snapshot.path("externalControllerServices"),
                List.of(new NifiFlowApiClient.IgnoredDifference(
                        "PutRecord", "Property 'Record Destination Service' was added")));
    }
}

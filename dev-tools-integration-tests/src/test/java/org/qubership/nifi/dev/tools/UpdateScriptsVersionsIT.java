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
 * Integration test for the {@code --versions} flag in isolation: only the component bundle versions
 * must be bumped to the target NiFi version, while external controller service references and
 * properties are left untouched.
 *
 * <p>Shared setup and the scripts container live in {@link UpdateScriptsTestHarness}.
 */
class UpdateScriptsVersionsIT {

    private static final UpdateScriptsTestHarness HARNESS = new UpdateScriptsTestHarness();

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeTrue(UpdateScriptsTestHarness.isConfigured(),
            "Skipping: system property 'nifi.cert.dir' is not set.");
        HARNESS.setUp("--versions");
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
     * Asserts that {@code --versions} bumped every {@code org.apache.nifi} bundle to the target NiFi
     * version while leaving the external controller service reference and the properties unchanged.
     */
    @Test
    void testVersionsFlagOnly() throws Exception {
        JsonNode snapshot = HARNESS.readFlow("flows/flow-with-external-cs.json");

        FlowAssertions.assertAllApacheBundleVersions(snapshot, HARNESS.nifiVersion());
        FlowAssertions.assertExternalCsNotRewritten(snapshot, "PutRecord",
                UpdateScriptsTestHarness.ORIGINAL_EXTERNAL_CS_ID);
        FlowAssertions.assertProcessorPropertiesNotRenamed(snapshot, "PutRecord",
                "put-record-reader", "put-record-sink");
    }

    /**
     * Imports the {@code --versions}-transformed flow into the live NiFi and validates it. The bundle
     * versions were bumped to the target NiFi, so every component resolves to an installed bundle.
     * Because {@code --external-cs} did not run, the {@code PutRecord} sink still references the
     * original foreign id, which the import resolves by the name carried in
     * {@code externalControllerServices} against the precreated controller service (enabled here).
     *
     * <p>On every Apache NiFi 2.x version, a renamed processor property that references an external
     * controller service breaks that resolution by name (an unfixed Apache NiFi limitation). The
     * {@code PutRecord} sink property was renamed in NiFi 2.7.2, so from 2.7.2 onward the foreign id
     * is left unresolved and {@code PutRecord} stays invalid; that validation error is tolerated
     * here until the upstream fix lands. On 2.5.0 and 2.6.0 the property was not yet renamed, the
     * reference resolves, and the tolerated entry is a no-op.
     */
    @Test
    void testVersionsFlagImport() throws Exception {
        Assumptions.assumeTrue(HARNESS.nifiVersion() != null && HARNESS.nifiVersion().startsWith("2."),
            "External controller service resolution only runs on NiFi 2.x targets");

        JsonNode snapshot = HARNESS.readFlow("flows/flow-with-external-cs.json");

        HARNESS.enableExternalControllerService();
        HARNESS.importAndValidate(snapshot.path("flowContents"),
                snapshot.path("externalControllerServices"),
                List.of(),
                List.of(new NifiFlowApiClient.IgnoredValidationError(
                        "PutRecord", UpdateScriptsTestHarness.ORIGINAL_EXTERNAL_CS_ID)));
    }
}

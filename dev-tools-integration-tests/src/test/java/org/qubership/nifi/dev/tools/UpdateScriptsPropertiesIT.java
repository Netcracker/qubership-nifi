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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test for the {@code --properties} flag in isolation: only the renamed/removed
 * properties must be applied, while component versions and external controller service references
 * are left untouched.
 *
 * <p>The probe flow's {@code ConvertRecord} property renames land in the 2.5 mapping step, so they
 * apply on every supported target (the properties update itself requires NiFi 2.5 or later). Shared
 * setup and the scripts container live in {@link UpdateScriptsTestHarness}.
 */
class UpdateScriptsPropertiesIT {

    /** Minimum target minor version that applies the fixture's ConvertRecord property renames. */
    private static final int CONVERT_RECORD_RENAME_MINOR = 5;

    /** ConvertRecord property renames applied at the 2.5 mapping step: {@code {oldKey, newKey}}. */
    private static final String[][] CONVERT_RECORD_RENAMES = {
        {"record-reader", "Record Reader"},
        {"record-writer", "Record Writer"},
        {"include-zero-record-flowfiles", "Include Zero Record FlowFiles"},
    };

    /** Minimum target minor version that applies the 2.11 mapping step to the AvroSchemaRegistry flow. */
    private static final int AVRO_VALIDATION_STRATEGY_MINOR = 11;

    /** Flow with two NiFi 1.28.1 AvroSchemaRegistry services: one with Validate Field Names set, one without. */
    private static final String AVRO_SCHEMA_REGISTRY_FLOW = "flows/flow-with-avro-schema-registry.json";

    /** Name of Validate Field Names after the 2.7 mapping step renamed avro-reg-validated-field-names. */
    private static final String VALIDATE_FIELD_NAMES = "Validate Field Names";

    private static final UpdateScriptsTestHarness HARNESS = new UpdateScriptsTestHarness();

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeTrue(UpdateScriptsTestHarness.isConfigured(),
            "Skipping: system property 'nifi.cert.dir' is not set.");
        HARNESS.setUp("--properties");
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
     * Asserts that {@code --properties} renamed the {@code ConvertRecord} properties while leaving
     * the component bundle versions unchanged.
     */
    @Test
    void testPropertiesFlagOnly() throws Exception {
        Assumptions.assumeTrue(HARNESS.nifiVersion() != null && HARNESS.nifiVersion().startsWith("2.")
                && HARNESS.nifiVersionMinor() >= CONVERT_RECORD_RENAME_MINOR,
            "Property migration only runs on NiFi 2.5 or later targets");

        JsonNode snapshot = HARNESS.readFlow("flows/flow-with-renamed-properties.json");

        FlowAssertions.assertAllApacheBundleVersions(snapshot, UpdateScriptsTestHarness.FIXTURE_BUNDLE_VERSION);
        FlowAssertions.assertProcessorPropertiesRenamed(snapshot, "ConvertRecord", CONVERT_RECORD_RENAMES);
    }

    /**
     * Imports the {@code --properties}-transformed flow into the live NiFi and validates it. The
     * {@code ConvertRecord} processor and its in-flow reader/writer controller services resolve, so
     * the imported process group reaches {@code invalidCount == 0}. The reader/writer default
     * properties NiFi 2.x adds on resolution (such as the {@code JsonRecordSetWriter}
     * {@code Allow Scientific Notation} property) are tolerated as benign local modifications.
     */
    @Test
    void testPropertiesFlagImport() throws Exception {
        JsonNode snapshot = HARNESS.readFlow("flows/flow-with-renamed-properties.json");

        HARNESS.importAndValidate(snapshot.path("flowContents"),
                List.of());
    }

    /**
     * Validate Field Names set to false stays in the flow, so that NiFi 2.11 converts it to the
     * Validation Strategy value NONE on import. Without the value, Validation Strategy takes its
     * default VALIDATE, and the service starts validating schemas it accepted before.
     */
    @Test
    void aValidateFieldNamesSetToFalseIsKeptForNiFiToMigrate() throws Exception {
        assumeAvroValidationStrategyTarget();

        JsonNode registry = controllerService(HARNESS.readFlow(AVRO_SCHEMA_REGISTRY_FLOW),
                "AvroSchemaRegistryNoValidation");
        JsonNode props = registry.path("properties");

        assertAll(
            () -> assertEquals("false", props.path(VALIDATE_FIELD_NAMES).asText(null),
                    "AvroSchemaRegistryNoValidation properties " + props),
            () -> assertTrue(registry.path("propertyDescriptors").has(VALIDATE_FIELD_NAMES),
                    "AvroSchemaRegistryNoValidation propertyDescriptors "
                            + registry.path("propertyDescriptors")));
    }

    /**
     * The Validate Field Names descriptor is removed from a service that carries no value for the
     * property, and the other descriptors stay.
     */
    @Test
    void anUnsetValidateFieldNamesLosesItsDescriptor() throws Exception {
        assumeAvroValidationStrategyTarget();

        JsonNode descriptors = controllerService(HARNESS.readFlow(AVRO_SCHEMA_REGISTRY_FLOW),
                "AvroSchemaRegistryValidationUnset").path("propertyDescriptors");

        assertAll(
            () -> assertFalse(descriptors.has(VALIDATE_FIELD_NAMES),
                    "AvroSchemaRegistryValidationUnset propertyDescriptors " + descriptors),
            () -> assertTrue(descriptors.has("schema1"),
                    "AvroSchemaRegistryValidationUnset propertyDescriptors " + descriptors));
    }

    private static void assumeAvroValidationStrategyTarget() {
        Assumptions.assumeTrue(HARNESS.nifiVersion() != null && HARNESS.nifiVersion().startsWith("2.")
                && HARNESS.nifiVersionMinor() >= AVRO_VALIDATION_STRATEGY_MINOR,
            "The 2.11 mapping step only runs on NiFi 2.11 or later targets");
    }

    private static JsonNode controllerService(final JsonNode snapshot, final String name) {
        for (JsonNode service : snapshot.path("flowContents").path("controllerServices")) {
            if (name.equals(service.path("name").asText())) {
                return service;
            }
        }
        return fail("flow must contain a controller service named " + name);
    }
}

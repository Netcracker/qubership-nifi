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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An Oracle DBCPConnectionPool or HikariCPConnectionPool with no driver location leaves the update scripts
 * with the {@code OJDBC_DRIVER_LOCATION} expression under the driver location key of the target NiFi, and
 * enables in that NiFi. Every other controller service keeps its driver location.
 *
 * <p>The update-scripts container runs its whole pipeline, with
 * {@code dev/update-scripts-ojdbc-location/updateOjdbcDriverLocation.sh} as the first stage, over the
 * {@code test-flows} fixtures. On an image that keeps the Oracle driver out of the NiFi lib directory, a pool
 * enables only when the expression resolves to the driver.
 *
 * <p>Shared setup, the scripts container, and the import helpers live in {@link UpdateScriptsTestHarness}.
 * Requires the {@code nifi.cert.dir} system property (otherwise the test is skipped) and the
 * {@code NIFI_CLIENT_PASSWORD} environment variable; see {@code UpdateScriptsIT} for the optional properties.
 */
class UpdateScriptsOjdbcLocationIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UpdateScriptsTestHarness HARNESS = new UpdateScriptsTestHarness();

    /** Driver location the script writes, as NiFi Expression Language. */
    private static final String OJDBC_LOCATION =
        "${OJDBC_DRIVER_LOCATION:replaceEmpty(${NIFI_HOME:append('/nifi-config-template')})}";

    private static final String NEW_LOCATIONS_KEY = "Database Driver Locations";
    private static final String DBCP_OLD_LOCATIONS_KEY = "database-driver-locations";
    private static final String HIKARI_OLD_LOCATIONS_KEY = "hikaricp-driver-locations";
    private static final String HIKARI_TYPE = "org.apache.nifi.dbcp.HikariCPConnectionPool";
    private static final String DBCP_TYPE = "org.apache.nifi.dbcp.DBCPConnectionPool";
    private static final String FIXTURE_VERSION = "1.28.1";
    private static final int FIRST_MINOR_WITH_NEW_KEYS = 7;
    private static final int ENABLE_TIMEOUT_SECONDS = 60;

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeTrue(UpdateScriptsTestHarness.isConfigured(),
            "Skipping: system property 'nifi.cert.dir' is not set.");
        //no flags: run every stage, as a real upgrade does:
        HARNESS.setUp();
    }

    @AfterAll
    static void cleanup() throws Exception {
        HARNESS.tearDown();
    }

    @AfterEach
    void cleanupCreatedResources() throws Exception {
        //The harness disables the services of the imported group only, not those of its child groups.
        String importedGroupId = HARNESS.importedProcessGroupId();
        if (importedGroupId != null) {
            for (JsonNode child : childGroups(importedGroupId)) {
                String childId = child.path("id").asText();
                HARNESS.api().changeControllerServicesStateForPg(childId, "DISABLED");
                HARNESS.api().waitForControllerServicesState(childId, "DISABLED");
            }
        }
        HARNESS.cleanupCreatedResources();
    }

    /**
     * A controller service export gets the location under the key of its bundle version, and the property
     * update renames that key for the target NiFi, so the result holds the key of whichever of the two
     * versions is newer. No other driver location key is left beside it.
     *
     * @param fileName      file under {@code controller-services/}
     * @param exportVersion bundle version in the export
     */
    @ParameterizedTest
    @CsvSource({
        "OracleDBCPConnectionPool.json,        1.28.1",
        "OracleHikariCPConnectionPool.json,    1.28.1",
        "OracleDBCPConnectionPool_2_6.json,    2.6.0",
        "OracleDBCPConnectionPool_2_7.json,    2.7.0",
        "OracleDBCPConnectionPool_2_10.json,   2.10.0",
        "OracleHikariCPConnectionPool_2_10.json, 2.10.0",
    })
    void controllerServiceExportGetsLocation(final String fileName, final String exportVersion) throws Exception {
        JsonNode component = MAPPER.readTree(
            HARNESS.flowsDir().resolve("controller-services/" + fileName).toFile()).path("component");
        String key = expectedLocationKey(component.path("type").asText(), exportVersion);
        JsonNode properties = component.path("properties");

        assertAll(
            () -> assertEquals(OJDBC_LOCATION, properties.path(key).asText(null), fileName + " " + key),
            () -> assertEquals(List.of(key), locationKeysOf(properties), fileName + " driver location keys"));
    }

    /**
     * An updated controller service export creates a valid pool that enables. An export newer than the
     * target NiFi cannot be created there, so each export runs only on a NiFi of its version or later.
     *
     * @param fileName      file under {@code controller-services/}
     * @param exportVersion bundle version in the export
     */
    @ParameterizedTest
    @CsvSource({
        "OracleDBCPConnectionPool.json,        1.28.1",
        "OracleHikariCPConnectionPool.json,    1.28.1",
        "OracleDBCPConnectionPool_2_6.json,    2.6.0",
        "OracleDBCPConnectionPool_2_7.json,    2.7.0",
        "OracleDBCPConnectionPool_2_10.json,   2.10.0",
        "OracleHikariCPConnectionPool_2_10.json, 2.10.0",
    })
    void updatedControllerServiceExportEnables(final String fileName, final String exportVersion)
            throws Exception {
        Assumptions.assumeTrue(compareVersions(HARNESS.nifiVersion(), exportVersion) >= 0,
            () -> fileName + " is newer than the target NiFi " + HARNESS.nifiVersion());
        String id = HARNESS.createControllerServiceFromExport(fileName).path("id").asText();

        enable(id);

        assertPoolEnabledWithLocation(HARNESS.api().getControllerServiceById(id), fileName);
    }

    /**
     * An updated flow export imports through NiFi Registry, and its Oracle pools enable, in the root group
     * and in a nested group.
     */
    @Test
    void updatedFlowExportImportsAndItsPoolsEnable() throws Exception {
        JsonNode flowContents = HARNESS.readFlow("flows/flow-with-oracle-pools.json").path("flowContents");

        HARNESS.importAndValidate(flowContents, List.of());

        String rootGroupId = HARNESS.importedProcessGroupId();
        String nestedGroupId = childGroups(rootGroupId).path(0).path("id").asText();
        String rootPoolId = HARNESS.api().getControllerServicesForPg(rootGroupId).path(0).path("id").asText();
        String nestedPoolId = HARNESS.api().getControllerServicesForPg(nestedGroupId).path(0).path("id").asText();
        enable(rootPoolId);
        enable(nestedPoolId);

        assertAll(
            () -> assertPoolEnabledWithLocation(HARNESS.api().getControllerServiceById(rootPoolId), "root pool"),
            () -> assertPoolEnabledWithLocation(HARNESS.api().getControllerServiceById(nestedPoolId),
                "nested pool"));
    }

    /**
     * An Oracle pool gets the location when the location is empty or missing, including a pool that names
     * {@code oracle.jdbc.driver.OracleDriver}. A pool with a location and a PostgreSQL pool keep their
     * location, and a service that is not a pool gets no location property.
     */
    @Test
    void flowExportGetsLocationOnOraclePoolsWithoutOne() throws Exception {
        JsonNode flow = HARNESS.readFlow("flows/flow-with-oracle-pool-variants.json").path("flowContents");
        String dbcpKey = expectedLocationKey(DBCP_TYPE, FIXTURE_VERSION);

        assertAll(
            () -> assertEquals(OJDBC_LOCATION, propertyOf(flow, "Oracle legacy driver class",
                expectedLocationKey(HIKARI_TYPE, FIXTURE_VERSION)), "Oracle legacy driver class"),
            () -> assertEquals(OJDBC_LOCATION, propertyOf(flow, "Oracle empty location", dbcpKey),
                "Oracle empty location"),
            () -> assertEquals(OJDBC_LOCATION, propertyOf(flow, "Oracle missing location", dbcpKey),
                "Oracle missing location"),
            () -> assertEquals("/opt/drivers/ojdbc8.jar", propertyOf(flow, "Oracle with location", dbcpKey),
                "Oracle with location"),
            () -> assertNull(propertyOf(flow, "PostgreSQL", dbcpKey), "PostgreSQL"),
            () -> assertEquals(List.of(), locationKeysOf(serviceNamed(flow, "JsonTreeReader").path("properties")),
                "JsonTreeReader driver location keys"));
    }

    private static void assertPoolEnabledWithLocation(final JsonNode service, final String description) {
        JsonNode component = service.path("component");
        String key = expectedLocationKey(component.path("type").asText(), HARNESS.nifiVersion());
        assertAll(
            () -> assertEquals("ENABLED", component.path("state").asText(),
                description + " state; validation errors: " + component.path("validationErrors")),
            () -> assertEquals(OJDBC_LOCATION, component.path("properties").path(key).asText(null),
                description + " " + key),
            () -> assertFalse(component.path("descriptors").path(key).path("dynamic").asBoolean(true),
                description + " declares no property " + key));
    }

    /**
     * Enables a controller service and waits until it is enabled. A pool whose driver class does not load
     * stays in {@code ENABLING}, so the wait fails with the last state and the bulletins of the service.
     *
     * @param serviceId controller service id
     */
    private static void enable(final String serviceId) throws Exception {
        Awaitility.await().atMost(ENABLE_TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() ->
            !"VALIDATING".equals(HARNESS.api().getControllerServiceById(serviceId)
                .path("status").path("validationStatus").asText()));
        JsonNode service = HARNESS.api().getControllerServiceById(serviceId);
        if ("ENABLED".equals(service.path("component").path("state").asText())) {
            return;
        }
        HARNESS.api().setControllerServiceState(serviceId, service.path("revision").path("version").asText(),
            "ENABLED");
        try {
            Awaitility.await().atMost(ENABLE_TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() ->
                "ENABLED".equals(HARNESS.api().getControllerServiceById(serviceId)
                    .path("component").path("state").asText()));
        } catch (RuntimeException e) {
            JsonNode last = HARNESS.api().getControllerServiceById(serviceId);
            throw new AssertionError("Controller service " + serviceId + " did not enable within "
                + ENABLE_TIMEOUT_SECONDS + " s; last state " + last.path("component").path("state").asText()
                + ", validation errors " + last.path("component").path("validationErrors")
                + ", bulletins " + last.path("bulletins"), e);
        }
    }

    /**
     * Returns the driver location key a pool holds after the update scripts: the key NiFi 2.7 introduced
     * when the export or the target NiFi is 2.7 or later, and the pre-2.7 key of the pool type otherwise.
     *
     * @param type          pool type
     * @param exportVersion bundle version in the export
     * @return the property key
     */
    private static String expectedLocationKey(final String type, final String exportVersion) {
        if (usesNewKeys(exportVersion) || usesNewKeys(HARNESS.nifiVersion())) {
            return NEW_LOCATIONS_KEY;
        }
        return HIKARI_TYPE.equals(type) ? HIKARI_OLD_LOCATIONS_KEY : DBCP_OLD_LOCATIONS_KEY;
    }

    private static boolean usesNewKeys(final String version) {
        return compareVersions(version, "2." + FIRST_MINOR_WITH_NEW_KEYS + ".0") >= 0;
    }

    /**
     * Compares the major and minor numbers of two versions such as {@code 2.10.0} or {@code 2.7.0-SNAPSHOT}.
     *
     * @param left  first version
     * @param right second version
     * @return a negative number, zero, or a positive number as the major.minor version of {@code left} is
     *         older than, equal to, or newer than that of {@code right}
     */
    private static int compareVersions(final String left, final String right) {
        String[] leftParts = left.split("[.-]");
        String[] rightParts = right.split("[.-]");
        int major = Integer.compare(Integer.parseInt(leftParts[0]), Integer.parseInt(rightParts[0]));
        return major != 0 ? major : Integer.compare(Integer.parseInt(leftParts[1]), Integer.parseInt(rightParts[1]));
    }

    private static JsonNode childGroups(final String groupId) throws Exception {
        return HARNESS.api().getProcessGroupFlowById(groupId)
            .path("processGroupFlow").path("flow").path("processGroups");
    }

    private static List<String> locationKeysOf(final JsonNode properties) {
        return List.of(NEW_LOCATIONS_KEY, DBCP_OLD_LOCATIONS_KEY, HIKARI_OLD_LOCATIONS_KEY).stream()
            .filter(properties::has)
            .toList();
    }

    private static JsonNode serviceNamed(final JsonNode group, final String name) {
        for (JsonNode service : group.path("controllerServices")) {
            if (name.equals(service.path("name").asText())) {
                return service;
            }
        }
        throw new AssertionError("No controller service named '" + name + "' in group "
            + group.path("name").asText());
    }

    private static String propertyOf(final JsonNode group, final String serviceName, final String key) {
        return serviceNamed(group, serviceName).path("properties").path(key).asText(null);
    }
}

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
import org.qubership.nifi.NifiAccessPolicies;
import org.qubership.nifi.NifiFlowApiClient;
import org.qubership.nifi.NifiMtlsClient;
import org.qubership.nifi.NifiRegistrySetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.utility.DockerImageName;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration test that runs the update scripts against a test flow,
 * then imports the result into a live NiFi instance via REST API using
 * NiFi Registry (bucket → flow → flow version → process group import by reference).
 *
 * <p>Requires the following system property to be set (otherwise the test is skipped):
 * <ul>
 *   <li>{@code nifi.cert.dir} — directory containing the admin client PKCS12 keystore
 *       ({@code CN=admin_OU=NIFI.p12}) and the CA certificate ({@code nifi-cert.pem})</li>
 * </ul>
 *
 * <p>Optional system properties (with defaults):
 * <ul>
 *   <li>{@code nifi.url} — NiFi base URL (default: {@code https://localhost:8080})</li>
 *   <li>{@code nifi.registry.url} — NiFi Registry base URL, used from the test runner
 *       (default: {@code https://localhost:18080})</li>
 *   <li>{@code scripts.docker.network} — Docker network to join
 *       (default: {@code host} network)</li>
 * </ul>
 *
 * <p>Required environment variable:
 * <ul>
 *   <li>{@code NIFI_CLIENT_PASSWORD} — password for the admin client PKCS12 keystore</li>
 * </ul>
 */
class UpdateScriptsIT {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateScriptsIT.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_SCRIPTS_IMAGE = "qubership-nifi-update-scripts:test";
    private static final String ADMIN_CERT_FILENAME = "CN=admin_OU=NIFI.p12";
    private static final String NIFI_CA_CERT_FILENAME = "nifi-cert.pem";
    private static final String NIFI_CONTAINER_HOST = "nifi";
    private static final int NIFI_CONTAINER_PORT = 8080;
    private static final String SCRIPTS_CONTAINER_CERT_DIR = "/tmp/certs";
    private static final String SCRIPTS_CONTAINER_FLOWS_DIR = "/data/export";
    private static final int SCRIPTS_TIMEOUT_MINUTES = 2;

    /** Internal URL NiFi uses to reach the Registry container. */
    private static final String NIFI_REGISTRY_INT_URL = "https://nifi-registry:18080";

    private static final List<String> CONTROLLER_SERVICE_FILES = List.of(
        //properties are not converted properly for these services:
        //"ADLSCredentialsControllerService.json",
        //"GCPCredentialsControllerService.json",
        "AWSCredentialsProviderControllerService.json",
        "AzureStorageCredentialsControllerService_v12.json",
        "AvroReader.json",
        "AvroRecordSetWriter.json",
        "AvroSchemaRegistry.json",
        "CSVReader.json",
        "CSVRecordSetWriter.json",
        "DBCPConnectionPool.json",
        "ExcelReader.json",
        "HikariCPConnectionPool.json",
        "JsonPathReader.json",
        "JsonRecordSetWriter.json",
        "JsonTreeReader.json",
        "PostgresPreparedStatementWithArrayProvider.json",
        "RedisConnectionPoolService.json",
        "StandardHttpContextMap.json",
        "StandardProxyConfigurationService.json",
        "StandardRestrictedSSLContextService.json",
        "StandardSSLContextService.json",
        "XMLReader.json",
        "XMLRecordSetWriter.json"
    );

    private static String nifiUrl;
    private static String nifiRegistryUrl;
    private static String nifiCertPath;
    private static String nifiCertPassword;
    private static String scriptsDockerNetwork;
    private static Path tempFlowsDir;
    private static HttpClient httpClient;
    private static NifiFlowApiClient api;
    private static String registryClientId;
    private static Map<String, String> csVersionMap;
    private static String nifiVersion;

    @BeforeAll
    static void setup() throws Exception {
        String certDir = System.getProperty("nifi.cert.dir");
        Assumptions.assumeTrue(certDir != null && !certDir.isEmpty(),
            "Skipping: system property 'nifi.cert.dir' is not set.");

        nifiUrl = System.getProperty("nifi.url", "https://localhost:8080");
        nifiRegistryUrl = System.getProperty("nifi.registry.url", "https://localhost:18080");
        nifiCertPath = certDir + "/" + ADMIN_CERT_FILENAME;
        String nifiCaCertPath = certDir + "/" + NIFI_CA_CERT_FILENAME;
        nifiCertPassword = System.getenv("NIFI_CLIENT_PASSWORD");
        scriptsDockerNetwork = System.getProperty("scripts.docker.network", "");

        tempFlowsDir = Files.createTempDirectory("dev-tools-it-flows-");
        copyTestFlows(tempFlowsDir);
        LOG.info("Test flows copied to {}", tempFlowsDir);

        httpClient = NifiMtlsClient.build(nifiCertPath, nifiCertPassword, nifiCaCertPath);
        api = new NifiFlowApiClient(nifiUrl, httpClient);
        new NifiAccessPolicies(nifiUrl, httpClient).setup();

        registryClientId = NifiRegistrySetup.setupRegistryClient(nifiUrl, NIFI_REGISTRY_INT_URL, httpClient);
        NifiRegistrySetup.createOrUpdateUser(nifiRegistryUrl, httpClient, "localhost");

        csVersionMap = buildControllerServiceVersionMap(api.fetchControllerServiceTypes());
        nifiVersion = api.fetchNifiVersion();
        //setup properties for SSLContext services:
        setupSslContextServicesTestProperties();
        runScriptsContainer();
    }

    private static final String TEST_PWD = "changeit";
    private static final String CA_CERTS_KEYSTORE = "/opt/java/openjdk/lib/security/cacerts";

    private static void setupSslContextServicesTestProperties() throws Exception {
        setupSslContextServicesTestProperties("StandardRestrictedSSLContextService.json");
        setupSslContextServicesTestProperties("StandardSSLContextService.json");
    }

    private static void setupSslContextServicesTestProperties(String fileName) throws IOException {
        Path csFile = tempFlowsDir.resolve("controller-services/" + fileName);
        File controllerServiceFile = csFile.toFile();
        ObjectNode csJson = (ObjectNode) MAPPER.readTree(controllerServiceFile);
        ObjectNode propsNode = (ObjectNode) csJson.path("component").path("properties");
        propsNode.put("Keystore Password", TEST_PWD);
        propsNode.put("key-password", TEST_PWD);
        propsNode.put("Truststore Password", TEST_PWD);
        if ("2.5.0".equals(nifiVersion)) {
            propsNode.put("Keystore Filename", CA_CERTS_KEYSTORE);
            propsNode.put("Truststore Filename", CA_CERTS_KEYSTORE);
        }
        MAPPER.writeValue(controllerServiceFile, csJson);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (registryClientId != null) {
            NifiRegistrySetup.deleteRegistryClient(nifiUrl, registryClientId, httpClient);
        }
        if (tempFlowsDir != null) {
            try (Stream<Path> paths = Files.walk(tempFlowsDir)) {
                paths.sorted(Comparator.reverseOrder())
                     .forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * Validates the transformation result, pushes the flow to NiFi Registry,
     * imports the process group via registry reference,
     * validates that all components are valid and deletes the process group afterward.
     */
    @Test
    void testTransformAndImport() throws Exception {
        Path flowFile = tempFlowsDir.resolve("flows/flow-with-jolt-and-cache.json");
        JsonNode flowContents = MAPPER.readTree(flowFile.toFile()).path("flowContents");

        if (FlowAssertions.isTransformed(flowContents)) {
            FlowAssertions.assertTransformed(flowContents);
        } else {
            FlowAssertions.assertUntransformed(flowContents);
        }

        importAndCleanup(flowContents);
    }

    /**
     * Pushes the flow to NiFi Registry, imports the process group via registry reference,
     * validates that all components are valid and deletes the process group afterward.
     */
    @Test
    void testTransformAndImport2() throws Exception {
        Path flowFile = tempFlowsDir.resolve("flows/Upgrade_Test_PG1.json");
        JsonNode flowContents = MAPPER.readTree(flowFile.toFile()).path("flowContents");
        importAndCleanup(flowContents);
    }

    static Stream<String> controllerServiceFiles() {
        return CONTROLLER_SERVICE_FILES.stream();
    }

    private String csId = null;
    private String csVersion = null;
    private String pgId = null;

    /**
     * Creates a single controller service in NiFi using the (script-updated) resource
     * file and validates that it resolves to {@code VALID} status.
     *
     * @param fileName controller service JSON file name under {@code controller-services/}
     */
    @ParameterizedTest
    @MethodSource("controllerServiceFiles")
    void testControllerService(final String fileName) throws Exception {
        Path csFile = tempFlowsDir.resolve("controller-services/" + fileName);
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
        String resolvedVersion = csVersionMap.get(csType);
        if (resolvedVersion == null) {
            throw new IllegalStateException(
                "Controller service type not found in NiFi: " + csType);
        }
        ((ObjectNode) component.path("bundle")).put("version", resolvedVersion);

        JsonNode respJson = api.createControllerService(MAPPER.writeValueAsString(csJson));
        LOG.info("Create controller service {}: id={}", fileName, respJson.path("id").asText());
        String createdId = respJson.path("id").asText();
        csId = createdId;
        csVersion = respJson.path("revision").path("version").asText("0");
        String validationStatus = respJson.path("status").path("validationStatus").asText();

        if ("VALIDATING".equals(validationStatus)) {
            //wait for up to 30 seconds for status to update
            LOG.info("Controller service {}: validationStatus={}, "
                    + "waiting for status to change to either valid or invalid", fileName, validationStatus);
            Awaitility.await().atMost(30, java.util.concurrent.TimeUnit.SECONDS).until(() -> {
                        JsonNode csNode = api.getControllerServiceById(createdId);
                        String validStatus = csNode.path("status").path("validationStatus").asText();
                        return "VALID".equals(validStatus) || "INVALID".equals(validStatus);
                    });
            //update cs data:
            respJson = api.getControllerServiceById(createdId);
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

    @AfterEach
    void cleanupCreatedResources() throws Exception {
        if (csId != null) {
            api.deleteControllerService(csId, csVersion);
            csId = null;
            csVersion = null;
        }
        if (pgId != null) {
            JsonNode mainPgJson = api.getProcessGroupById(pgId);
            String pgVersion = mainPgJson.path("revision").path("version").asText("0");
            String bucketId = mainPgJson.path("component").
                    path("versionControlInformation").path("bucketId").asText();
            api.changeControllerServicesStateForPg(pgId, "DISABLED");
            api.waitForControllerServicesState(pgId, "DISABLED");
            api.deleteProcessGroup(pgId, pgVersion);
            NifiRegistrySetup.deleteBucket(nifiRegistryUrl, httpClient, bucketId);
            pgId = null;
        }
    }

    // -------------------------------------------------------------------------
    // Scripts container
    // -------------------------------------------------------------------------

    private static void runScriptsContainer() {
        Path certPath = Paths.get(nifiCertPath).toAbsolutePath();
        Path certDir = certPath.getParent();
        String certFileName = certPath.getFileName().toString();

        String nifiTargetUrl;
        String networkMode;
        if (scriptsDockerNetwork != null && !scriptsDockerNetwork.isEmpty()) {
            networkMode = scriptsDockerNetwork;
            nifiTargetUrl = "https://" + NIFI_CONTAINER_HOST + ":" + NIFI_CONTAINER_PORT;
        } else {
            networkMode = "host";
            nifiTargetUrl = nifiUrl;
        }

        String nifiCert = "--cert '" + SCRIPTS_CONTAINER_CERT_DIR + "/" + certFileName
            + ":" + nifiCertPassword + "'"
            + " --cert-type P12"
            + " --cacert " + SCRIPTS_CONTAINER_CERT_DIR + "/" + NIFI_CA_CERT_FILENAME;

        try (GenericContainer<?> container = new GenericContainer<>(
                DockerImageName.parse(DEFAULT_SCRIPTS_IMAGE))) {
            container.withNetworkMode(networkMode)
                .withFileSystemBind(tempFlowsDir.toAbsolutePath().toString(),
                    SCRIPTS_CONTAINER_FLOWS_DIR, BindMode.READ_WRITE)
                .withFileSystemBind(certDir.toAbsolutePath().toString(),
                    SCRIPTS_CONTAINER_CERT_DIR, BindMode.READ_ONLY)
                .withEnv("NIFI_TARGET_URL", nifiTargetUrl)
                .withEnv("NIFI_CERT", nifiCert)
                .withCommand(SCRIPTS_CONTAINER_FLOWS_DIR)
                .withStartupCheckStrategy(
                    new OneShotStartupCheckStrategy()
                        .withTimeout(Duration.ofMinutes(SCRIPTS_TIMEOUT_MINUTES)));
            container.start();
            LOG.info("Scripts container logs:\n{}", container.getLogs());
        }
    }

    // -------------------------------------------------------------------------
    // NiFi API import via registry reference
    // -------------------------------------------------------------------------

    private void importAndCleanup(final JsonNode flowContents) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucketId = NifiRegistrySetup.createBucket(nifiRegistryUrl, httpClient, "IT-Bucket-" + suffix);
        String flowId = NifiRegistrySetup.createFlow(nifiRegistryUrl, httpClient, bucketId, "IT-Flow");
        int version = NifiRegistrySetup.createFlowVersion(nifiRegistryUrl, httpClient, bucketId, flowId, flowContents);

        JsonNode responseJson = api.importProcessGroup(bucketId, flowId, version, registryClientId);
        String createdId = responseJson.path("id").asText();
        pgId = createdId;
        assertNotNull(createdId, "Created process group must have an id");
        assertFalse(createdId.isEmpty(), "Created process group id must not be empty");

        api.changeControllerServicesStateForPg(createdId, "ENABLED");
        api.waitForControllerServicesState(createdId, "ENABLED");
        api.waitForPgValidation(createdId, nifiVersion);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, String> buildControllerServiceVersionMap(final JsonNode csTypes) {
        Map<String, String> map = new HashMap<>();
        for (JsonNode entry : csTypes) {
            map.put(entry.path("type").asText(),
                    entry.path("bundle").path("version").asText());
        }
        return map;
    }

    private static void copyTestFlows(final Path dest) throws Exception {
        Path testFlowsPath = Paths.get(UpdateScriptsIT.class.getResource("/test-flows").toURI());
        try (Stream<Path> files = Files.walk(testFlowsPath)) {
            files.forEach(src -> {
                if (Files.isDirectory(src)) {
                    try {
                        LOG.debug("Copy test directory = {}", src);
                        Files.createDirectories(dest.resolve(src.getFileName()));
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to create directories for test flow: " + src, e);
                    }
                } else {
                    try {
                        Path target = dest.resolve(src.getParent().getParent().relativize(src));
                        LOG.debug("Copy test file = {} to {}", src, target);
                        Files.copy(src, target);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to copy test flow: " + src, e);
                    }
                }
            });
        }
    }
}

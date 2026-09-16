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
import org.qubership.nifi.NifiAccessPolicies;
import org.qubership.nifi.NifiFlowApiClient;
import org.qubership.nifi.NifiMtlsClient;
import org.qubership.nifi.NifiRegistrySetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shared harness for the update-script integration tests. It owns everything common across the
 * per-flag IT classes: reading the test configuration, building the mTLS NiFi clients, copying the
 * test flows into a private working directory, precreating the external controller service the
 * fixtures reference by name, running the update-scripts container with a chosen set of flags, and
 * importing/validating the resulting flows in NiFi.
 *
 * <p>Each IT instantiates one harness, calls {@link #setUp(String...)} from {@code @BeforeAll} with
 * the flags under test, and calls {@link #tearDown()} from {@code @AfterAll}. Per-test resources
 * created through the harness are released by {@link #cleanupCreatedResources()} from
 * {@code @AfterEach}.
 *
 * <p>Requires the {@code nifi.cert.dir} system property and the {@code NIFI_CLIENT_PASSWORD}
 * environment variable; see {@code UpdateScriptsIT} for the full list of supported properties.
 */
final class UpdateScriptsTestHarness {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateScriptsTestHarness.class);
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

    private static final String TEST_PWD = "changeit";
    private static final String CA_CERTS_KEYSTORE = "/opt/java/openjdk/lib/security/cacerts";

    /** Name of the external controller service the {@code flow-with-external-cs.json} fixture references. */
    static final String EXTERNAL_CS_NAME = "CommonQubershipPrometheusRecordSink123";
    /** Type of the external controller service the fixture references. */
    static final String EXTERNAL_CS_TYPE = "org.qubership.nifi.service.QubershipPrometheusRecordSink";
    /** NAR bundle group of the external controller service. */
    static final String EXTERNAL_CS_NAR_GROUP = "org.qubership.nifi";
    /** NAR bundle artifact of the external controller service. */
    static final String EXTERNAL_CS_NAR_ARTIFACT = "qubership-service-nar";
    /** In-flow record reader id in the fixture; must be left unchanged by the script. */
    static final String IN_FLOW_READER_ID = "22222222-0000-0000-0000-000000000001";
    /** Foreign external controller service id in the fixture, before any {@code --external-cs} rewrite. */
    static final String ORIGINAL_EXTERNAL_CS_ID = "18a13b92-2d58-445d-9983-c8745deefcae";
    /** Bundle version every component carries in the fixtures, before any {@code --versions} bump. */
    static final String FIXTURE_BUNDLE_VERSION = "1.28.1";

    private String nifiUrl;
    private String nifiRegistryUrl;
    private String nifiCertPath;
    private String nifiCaCertPath;
    private String nifiCertPassword;
    private String scriptsDockerNetwork;
    private Path tempFlowsDir;
    private HttpClient httpClient;
    private NifiFlowApiClient api;
    private String registryClientId;
    private Map<String, String> csVersionMap;
    private String nifiVersion;
    private String externalCsId;
    private String externalCsVersion;

    // Per-test resources, released by cleanupCreatedResources().
    private String csId;
    private String csVersion;
    private String pgId;

    /**
     * Returns {@code true} when {@code nifi.cert.dir} is set, i.e. the integration tests are
     * configured to run. Used by each IT's {@code @BeforeAll} assumption guard.
     *
     * @return whether the required {@code nifi.cert.dir} system property is present
     */
    static boolean isConfigured() {
        String certDir = System.getProperty("nifi.cert.dir");
        return certDir != null && !certDir.isEmpty();
    }

    /**
     * Prepares the NiFi clients and test flows, precreates the external controller service, and runs
     * the update-scripts container once with the given flags. With no flags the script runs all
     * three updates.
     *
     * @param scriptFlags update-script flags to run (e.g. {@code "--external-cs"}); empty runs all
     */
    void setUp(final String... scriptFlags) throws Exception {
        String certDir = System.getProperty("nifi.cert.dir");
        nifiUrl = System.getProperty("nifi.url", "https://localhost:8080");
        nifiRegistryUrl = System.getProperty("nifi.registry.url", "https://localhost:18080");
        nifiCertPath = certDir + "/" + ADMIN_CERT_FILENAME;
        nifiCaCertPath = certDir + "/" + NIFI_CA_CERT_FILENAME;
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
        //precreate the external controller service the script will match by name (before scripts run):
        externalCsId = createExternalControllerService();
        runScriptsContainer(scriptFlags);
    }

    /**
     * Releases the shared resources: the precreated external controller service, the NiFi registry
     * client, and the temporary flows directory. Safe to call when {@link #setUp(String...)} failed
     * partway through.
     */
    void tearDown() throws Exception {
        if (externalCsId != null) {
            try {
                JsonNode csNode = api.getControllerServiceById(externalCsId);
                String version = csNode.path("revision").path("version").asText(externalCsVersion);
                api.setControllerServiceState(externalCsId, version, "DISABLED");
                api.waitForControllerServiceState(externalCsId, "DISABLED");
                csNode = api.getControllerServiceById(externalCsId);
                version = csNode.path("revision").path("version").asText(version);
                api.deleteControllerService(externalCsId, version);
            } catch (Exception e) {
                LOG.warn("Failed to clean up external controller service {}", externalCsId, e);
            }
            externalCsId = null;
        }
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
     * Releases per-test resources created through the harness: a controller service registered via
     * {@link #trackCreatedControllerService(String, String)} and a process group imported via the
     * {@code importAndValidate} methods (along with its registry bucket).
     */
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
    // Accessors
    // -------------------------------------------------------------------------

    NifiFlowApiClient api() {
        return api;
    }

    String nifiVersion() {
        return nifiVersion;
    }

    /**
     * Returns the minor component of the target NiFi version (e.g. {@code 9} for {@code 2.9.0}),
     * or {@code -1} when it cannot be parsed. Used to gate version-specific property assertions.
     *
     * @return the NiFi minor version, or {@code -1} if unknown
     */
    int nifiVersionMinor() {
        if (nifiVersion == null) {
            return -1;
        }
        String[] parts = nifiVersion.split("\\.");
        if (parts.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    Path flowsDir() {
        return tempFlowsDir;
    }

    Map<String, String> csVersionMap() {
        return csVersionMap;
    }

    String externalCsId() {
        return externalCsId;
    }

    /**
     * Reads the snapshot of a transformed flow export from the harness working directory.
     *
     * @param relativePath path relative to the flows directory, e.g. {@code "flows/flow-with-external-cs.json"}
     * @return the parsed flow-export JSON node
     */
    JsonNode readFlow(final String relativePath) throws IOException {
        return MAPPER.readTree(tempFlowsDir.resolve(relativePath).toFile());
    }

    /**
     * Records a controller service created by a test so {@link #cleanupCreatedResources()} can
     * delete it afterward.
     *
     * @param id      controller service id
     * @param version controller service revision version
     */
    void trackCreatedControllerService(final String id, final String version) {
        this.csId = id;
        this.csVersion = version;
    }

    // -------------------------------------------------------------------------
    // External controller service
    // -------------------------------------------------------------------------

    /**
     * Creates the external controller service (in root) that {@code flow-with-external-cs.json}
     * references by name. Capturing its real (target-environment) id lets the test assert the
     * script rewrote the foreign id to this one.
     *
     * @return the created controller service id
     */
    private String createExternalControllerService() throws Exception {
        String version = csVersionMap.get(EXTERNAL_CS_TYPE);
        if (version == null) {
            throw new IllegalStateException("Controller service type not found in NiFi: " + EXTERNAL_CS_TYPE);
        }

        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("group", EXTERNAL_CS_NAR_GROUP);
        bundle.put("artifact", EXTERNAL_CS_NAR_ARTIFACT);
        bundle.put("version", version);

        ObjectNode component = MAPPER.createObjectNode();
        component.put("name", EXTERNAL_CS_NAME);
        component.put("type", EXTERNAL_CS_TYPE);
        component.set("bundle", bundle);

        ObjectNode revision = MAPPER.createObjectNode();
        revision.put("version", 0);

        ObjectNode body = MAPPER.createObjectNode();
        body.set("revision", revision);
        body.set("component", component);

        JsonNode resp = api.createControllerService(MAPPER.writeValueAsString(body));
        externalCsVersion = resp.path("revision").path("version").asText("0");
        String id = resp.path("id").asText();
        LOG.info("Precreated external controller service {} id={}", EXTERNAL_CS_NAME, id);
        return id;
    }

    /**
     * Enables the precreated (root) external controller service so referencing components can
     * resolve and validate against it.
     */
    void enableExternalControllerService() throws Exception {
        JsonNode csNode = api.getControllerServiceById(externalCsId);
        String csVer = csNode.path("revision").path("version").asText(externalCsVersion);
        JsonNode enabled = api.setControllerServiceState(externalCsId, csVer, "ENABLED");
        externalCsVersion = enabled.path("revision").path("version").asText(csVer);
        api.waitForControllerServiceState(externalCsId, "ENABLED");
    }

    // -------------------------------------------------------------------------
    // SSL context service test properties
    // -------------------------------------------------------------------------

    private void setupSslContextServicesTestProperties() throws Exception {
        setupSslContextServicesTestProperties("StandardRestrictedSSLContextService.json");
        setupSslContextServicesTestProperties("StandardSSLContextService.json");
    }

    private void setupSslContextServicesTestProperties(final String fileName) throws IOException {
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

    // -------------------------------------------------------------------------
    // Scripts container
    // -------------------------------------------------------------------------

    private void runScriptsContainer(final String... flags) {
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
                .withStartupCheckStrategy(
                    new OneShotStartupCheckStrategy()
                        .withTimeout(Duration.ofMinutes(SCRIPTS_TIMEOUT_MINUTES)));

            //Pass the flows dir as the first argument and forward any flags to the entrypoint, which
            //hands them to the flow-export update script. With no flags the entrypoint runs the whole
            //autotest pipeline; with flags it runs only the flow-export stage.
            String[] command = new String[flags.length + 1];
            command[0] = SCRIPTS_CONTAINER_FLOWS_DIR;
            System.arraycopy(flags, 0, command, 1, flags.length);
            container.withCommand(command);

            container.start();
            LOG.info("Scripts container logs:\n{}", container.getLogs());
        }
    }

    // -------------------------------------------------------------------------
    // NiFi API import via registry reference
    // -------------------------------------------------------------------------

    /**
     * Pushes a flow to the registry, imports it under root, enables its controller services, and
     * asserts it validates and is up to date. Tracks the created process group for cleanup.
     *
     * @param flowContents the {@code flowContents} node to import
     * @param ignored      benign local modifications to tolerate in the up-to-date check
     */
    void importAndValidate(final JsonNode flowContents,
            final Collection<NifiFlowApiClient.IgnoredDifference> ignored) throws Exception {
        importAndValidate(flowContents, null, ignored, List.of());
    }

    /**
     * Same as {@link #importAndValidate(JsonNode, Collection)} but also tolerates an allowlist of
     * invalid-component validation errors.
     *
     * @param flowContents     the {@code flowContents} node to import
     * @param ignored          benign local modifications to tolerate in the up-to-date check
     * @param ignoredValidation validation errors to tolerate while waiting for the import to validate
     */
    void importAndValidate(final JsonNode flowContents,
            final Collection<NifiFlowApiClient.IgnoredDifference> ignored,
            final Collection<NifiFlowApiClient.IgnoredValidationError> ignoredValidation) throws Exception {
        importAndValidate(flowContents, null, ignored, ignoredValidation);
    }

    /**
     * Same as {@link #importAndValidate(JsonNode, Collection)} but also carries the export's
     * {@code externalControllerServices} map into the registry flow version, so external references
     * resolve on import.
     *
     * @param flowContents               the {@code flowContents} node to import
     * @param externalControllerServices the {@code externalControllerServices} node, or {@code null}
     * @param ignored                    benign local modifications to tolerate in the up-to-date check
     */
    void importAndValidate(final JsonNode flowContents,
            final JsonNode externalControllerServices,
            final Collection<NifiFlowApiClient.IgnoredDifference> ignored) throws Exception {
        importAndValidate(flowContents, externalControllerServices, ignored, List.of());
    }

    /**
     * Same as {@link #importAndValidate(JsonNode, JsonNode, Collection)} but also tolerates an
     * allowlist of invalid-component validation errors while waiting for the import to validate.
     *
     * @param flowContents               the {@code flowContents} node to import
     * @param externalControllerServices the {@code externalControllerServices} node, or {@code null}
     * @param ignored                    benign local modifications to tolerate in the up-to-date check
     * @param ignoredValidation          validation errors to tolerate while waiting for validation
     */
    void importAndValidate(final JsonNode flowContents,
            final JsonNode externalControllerServices,
            final Collection<NifiFlowApiClient.IgnoredDifference> ignored,
            final Collection<NifiFlowApiClient.IgnoredValidationError> ignoredValidation) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucketId = NifiRegistrySetup.createBucket(nifiRegistryUrl, httpClient, "IT-Bucket-" + suffix);
        String flowId = NifiRegistrySetup.createFlow(nifiRegistryUrl, httpClient, bucketId, "IT-Flow");
        int version;
        if (externalControllerServices == null) {
            version = NifiRegistrySetup.createFlowVersion(
                    nifiRegistryUrl, httpClient, bucketId, flowId, flowContents);
        } else {
            version = NifiRegistrySetup.createFlowVersion(
                    nifiRegistryUrl, httpClient, bucketId, flowId, flowContents, externalControllerServices);
        }

        JsonNode responseJson = api.importProcessGroup(bucketId, flowId, version, registryClientId);
        String createdId = responseJson.path("id").asText();
        pgId = createdId;
        assertNotNull(createdId, "Created process group must have an id");
        assertFalse(createdId.isEmpty(), "Created process group id must not be empty");

        api.changeControllerServicesStateForPg(createdId, "ENABLED");
        api.waitForControllerServicesState(createdId, "ENABLED");
        api.waitForPgValidation(createdId, ignoredValidation);
        api.assertProcessGroupUpToDate(createdId, ignored);
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
        Path testFlowsPath = Paths.get(UpdateScriptsTestHarness.class.getResource("/test-flows").toURI());
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

    /**
     * Returns the controller service file names used by the parameterized controller-service test.
     *
     * @return controller service JSON file names under {@code controller-services/}
     */
    static List<String> controllerServiceFiles() {
        return List.of(
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
    }
}

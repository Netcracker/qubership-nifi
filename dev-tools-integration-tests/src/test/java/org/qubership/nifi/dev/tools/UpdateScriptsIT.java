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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.NifiAccessPolicies;
import org.qubership.nifi.NifiMtlsClient;
import org.qubership.nifi.NifiRegistrySetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

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
    private static final int HTTP_CREATED = 201;
    private static final int HTTP_OK = 200;
    private static final int SCRIPTS_TIMEOUT_MINUTES = 2;

    /** Internal URL NiFi uses to reach the Registry container. */
    private static final String NIFI_REGISTRY_INT_URL = "https://nifi-registry:18080";

    private static final List<String> CONTROLLER_SERVICE_FILES = List.of(
        "HikariCPConnectionPool.json",
        "JsonRecordSetWriter.json",
        "StandardHttpContextMap.json"
    );

    private static String nifiUrl;
    private static String nifiRegistryUrl;
    private static String nifiCertPath;
    private static String nifiCertPassword;
    private static String scriptsDockerNetwork;
    private static Path tempFlowsDir;
    private static HttpClient httpClient;
    private static String registryClientId;

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

        try {
            //permissions 777 to allow docker container's user to RW access
            tempFlowsDir = Files.createTempDirectory("dev-tools-it-flows-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"))
            );
        } catch (UnsupportedOperationException ex) {
            LOG.debug("POSIX file attributes not supported. Will create directory w/o permissions", ex);
            tempFlowsDir = Files.createTempDirectory("dev-tools-it-flows-");
        }
        copyTestFlows(tempFlowsDir);
        LOG.info("Test flows copied to {}", tempFlowsDir);

        httpClient = NifiMtlsClient.build(nifiCertPath, nifiCertPassword, nifiCaCertPath);
        new NifiAccessPolicies(nifiUrl, httpClient).setup();

        registryClientId = NifiRegistrySetup.setupRegistryClient(nifiUrl, NIFI_REGISTRY_INT_URL, httpClient);
        NifiRegistrySetup.createOrUpdateUser(nifiRegistryUrl, httpClient, "localhost");

        runScriptsContainer();
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
     * imports the process group via registry reference, and deletes it afterwards.
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
     * Creates each standard controller service in NiFi using the (script-updated) resource
     * files and validates that each resolves to {@code VALID} status.
     */
    @Test
    void testControllerServices() throws Exception {
        JsonNode csTypes = fetchControllerServiceTypes();

        for (String fileName : CONTROLLER_SERVICE_FILES) {
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
            String bundleGroup = component.path("bundle").path("group").asText();
            String bundleArtifact = component.path("bundle").path("artifact").asText();
            String resolvedVersion = resolveControllerServiceBundleVersion(
                csTypes, csType, bundleGroup, bundleArtifact);
            ((ObjectNode) component.path("bundle")).put("version", resolvedVersion);

            String body = MAPPER.writeValueAsString(csJson);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(nifiUrl + "/nifi-api/process-groups/root/controller-services"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            LOG.info("Create controller service {}: status={}", fileName, resp.statusCode());
            assertEquals(HTTP_CREATED, resp.statusCode(),
                "Expected HTTP 201 when creating controller service " + fileName
                    + ". Response: " + resp.body());

            JsonNode respJson = MAPPER.readTree(resp.body());
            String createdId = respJson.path("id").asText();
            String validationStatus = respJson.path("status").path("validationStatus").asText();

            if (!"VALID".equals(validationStatus)) {
                StringBuilder errors = new StringBuilder();
                for (JsonNode err : respJson.path("component").path("validationErrors")) {
                    errors.append(err.asText()).append("; ");
                }
                assertEquals("VALID", validationStatus,
                    "Controller service " + fileName + " is not VALID. Errors: " + errors);
            }

            deleteControllerService(createdId,
                respJson.path("revision").path("version").asText("0"));
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

    private static void importAndCleanup(final JsonNode flowContents) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucketId = NifiRegistrySetup.createBucket(nifiRegistryUrl, httpClient, "IT-Bucket-" + suffix);
        String flowId = NifiRegistrySetup.createFlow(nifiRegistryUrl, httpClient, bucketId, "IT-Flow");
        int version = NifiRegistrySetup.createFlowVersion(nifiRegistryUrl, httpClient, bucketId, flowId, flowContents);

        ObjectNode importBody = buildVersionedImportBody(bucketId, flowId, version);
        String importBodyStr = MAPPER.writeValueAsString(importBody);

        HttpRequest importRequest = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + "/nifi-api/process-groups/root/process-groups"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(importBodyStr))
            .build();

        HttpResponse<String> importResponse = httpClient.send(importRequest,
            HttpResponse.BodyHandlers.ofString());
        LOG.info("Import response: status={}", importResponse.statusCode());
        if (LOG.isDebugEnabled()) {
            LOG.debug("Import response body: body={}", importResponse.body());
        }
        assertEquals(HTTP_CREATED, importResponse.statusCode(),
            "Expected HTTP 201 when creating process group. Response: " + importResponse.body());

        JsonNode responseJson = MAPPER.readTree(importResponse.body());
        String createdId = responseJson.path("id").asText();
        assertNotNull(createdId, "Created process group must have an id");
        assertFalse(createdId.isEmpty(), "Created process group id must not be empty");

        int invalidCount = responseJson.path("invalidCount").asInt();
        if (invalidCount > 0) {
            StringBuilder validationErrorsMessage = new StringBuilder();
            ArrayNode processorsList = (ArrayNode) responseJson.path("component")
                .path("contents").path("processors");
            for (JsonNode processorNode : processorsList) {
                String validationStatus = processorNode.path("validationStatus").asText();
                if ("INVALID".equals(validationStatus)) {
                    ArrayNode validationErrorsNode = (ArrayNode) processorNode.path("validationErrors");
                    validationErrorsMessage.append("Processor name = ")
                        .append(processorNode.path("name").asText())
                        .append(". Validation errors: [");
                    for (JsonNode validationError : validationErrorsNode) {
                        validationErrorsMessage.append(validationError.asText()).append(",");
                    }
                    validationErrorsMessage.append("].");
                }
            }
            assertEquals(0, invalidCount, "Created PG must not have invalid components. "
                + "Validation errors: " + validationErrorsMessage);
        }

        String pgVersion = responseJson.path("revision").path("version").asText("0");
        deleteProcessGroup(createdId, pgVersion);
        NifiRegistrySetup.deleteBucket(nifiRegistryUrl, httpClient, bucketId);
    }

    private static ObjectNode buildVersionedImportBody(final String bucketId,
                                                       final String flowId,
                                                       final int version) {
        ObjectNode importBody = MAPPER.createObjectNode();

        ObjectNode revision = MAPPER.createObjectNode();
        revision.put("version", 0);
        importBody.set("revision", revision);
        importBody.put("disconnectedNodeAcknowledged", false);

        ObjectNode position = MAPPER.createObjectNode();
        position.put("x", 0.0);
        position.put("y", 0.0);

        ObjectNode versionControlInfo = MAPPER.createObjectNode();
        versionControlInfo.put("registryId", registryClientId);
        versionControlInfo.put("bucketId", bucketId);
        versionControlInfo.put("flowId", flowId);
        versionControlInfo.put("version", version);

        ObjectNode component = MAPPER.createObjectNode();
        component.put("name", "IT-Test-Group");
        component.set("position", position);
        component.set("versionControlInformation", versionControlInfo);
        importBody.set("component", component);

        return importBody;
    }

    private static void deleteProcessGroup(final String pgId, final String pgVersion) throws Exception {
        HttpRequest deleteRequest = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + "/nifi-api/process-groups/" + pgId + "?version=" + pgVersion))
            .header("Accept", "application/json")
            .DELETE()
            .build();
        HttpResponse<String> deleteResponse = httpClient.send(deleteRequest,
            HttpResponse.BodyHandlers.ofString());
        if (deleteResponse.statusCode() != HTTP_OK) {
            LOG.warn("DELETE process group {} returned status {}; body: {}",
                pgId, deleteResponse.statusCode(), deleteResponse.body());
        }
    }

    private static JsonNode fetchControllerServiceTypes() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + "/nifi-api/flow/controller-service-types"))
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            throw new IllegalStateException(
                "GET /nifi-api/flow/controller-service-types returned HTTP " + resp.statusCode());
        }
        return MAPPER.readTree(resp.body()).path("controllerServiceTypes");
    }

    private static String resolveControllerServiceBundleVersion(final JsonNode csTypes,
                                                                 final String type,
                                                                 final String group,
                                                                 final String artifact) {
        for (JsonNode entry : csTypes) {
            JsonNode bundle = entry.path("bundle");
            if (type.equals(entry.path("type").asText())
                    && group.equals(bundle.path("group").asText())
                    && artifact.equals(bundle.path("artifact").asText())) {
                return bundle.path("version").asText();
            }
        }
        throw new IllegalStateException(
            "Controller service type not found in NiFi: " + type + " (" + group + ":" + artifact + ")");
    }

    private static void deleteControllerService(final String csId, final String version) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + "/nifi-api/controller-services/" + csId + "?version=" + version))
            .header("Accept", "application/json")
            .DELETE()
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            LOG.warn("DELETE controller service {} returned status {}; body: {}",
                csId, resp.statusCode(), resp.body());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
                        LOG.debug("Copy test file = {}", src);
                        Files.copy(src, dest.resolve(src.getParent().getParent().relativize(src)));
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to copy test flow: " + src, e);
                    }
                }
            });
        }
    }
}

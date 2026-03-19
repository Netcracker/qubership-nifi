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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.NifiAccessPolicies;
import org.qubership.nifi.NifiMtlsClient;
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
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration test that runs the update scripts against a test flow,
 * then imports the result into a live NiFi instance via REST API.
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

    private static String nifiUrl;
    private static String nifiCertPath;
    private static String nifiCertPassword;
    private static String scriptsDockerNetwork;
    private static Path tempFlowsDir;
    private static HttpClient httpClient;

    @BeforeAll
    static void setup() throws Exception {
        String certDir = System.getProperty("nifi.cert.dir");
        Assumptions.assumeTrue(certDir != null && !certDir.isEmpty(),
            "Skipping: system property 'nifi.cert.dir' is not set.");

        nifiUrl = System.getProperty("nifi.url", "https://localhost:8080");
        nifiCertPath = certDir + "/" + ADMIN_CERT_FILENAME;
        String nifiCaCertPath = certDir + "/" + NIFI_CA_CERT_FILENAME;
        nifiCertPassword = System.getenv("NIFI_CLIENT_PASSWORD");
        scriptsDockerNetwork = System.getProperty("scripts.docker.network", "");

        tempFlowsDir = Files.createTempDirectory("dev-tools-it-flows-");
        copyTestFlows(tempFlowsDir);
        LOG.info("Test flows copied to {}", tempFlowsDir);

        httpClient = NifiMtlsClient.build(nifiCertPath, nifiCertPassword, nifiCaCertPath);
        new NifiAccessPolicies(nifiUrl, httpClient).setup();
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (tempFlowsDir != null) {
            try (Stream<Path> paths = Files.walk(tempFlowsDir)) {
                paths.sorted(Comparator.reverseOrder())
                     .forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * Runs the update scripts container against the test flows, asserts the
     * transformation result, imports the resulting process group into NiFi, and
     * deletes the created group afterwards.
     */
    @Test
    void testTransformAndImport() throws Exception {
        runScriptsContainer();

        Path flowFile = tempFlowsDir.resolve("flow-with-jolt-and-cache.json");
        JsonNode flowContents = MAPPER.readTree(flowFile.toFile()).path("flowContents");

        if (FlowAssertions.isTransformed(flowContents)) {
            FlowAssertions.assertTransformed(flowContents);
        } else {
            FlowAssertions.assertUntransformed(flowContents);
        }

        importAndCleanup(flowContents);
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
    // NiFi API import
    // -------------------------------------------------------------------------

    private static void importAndCleanup(final JsonNode flowContents) throws Exception {
        String importBodyStr = MAPPER.writeValueAsString(buildImportBody(flowContents));

        HttpRequest importRequest = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + "/nifi-api/process-groups/root/process-groups"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(importBodyStr))
            .build();

        HttpResponse<String> importResponse = httpClient.send(importRequest,
            HttpResponse.BodyHandlers.ofString());
        LOG.info("Import response: status={}, body={}", importResponse.statusCode(),
            importResponse.body());
        assertEquals(HTTP_CREATED, importResponse.statusCode(),
            "Expected HTTP 201 when creating process group; body: " + importResponse.body());

        String createdId = MAPPER.readTree(importResponse.body()).path("id").asText();
        assertNotNull(createdId, "Created process group must have an id");
        assertFalse(createdId.isEmpty(), "Created process group id must not be empty");

        deleteProcessGroup(createdId);
    }

    private static void deleteProcessGroup(final String pgId) throws Exception {
        String version = fetchProcessGroupVersion(pgId);
        HttpRequest deleteRequest = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + "/nifi-api/process-groups/" + pgId + "?version=" + version))
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

    private static String fetchProcessGroupVersion(final String pgId) throws Exception {
        HttpRequest getRequest = HttpRequest.newBuilder()
            .uri(URI.create(nifiUrl + "/nifi-api/process-groups/" + pgId))
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> getResponse = httpClient.send(getRequest,
            HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(getResponse.body()).path("revision").path("version").asText("0");
    }

    private static ObjectNode buildImportBody(final JsonNode flowContents) {
        ObjectNode importBody = MAPPER.createObjectNode();

        ObjectNode revision = MAPPER.createObjectNode();
        revision.put("version", 0);
        importBody.set("revision", revision);
        importBody.put("disconnectedNodeAcknowledged", false);

        ObjectNode position = MAPPER.createObjectNode();
        position.put("x", 0.0);
        position.put("y", 0.0);
        ObjectNode component = MAPPER.createObjectNode();
        component.put("name", "IT-Test-Group");
        component.set("position", position);
        importBody.set("component", component);

        ObjectNode vfsNode = MAPPER.createObjectNode();
        vfsNode.put("flowEncodingVersion", "1.0");
        vfsNode.set("flowContents", flowContents);
        importBody.set("versionedFlowSnapshot", vfsNode);

        return importBody;
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
                        Files.copy(src, dest.resolve(src.getParent().relativize(src)));
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to copy test flow: " + src, e);
                    }
                }
            });
        }
    }
}

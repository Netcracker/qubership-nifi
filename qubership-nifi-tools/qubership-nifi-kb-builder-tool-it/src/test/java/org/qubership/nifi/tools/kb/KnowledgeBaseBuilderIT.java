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

package org.qubership.nifi.tools.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.tools.export.NiFiContainerManager;
import org.qubership.nifi.tools.kb.cli.Environment;
import org.qubership.nifi.tools.nifi.common.tls.Pkcs12TrustMaterial;
import org.qubership.nifi.tools.nifi.common.tls.TlsContextFactory;

import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that start a real NiFi 2.x container, run the Knowledge Base builder against it
 * over HTTPS with token authentication, and verify the generated output. The container is shared
 * across tests because startup is expensive. These tests require Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeBaseBuilderIT {

    private static final String NIFI_IMAGE = "apache/nifi:2.7.2";
    private static final String USERNAME = "admin";
    private static final int HOST_PORT = 19443;
    private static final int STARTUP_TIMEOUT_SECONDS = 240;
    private static final int RUN_TIMEOUT_MINUTES = 5;
    private static final int FORCED_TERMINATION_TIMEOUT_SECONDS = 30;
    private static final int OUTPUT_DRAIN_TIMEOUT_SECONDS = 10;
    private static final int PEM_LINE_LENGTH = 64;
    private static final int HTTP_MIN_SUCCESS = 200;
    private static final int HTTP_MAX_SUCCESS = 300;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> COMPONENT_JSON_FIELDS =
            Set.of("documentedType", "definition", "additionalDocumentation");
    private static final List<String> KIND_DIRS =
            List.of("processors", "controller-services", "reporting-tasks");

    private NiFiContainerManager container;
    private String baseUrl;
    private String token;
    private Path caFile;

    /** Per-test temporary directory provided by JUnit. */
    @TempDir
    private Path tempDir;

    @BeforeAll
    void startNiFi(@TempDir final Path sharedDir) throws Exception {
        final String password = UUID.randomUUID().toString();
        container = new NiFiContainerManager(NIFI_IMAGE, USERNAME, password,
                STARTUP_TIMEOUT_SECONDS, HOST_PORT);
        container.start();
        baseUrl = container.getBaseUrl();
        final NiFiContainerManager.TruststoreData truststore = container.readTruststore();
        final SSLContext sslContext = buildSslContext(truststore);
        caFile = writePemCaFile(sharedDir, truststore);
        token = requestToken(sslContext, baseUrl, password);
    }

    @AfterAll
    void stopNiFi() {
        if (container != null) {
            container.close();
        }
    }

    @Test
    void buildsCatalogOnlyKnowledgeBaseWithTokenAuth() throws Exception {
        final Path outputDir = tempDir.resolve("kb-catalog");
        final int code = run(outputDir, true);

        assertThat(code).isZero();
        assertCatalogStructure(outputDir);
        assertThat(outputDir.resolve("guides")).doesNotExist();

        final JsonNode manifest = MAPPER.readTree(outputDir.resolve("manifest.json").toFile());
        assertThat(manifest.path("fingerprint").asText()).startsWith("sha256:");
        assertGuideStatuses(manifest, "skip", "skipped");
    }

    @Test
    void buildsFullKnowledgeBaseWithGuides() throws Exception {
        final Path outputDir = tempDir.resolve("kb-full");
        final int code = run(outputDir, false);

        assertThat(code).isZero();
        assertCatalogStructure(outputDir);

        final Path guidesDir = outputDir.resolve("guides");
        assertThat(guidesDir.resolve("index.json")).exists();
        assertThat(guidesDir.resolve("expression-language-guide.md")).exists();
        assertThat(guidesDir.resolve("record-path-guide.md")).exists();
        assertThat(guidesDir.resolve("developer-guide.md")).exists();

        final JsonNode manifest = MAPPER.readTree(outputDir.resolve("manifest.json").toFile());
        assertGuideStatuses(manifest, "required", "collected");
    }

    @Test
    void replacesPreexistingKnowledgeBase() throws Exception {
        final Path outputDir = tempDir.resolve("kb-replace");

        assertThat(run(outputDir, true)).isZero();
        assertThat(outputDir.resolve("manifest.json")).exists();

        // A second run must replace the existing directory in place without an overwrite flag.
        assertThat(run(outputDir, true)).isZero();
        assertCatalogStructure(outputDir);
        assertThat(fingerprintOf(outputDir)).startsWith("sha256:");
    }

    private void assertGuideStatuses(final JsonNode manifest, final String expectedMode,
                                     final String expectedStatus) {
        final JsonNode guides = manifest.path("guides");
        assertThat(guides.path("mode").asText()).isEqualTo(expectedMode);
        for (final String key : List.of("expressionLanguageGuide", "recordPathGuide", "developerGuide")) {
            assertThat(guides.path(key).path("status").asText())
                    .as("guide status for %s", key)
                    .isEqualTo(expectedStatus);
        }
    }

    // Runs the packaged tool in its own process, exactly as a user would, and returns its exit code.
    // Launching the built jar rather than calling into the classes keeps the assertions honest about
    // the assembled application: the manifest Class-Path, the picocli command, and the real
    // environment variable lookup all take part.
    private int run(final Path outputDir, final boolean skipGuides) throws Exception {
        final List<String> command = new ArrayList<>(List.of(
                javaExecutable(),
                "-jar", builderJar().toString(),
                "--nifi-url", baseUrl,
                "--auth", "token",
                "--ca-file", caFile.toString(),
                "--output-dir", outputDir.toString()));
        if (skipGuides) {
            command.add("--skip-guides");
        }

        final ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().put(Environment.NIFI_ACCESS_TOKEN, token);

        final Process process = processBuilder.start();
        final StringBuilder output = new StringBuilder();
        // Drained on a separate thread so that a large guide build cannot fill the pipe and deadlock.
        final Thread drain = new Thread(() -> drainTo(process, output), "kb-builder-output");
        drain.setDaemon(true);
        drain.start();
        final boolean finished = process.waitFor(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            final boolean terminated = process.waitFor(FORCED_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            drain.join(TimeUnit.SECONDS.toMillis(OUTPUT_DRAIN_TIMEOUT_SECONDS));
            if (drain.isAlive()) {
                drain.interrupt();
            }
            if (!terminated) {
                throw new IllegalStateException("The builder timed out and could not be terminated within "
                        + FORCED_TERMINATION_TIMEOUT_SECONDS + " seconds");
            }
            throw new IllegalStateException("The builder did not finish within " + RUN_TIMEOUT_MINUTES
                    + " minutes. Output:\n" + output);
        }
        drain.join(TimeUnit.SECONDS.toMillis(OUTPUT_DRAIN_TIMEOUT_SECONDS));
        if (drain.isAlive()) {
            drain.interrupt();
            throw new IllegalStateException("The builder output could not be drained within "
                    + OUTPUT_DRAIN_TIMEOUT_SECONDS + " seconds");
        }
        System.out.print(output);
        return process.exitValue();
    }

    private static void drainTo(final Process process, final StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
                sink.append(line).append('\n');
                line = reader.readLine();
            }
        } catch (final IOException e) {
            sink.append("Failed to read the builder output: ").append(e.getMessage()).append('\n');
        }
    }

    private static String javaExecutable() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static Path builderJar() {
        final String jarPath = System.getProperty("kb.builder.jar");
        assertThat(jarPath).as("system property kb.builder.jar").isNotNull();
        final Path jar = Paths.get(jarPath);
        assertThat(jar).as("the packaged tool under test; run 'mvn package' on the tool module first")
                .exists();
        return jar;
    }

    private void assertCatalogStructure(final Path outputDir) throws Exception {
        assertThat(outputDir.resolve("README.md")).doesNotExist();
        assertThat(outputDir.resolve("manifest.json")).exists();
        final Path componentsDir = outputDir.resolve("components");
        assertThat(componentsDir.resolve("index.md")).exists();
        assertThat(componentsDir.resolve("index.json")).exists();

        for (final String kindDir : KIND_DIRS) {
            assertThat(componentsDir.resolve(kindDir)).isDirectory();
        }

        int componentCount = 0;
        try (Stream<Path> jsonFiles = Files.walk(componentsDir)) {
            final List<Path> componentJsons = jsonFiles
                    .filter(p -> p.getFileName().toString().equals("component.json"))
                    .toList();
            for (final Path componentJson : componentJsons) {
                componentCount++;
                assertComponentJson(componentJson);
            }
        }
        assertThat(componentCount).isPositive();
    }

    private void assertComponentJson(final Path componentJson) throws Exception {
        final JsonNode root = MAPPER.readTree(componentJson.toFile());
        final Set<String> fields = new java.util.HashSet<>();
        root.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isEqualTo(COMPONENT_JSON_FIELDS);

        final Path additionalDetails = componentJson.resolveSibling("additionalDetails.md");
        final boolean available = root.path("additionalDocumentation").path("available").asBoolean();
        if (available) {
            assertThat(additionalDetails).exists();
            assertThat(Files.size(additionalDetails)).isPositive();
        } else {
            assertThat(additionalDetails).doesNotExist();
        }
    }

    private String fingerprintOf(final Path outputDir) throws Exception {
        return MAPPER.readTree(outputDir.resolve("manifest.json").toFile())
                .path("fingerprint").asText();
    }

    private static SSLContext buildSslContext(final NiFiContainerManager.TruststoreData truststore) {
        final char[] password = truststore.getPassword().toCharArray();
        try {
            final Pkcs12TrustMaterial trust = Pkcs12TrustMaterial.fromBytes(truststore.getBytes(), password);
            try {
                return TlsContextFactory.create(Optional.empty(), Optional.of(trust));
            } finally {
                trust.clearPassword();
            }
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static Path writePemCaFile(final Path dir,
                                       final NiFiContainerManager.TruststoreData truststore) throws Exception {
        final KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = new java.io.ByteArrayInputStream(truststore.getBytes())) {
            keyStore.load(in, truststore.getPassword().toCharArray());
        }
        final Base64.Encoder encoder = Base64.getMimeEncoder(PEM_LINE_LENGTH, new byte[]{'\n'});
        final StringBuilder pem = new StringBuilder();
        final Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            final Certificate certificate = keyStore.getCertificate(aliases.nextElement());
            if (certificate == null) {
                continue;
            }
            pem.append("-----BEGIN CERTIFICATE-----\n")
                    .append(encoder.encodeToString(certificate.getEncoded()))
                    .append("\n-----END CERTIFICATE-----\n");
        }
        final Path caFilePath = dir.resolve("ca.pem");
        Files.writeString(caFilePath, pem.toString());
        return caFilePath;
    }

    private static String requestToken(final SSLContext sslContext, final String base,
                                       final String password) throws Exception {
        final HttpClient client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        final String body = "username=" + URLEncoder.encode(USERNAME, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        final HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/nifi-api/access/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        final HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        final int status = response.statusCode();
        if (status < HTTP_MIN_SUCCESS || status >= HTTP_MAX_SUCCESS) {
            throw new IllegalStateException("Token request failed with status " + status);
        }
        return response.body().trim();
    }
}

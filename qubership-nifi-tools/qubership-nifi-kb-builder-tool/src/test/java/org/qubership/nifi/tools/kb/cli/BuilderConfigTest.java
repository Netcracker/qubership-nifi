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

package org.qubership.nifi.tools.kb.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuilderConfigTest {

    @TempDir
    private Path temp;

    private final Map<String, String> env = new HashMap<>();
    private final Environment environment = env::get;

    private BuildCommand command(final String url, final String auth, final String certFile,
                                 final boolean skipGuides) {
        final List<String> args = new ArrayList<>(List.of(
                "--nifi-url", url, "--auth", auth, "--output-dir", temp.resolve("out").toString()));
        if (certFile != null) {
            args.add("--certificate-file");
            args.add(certFile);
        }
        if (skipGuides) {
            args.add("--skip-guides");
        }
        return TestCommands.populate(args.toArray(new String[0]));
    }

    private BuildCommand command(final String auth, final String certFile, final boolean skipGuides) {
        return command("https://nifi.example.com/nifi", auth, certFile, skipGuides);
    }

    @Test
    void resolvesTokenMode() {
        env.put(Environment.NIFI_ACCESS_TOKEN, "the-token-value");
        final BuilderConfig config = BuilderConfig.resolve(command("token", null, true), environment);
        assertThat(config.authMode()).isEqualTo(AuthMode.TOKEN);
        assertThat(config.token()).contains("the-token-value");
        assertThat(config.skipGuides()).isTrue();
        assertThat(config.resolver().baseUrl()).isEqualTo("https://nifi.example.com");
    }

    @Test
    void tokenModeRequiresTokenEnv() {
        assertThatThrownBy(() -> BuilderConfig.resolve(command("token", null, false), environment))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining(Environment.NIFI_ACCESS_TOKEN);
    }

    @Test
    void tokenModeRejectsCertificateFile() {
        env.put(Environment.NIFI_ACCESS_TOKEN, "tok");
        assertThatThrownBy(() -> BuilderConfig.resolve(command("token", "cert.p12", false), environment))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("--certificate-file");
    }

    @Test
    void resolvesCookieMode() {
        env.put(Environment.NIFI_AUTHORIZATION_BEARER_COOKIE, "the-cookie-value");
        final BuilderConfig config = BuilderConfig.resolve(command("cookie", null, false), environment);
        assertThat(config.authMode()).isEqualTo(AuthMode.COOKIE);
        assertThat(config.authorizationBearerCookie()).contains("the-cookie-value");
        assertThat(config.token()).isEmpty();
        assertThat(config.secretsForScan())
                .anySatisfy(secret -> assertThat(new String(secret, StandardCharsets.UTF_8))
                        .isEqualTo("the-cookie-value"));
    }

    @Test
    void cookieModeRequiresCookieEnv() {
        assertThatThrownBy(() -> BuilderConfig.resolve(command("cookie", null, false), environment))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(Environment.NIFI_AUTHORIZATION_BEARER_COOKIE);
    }

    @Test
    void cookieModeRejectsCertificateFile() {
        env.put(Environment.NIFI_AUTHORIZATION_BEARER_COOKIE, "cookie");
        assertThatThrownBy(() -> BuilderConfig.resolve(command("cookie", "cert.p12", false), environment))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("--certificate-file");
    }

    @Test
    void certificateModeRequiresCertificateFile() {
        env.put(Environment.NIFI_PKCS12_PASSWORD, "pw");
        assertThatThrownBy(() -> BuilderConfig.resolve(command("certificate", null, false), environment))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("--certificate-file is required");
    }

    @Test
    void certificateModeRequiresPassword() throws IOException {
        final Path cert = Files.createFile(temp.resolve("client.p12"));
        assertThatThrownBy(() -> BuilderConfig.resolve(command("certificate", cert.toString(), false), environment))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining(Environment.NIFI_PKCS12_PASSWORD);
    }

    @Test
    void certificateModeIgnoresStaleToken() throws IOException {
        final Path cert = Files.createFile(temp.resolve("client.p12"));
        env.put(Environment.NIFI_ACCESS_TOKEN, "should-be-ignored");
        env.put(Environment.NIFI_PKCS12_PASSWORD, "pw");
        final BuilderConfig config =
                BuilderConfig.resolve(command("certificate", cert.toString(), false), environment);
        assertThat(config.authMode()).isEqualTo(AuthMode.CERTIFICATE);
        assertThat(config.token()).isEmpty();
    }

    @Test
    void rejectsNonHttpsUrl() {
        env.put(Environment.NIFI_ACCESS_TOKEN, "tok");
        final BuildCommand httpCommand = command("http://nifi.example.com", "token", null, false);
        assertThatThrownBy(() -> BuilderConfig.resolve(httpCommand, environment))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("HTTPS");
    }
}

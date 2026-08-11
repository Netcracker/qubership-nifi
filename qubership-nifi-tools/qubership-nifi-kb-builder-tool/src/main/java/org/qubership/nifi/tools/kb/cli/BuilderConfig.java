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

import org.qubership.nifi.tools.nifi.common.http.NiFiUriResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The fully resolved and validated builder configuration. It applies the CLI validation matrix,
 * reads only the environment variable required by the selected authentication mode, and resolves
 * and validates all input and output paths without modifying the destination.
 */
public final class BuilderConfig {

    private final NiFiUriResolver resolver;
    private final AuthMode authMode;
    private final String token;
    private final String authorizationBearerCookie;
    private final Path certificateFile;
    private final char[] certificatePassword;
    private final Path caFile;
    private final boolean skipGuides;
    private final Path outputDir;

    private BuilderConfig(final Builder builder) {
        this.resolver = builder.resolver;
        this.authMode = builder.authMode;
        this.token = builder.token;
        this.authorizationBearerCookie = builder.authorizationBearerCookie;
        this.certificateFile = builder.certificateFile;
        this.certificatePassword = builder.certificatePassword;
        this.caFile = builder.caFile;
        this.skipGuides = builder.skipGuides;
        this.outputDir = builder.outputDir;
    }

    /**
     * Resolves and validates the configuration from the parsed command and the environment.
     *
     * @param command     the parsed command carrying the option values
     * @param environment the environment source
     * @return the resolved configuration
     * @throws ConfigurationException on any validation failure
     */
    public static BuilderConfig resolve(final BuildCommand command, final Environment environment) {
        final Builder builder = new Builder();
        builder.resolver = resolveUrl(command.nifiUrl());
        builder.authMode = command.auth();
        builder.skipGuides = command.skipGuides();
        builder.outputDir = resolveOutput(command.outputDir());

        resolveCaFile(command, builder);
        switch (builder.authMode) {
            case TOKEN -> resolveTokenMode(command, environment, builder);
            case COOKIE -> resolveCookieMode(command, environment, builder);
            case CERTIFICATE -> resolveCertificateMode(command, environment, builder);
        }
        validateOutputOverlap(builder);
        return new BuilderConfig(builder);
    }

    private static NiFiUriResolver resolveUrl(final String nifiUrl) {
        try {
            return NiFiUriResolver.fromBaseUrl(nifiUrl, true);
        } catch (final IllegalArgumentException e) {
            throw new ConfigurationException("Invalid --nifi-url: " + e.getMessage(), e);
        }
    }

    private static Path resolveOutput(final Path outputDir) {
        final Path resolved = outputDir.toAbsolutePath().normalize();
        if (resolved.getParent() == null) {
            throw new ConfigurationException("--output-dir must not resolve to a filesystem root");
        }
        return resolved;
    }

    private static void resolveCaFile(final BuildCommand command, final Builder builder) {
        if (command.caFile() == null) {
            return;
        }
        final Path caPath = command.caFile().toAbsolutePath().normalize();
        if (!Files.isReadable(caPath)) {
            throw new ConfigurationException("--ca-file is missing or unreadable: " + caPath);
        }
        builder.caFile = caPath;
    }

    private static void resolveTokenMode(final BuildCommand command, final Environment environment,
                                         final Builder builder) {
        if (command.certificateFile() != null) {
            throw new ConfigurationException("--certificate-file is not allowed in token mode");
        }
        final String value = environment.get(Environment.NIFI_ACCESS_TOKEN);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(Environment.NIFI_ACCESS_TOKEN + " must be set in token mode");
        }
        builder.token = value;
    }

    private static void resolveCookieMode(final BuildCommand command, final Environment environment,
                                          final Builder builder) {
        if (command.certificateFile() != null) {
            throw new ConfigurationException("--certificate-file is not allowed in cookie mode");
        }
        final String value = environment.get(Environment.NIFI_AUTHORIZATION_BEARER_COOKIE);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(
                    Environment.NIFI_AUTHORIZATION_BEARER_COOKIE + " must be set in cookie mode");
        }
        builder.authorizationBearerCookie = value;
    }

    private static void resolveCertificateMode(final BuildCommand command, final Environment environment,
                                               final Builder builder) {
        if (command.certificateFile() == null) {
            throw new ConfigurationException("--certificate-file is required in certificate mode");
        }
        final Path certPath = command.certificateFile().toAbsolutePath().normalize();
        if (!Files.isReadable(certPath)) {
            throw new ConfigurationException("--certificate-file is missing or unreadable: " + certPath);
        }
        final String value = environment.get(Environment.NIFI_PKCS12_PASSWORD);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(Environment.NIFI_PKCS12_PASSWORD + " must be set in certificate mode");
        }
        builder.certificateFile = certPath;
        builder.certificatePassword = value.toCharArray();
    }

    private static void validateOutputOverlap(final Builder builder) {
        final Path workingDir = Paths.get("").toAbsolutePath().normalize();
        rejectOverlap(builder.outputDir, workingDir, "the process working directory");
        rejectCredentialOverlap(builder.outputDir, builder.certificateFile, "the PKCS#12 file");
        rejectCredentialOverlap(builder.outputDir, builder.caFile, "the CA file");
    }

    private static void rejectCredentialOverlap(final Path outputDir, final Path credential, final String label) {
        if (credential == null) {
            return;
        }
        rejectOverlap(outputDir, credential, label);
        rejectOverlap(outputDir, credential.getParent(), "the parent directory of " + label);
    }

    private static void rejectOverlap(final Path outputDir, final Path forbidden, final String label) {
        if (forbidden != null && outputDir.equals(forbidden.toAbsolutePath().normalize())) {
            throw new ConfigurationException("--output-dir must not resolve to " + label);
        }
    }

    /**
     * Returns the URI resolver bound to the normalized NiFi base.
     *
     * @return the resolver
     */
    public NiFiUriResolver resolver() {
        return resolver;
    }

    /**
     * Returns the selected authentication mode.
     *
     * @return the auth mode
     */
    public AuthMode authMode() {
        return authMode;
    }

    /**
     * Returns the bearer token in token mode.
     *
     * @return the optional token
     */
    public Optional<String> token() {
        return Optional.ofNullable(token);
    }

    /**
     * Returns the authorization bearer cookie value in cookie mode.
     *
     * @return the optional cookie value
     */
    public Optional<String> authorizationBearerCookie() {
        return Optional.ofNullable(authorizationBearerCookie);
    }

    /**
     * Returns the PKCS#12 certificate file in certificate mode.
     *
     * @return the optional certificate file
     */
    public Optional<Path> certificateFile() {
        return Optional.ofNullable(certificateFile);
    }

    /**
     * Returns a copy of the certificate password in certificate mode.
     *
     * @return the optional password characters
     */
    public Optional<char[]> certificatePassword() {
        return certificatePassword == null ? Optional.empty() : Optional.of(certificatePassword.clone());
    }

    /**
     * Returns the optional CA file.
     *
     * @return the optional CA file
     */
    public Optional<Path> caFile() {
        return Optional.ofNullable(caFile);
    }

    /**
     * Reports whether guide collection is skipped.
     *
     * @return {@code true} when guides are skipped
     */
    public boolean skipGuides() {
        return skipGuides;
    }

    /**
     * Returns the absolute output directory.
     *
     * @return the output directory
     */
    public Path outputDir() {
        return outputDir;
    }

    /**
     * Returns the secret byte sequences to scan generated output for, as defense in depth.
     *
     * @return the secrets to scan
     */
    public List<byte[]> secretsForScan() {
        final List<byte[]> secrets = new ArrayList<>();
        if (token != null) {
            secrets.add(token.getBytes(StandardCharsets.UTF_8));
        }
        if (authorizationBearerCookie != null) {
            secrets.add(authorizationBearerCookie.getBytes(StandardCharsets.UTF_8));
        }
        if (certificatePassword != null) {
            secrets.add(new String(certificatePassword).getBytes(StandardCharsets.UTF_8));
        }
        return secrets;
    }

    /**
     * Clears retained mutable secret material after the SSL context has consumed it.
     */
    public void clearSecrets() {
        if (certificatePassword != null) {
            Arrays.fill(certificatePassword, '\0');
        }
    }

    private static final class Builder {
        private NiFiUriResolver resolver;
        private AuthMode authMode;
        private String token;
        private String authorizationBearerCookie;
        private Path certificateFile;
        private char[] certificatePassword;
        private Path caFile;
        private boolean skipGuides;
        private Path outputDir;
    }
}

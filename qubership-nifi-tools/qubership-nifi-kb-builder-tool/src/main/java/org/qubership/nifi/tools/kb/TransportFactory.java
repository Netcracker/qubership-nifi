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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.nifi.tools.kb.cli.AuthMode;
import org.qubership.nifi.tools.kb.cli.BuilderConfig;
import org.qubership.nifi.tools.nifi.common.AuthorizationBearerCookieAuthenticator;
import org.qubership.nifi.tools.nifi.common.BearerTokenAuthenticator;
import org.qubership.nifi.tools.nifi.common.ClientKeyMaterial;
import org.qubership.nifi.tools.nifi.common.NiFiHttpClient;
import org.qubership.nifi.tools.nifi.common.NiFiRequestAuthenticator;
import org.qubership.nifi.tools.nifi.common.NiFiRestClient;
import org.qubership.nifi.tools.nifi.common.NoAuthentication;
import org.qubership.nifi.tools.nifi.common.PemTrustMaterial;
import org.qubership.nifi.tools.nifi.common.Pkcs12KeyMaterial;
import org.qubership.nifi.tools.nifi.common.TlsContextFactory;
import org.qubership.nifi.tools.nifi.common.TrustMaterial;

import javax.net.ssl.SSLContext;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Builds the authenticated REST transport for a single run. The certificate password is cleared as
 * soon as the TLS context has consumed it, and the configuration is asked to drop its own copies of
 * the secrets once the transport exists.
 */
public final class TransportFactory {

    private static final int CONNECT_TIMEOUT_SECONDS = 30;

    /**
     * Creates the REST client for the given configuration.
     *
     * @param config the resolved configuration
     * @return the REST client
     */
    public NiFiRestClient create(final BuilderConfig config) {
        final SSLContext sslContext = buildSslContext(config);
        final NiFiRequestAuthenticator authenticator = buildAuthenticator(config);
        config.clearSecrets();
        final HttpClient jdkClient =
                NiFiHttpClient.newHttpClient(sslContext, Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
        final NiFiHttpClient httpClient = new NiFiHttpClient(jdkClient, config.resolver(), authenticator);
        return new NiFiRestClient(httpClient, new ObjectMapper());
    }

    private NiFiRequestAuthenticator buildAuthenticator(final BuilderConfig config) {
        return switch (config.authMode()) {
            case TOKEN -> new BearerTokenAuthenticator(config.token().orElseThrow());
            case COOKIE -> new AuthorizationBearerCookieAuthenticator(
                    config.authorizationBearerCookie().orElseThrow());
            case CERTIFICATE -> NoAuthentication.INSTANCE;
        };
    }

    private SSLContext buildSslContext(final BuilderConfig config) {
        final Optional<TrustMaterial> trust = config.caFile().map(path -> PemTrustMaterial.fromFile(path));
        Optional<ClientKeyMaterial> key = Optional.empty();
        if (config.authMode() == AuthMode.CERTIFICATE) {
            final Path certFile = config.certificateFile().orElseThrow();
            final char[] password = config.certificatePassword().orElseThrow();
            try {
                key = Optional.of(Pkcs12KeyMaterial.fromFile(certFile, password));
            } finally {
                Arrays.fill(password, '\0');
            }
        }
        return TlsContextFactory.create(key, trust);
    }
}

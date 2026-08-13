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
import org.qubership.nifi.tools.nifi.common.auth.AuthorizationBearerCookieAuthenticator;
import org.qubership.nifi.tools.nifi.common.auth.BearerTokenAuthenticator;
import org.qubership.nifi.tools.nifi.common.auth.NiFiRequestAuthenticator;
import org.qubership.nifi.tools.nifi.common.auth.NoAuthentication;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiRestClient;
import org.qubership.nifi.tools.nifi.common.tls.ClientKeyMaterial;
import org.qubership.nifi.tools.nifi.common.tls.PemTrustMaterial;
import org.qubership.nifi.tools.nifi.common.tls.Pkcs12KeyMaterial;
import org.qubership.nifi.tools.nifi.common.tls.TlsContextFactory;
import org.qubership.nifi.tools.nifi.common.tls.TrustMaterial;

import javax.net.ssl.SSLContext;
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
     * <p>Consumes the configuration's secrets: once the TLS context and the authenticator hold what
     * they need, this calls {@link BuilderConfig#clearSecrets()}, so the certificate password is no
     * longer readable from {@code config} after this returns.
     *
     * @param config the resolved configuration; its retained certificate password is cleared before
     *               this returns
     * @return the REST client, with a 30-second connect timeout
     */
    public NiFiRestClient create(final BuilderConfig config) {
        final SSLContext sslContext = buildSslContext(config);
        final NiFiRequestAuthenticator authenticator = buildAuthenticator(config);
        config.clearSecrets();
        final NiFiHttpClient httpClient = new NiFiHttpClient(
                NiFiHttpClient.newHttpClient(sslContext, Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)),
                config.resolver(), authenticator);
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
        if (config.authMode() != AuthMode.CERTIFICATE) {
            return TlsContextFactory.create(Optional.empty(), trust);
        }
        final Path certFile = config.certificateFile().orElseThrow();
        final char[] password = config.certificatePassword().orElseThrow();
        final Pkcs12KeyMaterial keyMaterial;
        try {
            keyMaterial = Pkcs12KeyMaterial.fromFile(certFile, password);
        } finally {
            Arrays.fill(password, '\0');
        }
        try {
            return TlsContextFactory.create(Optional.<ClientKeyMaterial>of(keyMaterial), trust);
        } finally {
            keyMaterial.clearPassword();
        }
    }
}

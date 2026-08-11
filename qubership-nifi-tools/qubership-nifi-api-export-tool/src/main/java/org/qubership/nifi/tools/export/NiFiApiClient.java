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

package org.qubership.nifi.tools.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.HttpRequest;
import org.qubership.nifi.tools.nifi.common.auth.NiFiRequestAuthenticator;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiHttpResponse;
import org.qubership.nifi.tools.nifi.common.http.NiFiRestClient;
import org.qubership.nifi.tools.nifi.common.http.NiFiUriResolver;
import org.qubership.nifi.tools.nifi.common.tls.Pkcs12TrustMaterial;
import org.qubership.nifi.tools.nifi.common.tls.TlsContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * HTTP client for the NiFi REST API. It delegates transport, TLS, and JSON handling to the shared
 * {@code qubership-nifi-tools-nifi-common} library while preserving this tool's username/password
 * token acquisition and its GET/POST/DELETE surface.
 */
public class NiFiApiClient implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(NiFiApiClient.class);

    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final String TOKEN_PATH = "/nifi-api/access/token";

    private final String username;
    private final String password;
    private final ObjectMapper mapper = new ObjectMapper();
    private final NiFiUriResolver resolver;
    private final NiFiHttpClient httpClient;
    private final NiFiRestClient restClient;
    private final MutableBearerAuthenticator authenticator = new MutableBearerAuthenticator();

    /**
     * Creates a new client that trusts the certificates in the supplied container truststore.
     *
     * @param url            the base URL of the NiFi instance
     * @param user           the username for authentication
     * @param pass           the password for authentication
     * @param truststoreData the NiFi truststore used to build the SSL context
     * @throws Exception if the SSL context cannot be built
     */
    public NiFiApiClient(final String url, final String user, final String pass,
                         final NiFiContainerManager.TruststoreData truststoreData) throws Exception {
        this.username = user;
        this.password = pass;
        this.resolver = NiFiUriResolver.fromBaseUrl(url, false);
        final CloseableHttpClient transport = buildTruststoreHttpClient(truststoreData);
        this.httpClient = new NiFiHttpClient(transport, resolver, authenticator);
        this.restClient = new NiFiRestClient(httpClient, mapper);
    }

    NiFiApiClient(final String url, final String user, final String pass,
                  final CloseableHttpClient client) {
        this.username = user;
        this.password = pass;
        this.resolver = NiFiUriResolver.fromBaseUrl(url, false);
        this.httpClient = new NiFiHttpClient(client, resolver, authenticator);
        this.restClient = new NiFiRestClient(httpClient, mapper);
    }

    private static CloseableHttpClient buildTruststoreHttpClient(
            final NiFiContainerManager.TruststoreData ts) {
        final char[] storePassword = ts.getPassword().toCharArray();
        try {
            final Pkcs12TrustMaterial trust = Pkcs12TrustMaterial.fromBytes(ts.getBytes(), storePassword);
            try {
                final SSLContext sslContext = TlsContextFactory.create(Optional.empty(), Optional.of(trust));
                return NiFiHttpClient.newHttpClient(sslContext,
                        Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
            } finally {
                trust.clearPassword();
            }
        } finally {
            Arrays.fill(storePassword, '\0');
        }
    }

    /**
     * Authenticates against the NiFi access token endpoint and stores the bearer token.
     *
     * @throws Exception if the HTTP request fails or authentication is rejected
     */
    public void authenticate() throws Exception {
        final String body = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        final NiFiHttpResponse response = httpClient.post(resolver.resolve(TOKEN_PATH), body,
                "application/x-www-form-urlencoded", "text/plain");
        if (!response.isSuccess()) {
            throw new RuntimeException("Authentication failed with status " + response.statusCode());
        }
        authenticator.setToken(response.bodyAsText().trim());
        LOG.info("Authentication successful");
    }

    /**
     * Performs a GET request to the given NiFi API path.
     *
     * @param path the API path (relative to base URL)
     * @return the parsed JSON response
     * @throws Exception if the HTTP request fails or the response status is not 2xx
     */
    public JsonNode get(final String path) throws Exception {
        return restClient.getJson(resolver.resolve(path));
    }

    /**
     * Performs a POST request to the given NiFi API path with a JSON body.
     *
     * @param path     the API path (relative to base URL)
     * @param jsonBody the JSON request body
     * @return the parsed JSON response
     * @throws Exception if the HTTP request fails or the response status is not 2xx
     */
    public JsonNode post(final String path, final String jsonBody) throws Exception {
        return restClient.postJson(resolver.resolve(path), jsonBody);
    }

    /**
     * Performs a DELETE request to the given NiFi API path.
     *
     * @param path the API path (relative to base URL)
     * @throws Exception if the HTTP request fails
     */
    public void delete(final String path) throws Exception {
        final NiFiHttpResponse response = httpClient.delete(resolver.resolve(path));
        if (!response.isSuccess()) {
            LOG.warn("DELETE {} returned status {}", path, response.statusCode());
        }
    }

    /**
     * Closes the underlying HTTP client and its connection pool.
     */
    @Override
    public void close() {
        restClient.close();
    }

    /**
     * A bearer authenticator whose token is populated after the username/password exchange. It
     * applies the {@code Authorization} header only once a token is available.
     */
    private static final class MutableBearerAuthenticator implements NiFiRequestAuthenticator {

        private volatile String token;

        void setToken(final String accessToken) {
            this.token = accessToken;
        }

        @Override
        public void apply(final HttpRequest request) {
            final String current = token;
            if (current != null && !current.isBlank()) {
                request.setHeader("Authorization", "Bearer " + current);
            }
        }
    }
}

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

/**
 * HTTP client for NiFi REST API. Handles authentication and JSON requests.
 */
public class NiFiApiClient {

    private static final Logger LOG = LoggerFactory.getLogger(NiFiApiClient.class);

    private static final int HTTP_OK = 200;
    private static final int HTTP_CREATED = 201;

    private final String baseUrl;
    private final String username;
    private final String password;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private String bearerToken;

    /**
     * Creates a new NiFiApiClient for the given NiFi instance.
     *
     * @param url            the base URL of the NiFi instance
     * @param user           the username for authentication
     * @param pass           the password for authentication
     * @param truststoreData the NiFi truststore used to build the SSL context
     * @throws Exception if the SSL context cannot be built
     */
    public NiFiApiClient(final String url, final String user, final String pass,
                         final NiFiContainerManager.TruststoreData truststoreData) throws Exception {
        this.baseUrl = url;
        this.username = user;
        this.password = pass;
        this.httpClient = buildTruststoreHttpClient(truststoreData);
    }

    NiFiApiClient(final String url, final String user, final String pass,
                  final HttpClient client) {
        this.baseUrl = url;
        this.username = user;
        this.password = pass;
        this.httpClient = client;
    }

    private HttpClient buildTruststoreHttpClient(
            final NiFiContainerManager.TruststoreData ts) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(new ByteArrayInputStream(ts.getBytes()), ts.getPassword().toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
    }

    /**
     * Authenticates against the NiFi access token endpoint and stores the bearer token.
     *
     * @throws Exception if the HTTP request fails or authentication is rejected
     */
    public void authenticate() throws Exception {
        String body = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/nifi-api/access/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HTTP_CREATED && response.statusCode() != HTTP_OK) {
            throw new RuntimeException(
                    "Authentication failed with status " + response.statusCode() + ": " + response.body());
        }
        bearerToken = response.body().trim();
        LOG.info("Authentication successful");
    }

    /**
     * Performs a GET request to the given NiFi API path.
     *
     * @param path the API path (relative to base URL)
     * @return the parsed JSON response
     * @throws Exception if the HTTP request fails or the response status is not 200
     */
    public JsonNode get(final String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HTTP_OK) {
            throw new RuntimeException(
                    "GET " + path + " failed with status " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }

    /**
     * Performs a POST request to the given NiFi API path with a JSON body.
     *
     * @param path     the API path (relative to base URL)
     * @param jsonBody the JSON request body
     * @return the parsed JSON response
     * @throws Exception if the HTTP request fails or the response status is not 200/201
     */
    public JsonNode post(final String path, final String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HTTP_OK && response.statusCode() != HTTP_CREATED) {
            throw new RuntimeException(
                    "POST " + path + " failed with status " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }

    /**
     * Performs a DELETE request to the given NiFi API path.
     *
     * @param path the API path (relative to base URL)
     * @throws Exception if the HTTP request fails
     */
    public void delete(final String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + bearerToken)
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HTTP_OK) {
            LOG.warn("DELETE {} returned status {}", path, response.statusCode());
        }
    }
}

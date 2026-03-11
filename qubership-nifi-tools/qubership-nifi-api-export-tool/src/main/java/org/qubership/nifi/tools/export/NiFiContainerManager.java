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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Manages the NiFi TestContainer lifecycle.
 */
public final class NiFiContainerManager {

    private static final Logger LOG = LoggerFactory.getLogger(NiFiContainerManager.class);
    private static final int NIFI_PORT = 8443;

    private final String nifiImage;
    private final String username;
    private final String password;
    private final int timeoutSeconds;
    private final int port;

    private GenericContainer<?> container;

    /**
     * Creates a new NiFiContainerManager.
     *
     * @param image    the Docker image to use for the NiFi container
     * @param user     the NiFi single-user username
     * @param pass     the NiFi single-user password
     * @param timeout  the startup timeout in seconds
     * @param hostPort the fixed host port to bind NiFi's 8443 to
     */
    public NiFiContainerManager(final String image, final String user, final String pass, final int timeout,
                                final int hostPort) {
        this.nifiImage = image;
        this.username = user;
        this.password = pass;
        this.timeoutSeconds = timeout;
        this.port = hostPort;
    }

    private static final int HTTP_UNAUTHORIZED_STATUS_CODE = 401;

    /**
     * Starts the NiFi container and waits until the API is ready.
     */
    public void start() {
        LOG.info("Starting NiFi container: {}", nifiImage);
        container = new GenericContainer<>(nifiImage)
                .withEnv("SINGLE_USER_CREDENTIALS_USERNAME", username)
                .withEnv("SINGLE_USER_CREDENTIALS_PASSWORD", password)
                .withEnv("NIFI_WEB_PROXY_HOST", "localhost:" + port)
                .waitingFor(Wait.forHttps("/nifi-api/controller/config")
                        .allowInsecure()
                        .forStatusCode(HTTP_UNAUTHORIZED_STATUS_CODE)
                        .withStartupTimeout(Duration.ofSeconds(timeoutSeconds))
                );
        container.setPortBindings(List.of("127.0.0.1:" + port + ":" + NIFI_PORT));
        container.start();
        LOG.info("NiFi container started. Base URL: {}", getBaseUrl());
    }

    /**
     * Stops the NiFi container if it is running.
     */
    public void stop() {
        if (container != null && container.isRunning()) {
            LOG.info("Stopping NiFi container");
            container.close();
        }
    }

    /**
     * Returns the base HTTPS URL of the running NiFi container.
     *
     * @return the base URL (e.g. {@code https://localhost:12345})
     * @throws IllegalStateException if the container is not running
     */
    public String getBaseUrl() {
        if (container == null || !container.isRunning()) {
            throw new IllegalStateException("NiFi container is not running");
        }
        return "https://localhost:" + port;
    }

    /**
     * Copies the NiFi truststore from the running container and returns it along
     * with its password read from the container's {@code nifi.properties}.
     *
     * @return truststore bytes and password
     * @throws Exception if the container is not running or copying fails
     */
    public TruststoreData readTruststore() throws Exception {
        if (container == null || !container.isRunning()) {
            throw new IllegalStateException("NiFi container is not running");
        }
        String truststorePassword = container.copyFileFromContainer(
            "/opt/nifi/nifi-current/conf/nifi.properties",
            is -> {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("nifi.security.truststorePasswd");
            }
        );
        byte[] truststoreBytes = container.copyFileFromContainer(
            "/opt/nifi/nifi-current/conf/truststore.p12",
            is -> is.readAllBytes()
        );
        return new TruststoreData(truststoreBytes, truststorePassword);
    }

    /**
     * Holds the raw bytes and password of the NiFi truststore.
     */
    public static final class TruststoreData {
        private final byte[] bytes;
        private final String password;

        /**
         * @param truststoreBytes    raw PKCS12 truststore bytes
         * @param truststorePassword truststore password
         */
        public TruststoreData(final byte[] truststoreBytes, final String truststorePassword) {
            this.bytes = truststoreBytes;
            this.password = truststorePassword;
        }

        /** @return the raw PKCS12 truststore bytes */
        public byte[] getBytes() {
            return bytes;
        }

        /** @return the truststore password */
        public String getPassword() {
            return password;
        }
    }
}

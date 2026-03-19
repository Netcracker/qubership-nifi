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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CLI entry point. Parses arguments, starts NiFi in TestContainers, collects component descriptors,
 * and writes JSON output.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private static final String DEFAULT_VERSION = "2.7.2";
    private static final String DEFAULT_OUTPUT_DIR = "./nifi-api-output";
    private static final int DEFAULT_TIMEOUT = 180;
    private static final int DEFAULT_PORT = 18443;

    private Main() { }

    /**
     * Application entry point.
     *
     * @param args command-line arguments (--version, --output-dir, --timeout, --port);
     *             each flag must be followed by its value — a flag provided as the last
     *             argument without a value causes an {@link ArrayIndexOutOfBoundsException}
     * @throws Exception if any step of the export process fails
     */
    public static void main(final String[] args) throws Exception {
        String nifiVersion = DEFAULT_VERSION;
        String outputDir = DEFAULT_OUTPUT_DIR;
        String username = "admin";
        String password = UUID.randomUUID().toString();
        int timeout = DEFAULT_TIMEOUT;
        int port = DEFAULT_PORT;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--version":
                    nifiVersion = args[++i];
                    break;
                case "--output-dir":
                    outputDir = args[++i];
                    break;
                case "--timeout":
                    timeout = Integer.parseInt(args[++i]);
                    break;
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                default:
                    // ignore unknown flags
                    break;
            }
        }

        String nifiImage = "apache/nifi:" + nifiVersion;

        LOG.info("Starting NiFi API diff tool");
        LOG.info("  version    : {}", nifiVersion);
        LOG.info("  output-dir : {}", outputDir);
        LOG.info("  username   : {}", username);
        LOG.info("  timeout    : {}s", timeout);
        LOG.info("  port       : {}", port);

        try (NiFiContainerManager containerManager =
                new NiFiContainerManager(nifiImage, username, password, timeout, port)) {
            containerManager.start();
            String baseUrl = containerManager.getBaseUrl();
            NiFiContainerManager.TruststoreData truststoreData = containerManager.readTruststore();
            NiFiApiClient apiClient = new NiFiApiClient(baseUrl, username, password, truststoreData);
            apiClient.authenticate();

            ComponentDescriptorCollector collector = new ComponentDescriptorCollector(apiClient);
            OutputWriter writer = new OutputWriter(outputDir);

            for (ComponentKind kind : ComponentKind.values()) {
                LOG.info("Collecting {} descriptors...", kind);
                List<Map<String, Object>> components = collector.collect(kind);
                LOG.info("  found {} components for {}", components.size(), kind);
                writer.write(kind, components);
            }

            LOG.info("Done. Output written to: {}", outputDir);
        }
    }
}

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

package org.qubership.cloud.nifi.config;

import org.qubership.cloud.nifi.config.common.BasePropertiesManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.IOException;

/**
 * The {@code PropertiesManager} is responsible for managing configuration properties
 * and logging settings for the qubership-nifi application.
 * <p>
 * <b>Responsibilities:</b>
 * <ul>
 *   <li>Accesses Consul to retrieve dynamic configuration properties.</li>
 *   <li>Generates {@code nifi.properties}, {@code custom.properties} and {@code logback.xml}
 *   files before application startup.</li>
 *   <li>Watches for configuration changes and periodically updates {@code logback.xml}
 *   to support dynamic logging level changes.</li>
 * </ul>
 * <p>
 * This class is a Spring component with refresh scope, allowing it to respond to configuration changes at runtime.
 */
@Component
@RefreshScope
public class PropertiesManager {
    private static final Logger LOG = LoggerFactory.getLogger(PropertiesManager.class);
    @Autowired
    private BasePropertiesManager basePropertiesManager;
    //Not used, kept for backward compatibility:
    private ConfigurableEnvironment env;
    private Environment appEnv;

    /**
     * Default constructor.
     * @param configEnv instance of ConfigurableEnvironment to use
     * @param applicationEnv instance of Environment to use
     */
    @Autowired
    public PropertiesManager(final ConfigurableEnvironment configEnv, final Environment applicationEnv) {
        this.env = configEnv;
        this.appEnv = applicationEnv;
    }

    /**
     * Generates nifi.properties file.
     * @throws IOException if an I/O error occurs while reading or writing files
     * @throws ParserConfigurationException if a configuration error occurs while parsing XML
     * @throws TransformerException if an error occurs during XML transformation
     * @throws SAXException if an error occurs while parsing XML
     */
    public void generateNifiProperties() throws IOException, ParserConfigurationException,
            TransformerException, SAXException {
        this.basePropertiesManager.generateNifiPropertiesAndLogbackConfig();
    }

    /**
     * Handles environment change events by regenerating the {@code logback.xml} file
     * to support dynamic logging level changes.
     * <p>
     * This method is triggered automatically by Spring when configuration changes are detected in the environment.
     *
     * @param event the environment change event containing the changed property keys
     */
    @EventListener
    public void handleChangeEvent(EnvironmentChangeEvent event) {
        LOG.debug("Change event received for keys: {}", event.getKeys());
        try {
            this.basePropertiesManager.updateLogbackConfig();
        } catch (Exception e) {
            LOG.error("Exception while processing change event from consul", e);
        }
    }
}


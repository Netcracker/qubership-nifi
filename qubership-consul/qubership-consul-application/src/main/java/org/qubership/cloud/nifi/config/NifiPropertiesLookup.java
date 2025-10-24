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


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


/**
 * Main application class.
 */
@SpringBootApplication
@EnableAutoConfiguration
@ComponentScan(basePackages = "org.qubership.cloud.nifi.config")
public class NifiPropertiesLookup implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(NifiPropertiesLookup.class);
    private PropertiesManager propertiesManager;
    private XmlConfigValidator xmlConfigValidator;

    @Value("${config.notify-completion.path}")
    private String path;

    /**
     * Default constructor.
     * @param pm instance of PropertiesManager to use
     * @param validator instance of XmlConfigValidator to use
     */
    @Autowired
    public NifiPropertiesLookup(final PropertiesManager pm, final XmlConfigValidator validator) {
        this.propertiesManager = pm;
        this.xmlConfigValidator = validator;
    }

    /**
     * Main entry point to application.
     * @param args startup arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(NifiPropertiesLookup.class, args);
    }

    /**
     * Runs main application functions: creates nifi properties file and checks XML configurations for errors
     * and restores them from backup, if necessary.
     * @param args
     * @throws IOException
     * @throws ParserConfigurationException
     * @throws TransformerException
     * @throws SAXException
     */
    @Override
    public void run(String... args)
            throws IOException, ParserConfigurationException, TransformerException, SAXException {
        propertiesManager.generateNifiProperties();
        xmlConfigValidator.validate();
        notifyCompletionToStartScript();
    }

    private void notifyCompletionToStartScript() {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            Path fPath = Paths.get(path + "initial-config-completed.txt");
            Files.write(fPath, timestamp.getBytes());
            LOG.info("Consul App completion file created:{} ", fPath.toAbsolutePath());
        } catch (Exception e) {
            LOG.error("Error while creating completion file for consul app", e);
        }
    }
}

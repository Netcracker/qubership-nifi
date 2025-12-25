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
import org.springframework.cloud.consul.config.ConsulPropertySource;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;


@Component
@RefreshScope
public class PropertiesManager {
    private static final Logger LOG = LoggerFactory.getLogger(PropertiesManager.class);
    @Value("classpath:logback-template.xml")
    private Resource sourceXmlFile;
    @Value("classpath:nifi_default.properties")
    private Resource defaultPropertiesFile;
    @Value("classpath:custom.properties")
    private Resource defaultCustomPropertiesFile;
    @Value("classpath:nifi_internal.properties")
    private Resource internalPropertiesFile;
    @Value("classpath:nifi_internal_comments.properties")
    private Resource internalPropertiesCommentsFile;
    private Map<String, String> consulPropertiesMap = new HashMap<>();
    private Properties props;
    @Value("${config.file.path}")
    private String path;
    private ConfigurableEnvironment env;
    private Environment appEnv;

    private static final Set<String> SKIPPED_CUSTOM_PROPERTIES = new HashSet<>();

    static {
        SKIPPED_CUSTOM_PROPERTIES.add("nifi.http-auth-proxying-disabled-schemes");
        SKIPPED_CUSTOM_PROPERTIES.add("nifi.http-auth-tunneling-disabled-schemes");
        SKIPPED_CUSTOM_PROPERTIES.add("nifi.cluster.base-node-count");
        SKIPPED_CUSTOM_PROPERTIES.add("nifi.cluster.start-mode");
        SKIPPED_CUSTOM_PROPERTIES.add("nifi.nifi-registry.nar-provider-enabled");
        SKIPPED_CUSTOM_PROPERTIES.add("nifi.conf.clean-db-repository");
        SKIPPED_CUSTOM_PROPERTIES.add("nifi.conf.clean-configuration");
        SKIPPED_CUSTOM_PROPERTIES.add("nifi.extensions.retry.attempts");
        SKIPPED_CUSTOM_PROPERTIES.add("nifi.extensions.retry.delay");

    }

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
     * @throws IOException
     * @throws ParserConfigurationException
     * @throws TransformerException
     * @throws SAXException
     */
    public void generateNifiProperties() throws IOException, ParserConfigurationException,
            TransformerException, SAXException {
        readConsulProperties();
        buildPropertiesFile();
        buildCustomPropertiesFile();
        buildLogbackXMLFile();
        LOG.info("nifi properties files generated");
    }

    private static final int LOGGER_PREFIX_LENGTH = "logger.".length();

    private void buildLogbackXMLFile()
            throws ParserConfigurationException, IOException, SAXException, TransformerException {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        //configure to avoid XXE attacks:
        dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbFactory.setXIncludeAware(false);
        dbFactory.setExpandEntityReferences(false);
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = null;
        try (InputStream is = new BufferedInputStream(sourceXmlFile.getInputStream())) {
            doc = dBuilder.parse(is);
        }
        doc.getDocumentElement().normalize();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Root element: {}", doc.getDocumentElement().getNodeName());
        }
        NodeList nodeList = doc.getElementsByTagName("logger");

        for (String consulKey : consulPropertiesMap.keySet()) {
            boolean loggerElementFound = false;
            // if it starts with "logger.*" then, check element in logback.xml
            if (consulKey.toLowerCase().startsWith("logger.")) {
                String xmlKey = consulKey.substring(LOGGER_PREFIX_LENGTH);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("current xmlKey: {}", xmlKey);
                }
                for (int i = 0; i < nodeList.getLength(); i++) {
                    Node prop = nodeList.item(i);
                    NamedNodeMap attr = prop.getAttributes();
                    if (attr != null) {
                        Node loggerKey = attr.getNamedItem("name");
                        if (xmlKey.equalsIgnoreCase(loggerKey.getNodeValue())) {
                            //key found then update element in xml file
                            attr.getNamedItem("level").setTextContent(consulPropertiesMap.get(consulKey));
                            loggerElementFound = true;
                            break;
                        }
                    }
                }
                if (!loggerElementFound) {
                    // add new element to xml file
                    Element newLogger = doc.createElement("logger");
                    newLogger.setAttribute("name", xmlKey);
                    newLogger.setAttribute("level", consulPropertiesMap.get(consulKey));
                    Node firstLogNode = doc.getElementsByTagName("logger").item(0);
                    //insert before first node with tag=logger
                    doc.getDocumentElement().insertBefore(newLogger, firstLogNode);
                }
            }
        }
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(path + "logback.xml"))) {
            //write to new file:
            writeXml(doc, output);
        }
        LOG.info("logback.xml file created at path: {}", path);
    }

    private void writeXml(Document doc, OutputStream output) throws TransformerException {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        //configure to avoid XXE attacks:
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = transformerFactory.newTransformer();

        transformer.setOutputProperty(OutputKeys.INDENT, "yes");

        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(output);

        transformer.transform(source, result);
    }

    private void readConsulProperties() {
        MutablePropertySources srcs = env.getPropertySources();
        Set<String> allPropertyNames = new HashSet<>();
        for (PropertySource src1 : srcs) {

            // get properties  for ConsulPropertySource..
            if (ConsulPropertySource.class.isAssignableFrom(src1.getClass())) {
                String[] allNames = ((ConsulPropertySource) src1).getPropertyNames();
                if (allNames != null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("allNames array: {}", List.of(allNames));
                    }
                    //fetch only properties starting with logger.* and nifi.*
                    for (String name : allNames) {
                        String lowercaseName = name.toLowerCase();
                        if (lowercaseName.startsWith("logger") || lowercaseName.startsWith("nifi")) {
                            allPropertyNames.add(name);
                        }
                    }
                }
            }

        }
        LOG.debug("All property names = {}", allPropertyNames);
        for (String property : allPropertyNames) {
            consulPropertiesMap.put(property, appEnv.getProperty(property));
        }
        LOG.debug("consulPropertiesMap map: {}", consulPropertiesMap);
    }

    /**
     * Builds custom.properties file.
     * @throws IOException
     */
    public void buildCustomPropertiesFile() throws IOException {
        String fileName = path + "custom.properties";

        Map<String, String> combinedNifiProperties = getOrderedProperties(defaultCustomPropertiesFile);
        //consul
        for (String consulKey : consulPropertiesMap.keySet()) {
            // if it's in the custom list, add it
            if (SKIPPED_CUSTOM_PROPERTIES.contains(consulKey)) {
                combinedNifiProperties.put(consulKey, consulPropertiesMap.get(consulKey));
            }
        }
        //write nifiProperties to properties file
        try (PrintWriter pw = new PrintWriter(new FileOutputStream(fileName))) {
            //Storing the map in properties file in order
            for (String s : combinedNifiProperties.keySet()) {
                pw.print(s);
                pw.print("=");
                pw.println(combinedNifiProperties.get(s));
            }
        }
        LOG.info("Custom Properties file created : {}", fileName);
    }

    /**
     * Builds nifi.properties file.
     * @throws IOException
     */
    public void buildPropertiesFile() throws IOException {
        String fileName = path + "nifi.properties";

        //we have to build combinedNifiProperties properties map. copy nifiDefaultProps as is without order change
        Map<String, String> combinedNifiProperties = getOrderedProperties(defaultPropertiesFile);

        //consul
        for (String consulKey : consulPropertiesMap.keySet()) {
            // if it starts with "nifi.*" and not in the custom list then, add in nifiProperties
            if (consulKey.toLowerCase().startsWith("nifi.")
                    && !SKIPPED_CUSTOM_PROPERTIES.contains(consulKey)) {
                combinedNifiProperties.put(consulKey, consulPropertiesMap.get(consulKey));
            }
        }

        //nifi_internal properties should be placed as is, in same order
        Map<String, String> nifiInternalProps = getOrderedProperties(internalPropertiesFile);
        for (String s : nifiInternalProps.keySet()) {
            combinedNifiProperties.put(s, nifiInternalProps.get(s));
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("combined nifi Properties: {}", combinedNifiProperties);
        }

        // remove properties from combinedNifiProperties map that are present on nifi_internal_comments.properties
        Set<String> readOnlyNifiProps = new HashSet<>();
        readOnlyNifiProps.add("nifi.security.identity.mapping.pattern.dn");
        readOnlyNifiProps.add("nifi.security.identity.mapping.value.dn");
        readOnlyNifiProps.add("nifi.security.identity.mapping.transform.dn");
        for (String s : readOnlyNifiProps) {
            combinedNifiProperties.remove(s);
        }

        //write nifiProperties to properties file
        try (PrintWriter pw = new PrintWriter(new FileOutputStream(fileName)); BufferedReader reader =
                new BufferedReader(new InputStreamReader(internalPropertiesCommentsFile.getInputStream()))) {
            //Storing the map in properties file in order
            for (String s : combinedNifiProperties.keySet()) {
                pw.print(s);
                pw.print("=");
                pw.println(combinedNifiProperties.get(s));
            }

            // store all commented properties from nifi_internal_comments.properties in file
            String line = reader.readLine();
            while (line != null) {
                pw.println(line);
                // read next line
                line = reader.readLine();
            }
        }
        LOG.info("Nifi Properties file created : {}", fileName);
    }

    /**
     * Gets ordered properties map.
     * @param rs consul data
     * @return ordered properties map
     * @throws IOException
     */
    public Map<String, String> getOrderedProperties(Resource rs) throws IOException {
        Map<String, String> mp = new LinkedHashMap<>();
        try (InputStream in = rs.getInputStream()) {
            (new Properties() {
                public synchronized Object put(Object key, Object value) {
                    return mp.put((String) key, (String) value);
                }
            }).load(in);
        }
        return mp;
    }

    /**
     * Handles environment change event: generates updated logback.xml to support dynamic logging level changes.
     * @param event environment change event
     */
    @EventListener
    public void handleChangeEvent(EnvironmentChangeEvent event) {
        LOG.debug("Change event received for keys: {}", event.getKeys());
        readConsulProperties();
        try {
            buildLogbackXMLFile();
        } catch (Exception e) {
            LOG.error("Exception while processing change event from consul", e);
        }
    }
}


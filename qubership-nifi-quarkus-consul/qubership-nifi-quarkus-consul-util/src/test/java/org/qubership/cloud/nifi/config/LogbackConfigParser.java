package org.qubership.cloud.nifi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class LogbackConfigParser {
    private static final Logger LOG = LoggerFactory.getLogger(LogbackConfigParser.class);
    private static final String LOGGER_TAG = "logger";
    private final String configFileName;

    public LogbackConfigParser(final String confFileName) {
        this.configFileName = confFileName;
    }

    public Map<String, String> getAllLoggingLevels() throws IOException, ParserConfigurationException {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        //configure to avoid XXE attacks:
        dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbFactory.setXIncludeAware(false);
        dbFactory.setExpandEntityReferences(false);
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Map<String, String> loggingLevels = new HashMap<>();
        try (InputStream in = new BufferedInputStream(new FileInputStream(configFileName))) {
            Document doc = null;
            doc = dBuilder.parse(in);
            doc.getDocumentElement().normalize();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Root element: {}", doc.getDocumentElement().getNodeName());
            }
            NodeList loggersList = doc.getElementsByTagName(LOGGER_TAG);
            for (int cnt = 0; cnt < loggersList.getLength(); cnt++) {
                Node prop = loggersList.item(cnt);
                NamedNodeMap attr = prop.getAttributes();
                loggingLevels.put(attr.getNamedItem("name").getNodeValue(),
                        attr.getNamedItem("level").getNodeValue());
            }
        } catch (SAXException e) {
            throw new RuntimeException(e);
        }
        return loggingLevels;
    }
}

package org.qubership.cloud.nifi.quarkus.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.cloud.nifi.config.xml.BaseXmlConfigValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.IOException;

@ApplicationScoped
public class XmlConfigValidator {
    private static final Logger LOG = LoggerFactory.getLogger(XmlConfigValidator.class);

    @Inject
    private BaseXmlConfigValidator baseXmlConfigValidator;

    /**
     * Default constructor for CDI.
     */
    public XmlConfigValidator() {
        // Default constructor
    }

    /**
     * Default constructor for CDI.
     */
    public void validateProperties() throws IOException, ParserConfigurationException {
        this.baseXmlConfigValidator.validate();
        LOG.info("nifi properties files validated");
    }
}

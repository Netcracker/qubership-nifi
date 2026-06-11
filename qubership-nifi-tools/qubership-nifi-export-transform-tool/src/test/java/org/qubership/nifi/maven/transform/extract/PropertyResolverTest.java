package org.qubership.nifi.maven.transform.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.nifi.maven.transform.config.PropertyMapping;
import org.qubership.nifi.maven.transform.flow.ProcessGroup;
import org.qubership.nifi.maven.transform.flow.ProcessorProperty;
import org.qubership.nifi.maven.transform.flow.Processor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PropertyResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TYPE = "org.qubership.nifi.TestProcessor";

    /** Mock Maven logger. */
    @Mock
    private Log log;

    private PropertyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PropertyResolver(log);
    }

    private ProcessGroup rootGroup() {
        return new ProcessGroup("root", "root-id", List.of(), List.of(), null, false);
    }

    private Processor processorWith(String propertyName, String value) {
        ObjectNode props = MAPPER.createObjectNode();
        props.put(propertyName, value);
        return new Processor("TestProcessor", TYPE, "id", props, rootGroup());
    }

    @Test
    void resolveByExactNameReturnsPropertyWhenPresent() {
        Processor processor = processorWith("SQL Query", "SELECT 1");
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");

        Optional<ProcessorProperty> result = resolver.resolve(processor, mapping);

        assertTrue(result.isPresent());
        assertEquals("SQL Query", result.get().getName());
        assertEquals("SELECT 1", result.get().getValue());
    }

    @Test
    void resolveByExactNameReturnsEmptyAndLogsWarnWhenPropertyAbsent() {
        Processor processor = new Processor("P", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");

        Optional<ProcessorProperty> result = resolver.resolve(processor, mapping);

        assertTrue(result.isEmpty());
        verify(log).warn(argThat((CharSequence msg) -> msg.toString().contains("SQL Query")));
    }

    @Test
    void resolveByRegexReturnsSingleMatchingProperty() {
        Processor processor = processorWith("Script Body", "println 'hi'");
        PropertyMapping mapping = PropertyMapping.of("Script.*", "script.groovy");

        Optional<ProcessorProperty> result = resolver.resolve(processor, mapping);

        assertTrue(result.isPresent());
        assertEquals("Script Body", result.get().getName());
    }

    @Test
    void resolveByRegexReturnsEmptyAndLogsWarnWhenNoMatchFound() {
        Processor processor = processorWith("SQL Query", "SELECT 1");
        PropertyMapping mapping = PropertyMapping.of("Script.*", "script.groovy");

        Optional<ProcessorProperty> result = resolver.resolve(processor, mapping);

        assertTrue(result.isEmpty());
        verify(log).warn(argThat((CharSequence msg) -> msg.toString().contains("Script.*")));
    }

    @Test
    void resolveByRegexReturnsFirstMatchWhenMultiplePropertiesMatch() {
        ObjectNode props = MAPPER.createObjectNode();
        props.put("Script Body", "println 'hi'");
        props.put("Script File", "script.groovy");
        Processor processor = new Processor("P", TYPE, "id", props, rootGroup());
        PropertyMapping mapping = PropertyMapping.of("Script.*", "script.groovy");

        Optional<ProcessorProperty> result = resolver.resolve(processor, mapping);

        assertTrue(result.isPresent());
    }
}

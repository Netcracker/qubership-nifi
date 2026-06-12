package org.qubership.nifi.maven.transform.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginConfigTest {

    private static final String EXECUTE_SQL =
            "org.apache.nifi.processors.standard.ExecuteSQL";
    private static final String REPLACE_TEXT =
            "org.apache.nifi.processors.standard.ReplaceText";

    @Test
    void getProcessorTypesReturnsAllEntries() {
        ProcessorTypeConfig c1 = typeConfig(EXECUTE_SQL);
        ProcessorTypeConfig c2 = typeConfig(REPLACE_TEXT);

        PluginConfig config = new PluginConfig(List.of(c1, c2));

        assertEquals(List.of(c1, c2), config.getProcessorTypes());
    }

    @Test
    void getProcessorTypesIsUnmodifiable() {
        PluginConfig config = new PluginConfig(List.of(typeConfig(EXECUTE_SQL)));

        assertThrows(UnsupportedOperationException.class,
                () -> config.getProcessorTypes().add(typeConfig(REPLACE_TEXT)));
    }

    private ProcessorTypeConfig typeConfig(String fqn) {
        return new ProcessorTypeConfig(fqn,
                List.of(PropertyMapping.of("SQL Query", "query.sql")));
    }
}

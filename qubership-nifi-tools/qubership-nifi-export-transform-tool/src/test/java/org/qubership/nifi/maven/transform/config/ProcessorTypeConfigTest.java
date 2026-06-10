package org.qubership.nifi.maven.transform.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessorTypeConfigTest {

    private static final String TYPE_FQN =
            "org.apache.nifi.processors.standard.ExecuteSQL";

    @Test
    void getProcessorTypeFqnReturnsCorrectValue() {
        ProcessorTypeConfig config = new ProcessorTypeConfig(TYPE_FQN, List.of());

        assertEquals(TYPE_FQN, config.getProcessorTypeFqn());
    }

    @Test
    void getPropertyMappingsReturnsAllMappings() {
        PropertyMapping m1 = PropertyMapping.of("SQL Query", "query.sql");
        PropertyMapping m2 = PropertyMapping.of("Max Rows Per Flow File", "rows.txt");

        ProcessorTypeConfig config = new ProcessorTypeConfig(TYPE_FQN, List.of(m1, m2));

        assertEquals(List.of(m1, m2), config.getPropertyMappings());
    }

    @Test
    void getPropertyMappingsReturnsEmptyListWhenNoMappings() {
        ProcessorTypeConfig config = new ProcessorTypeConfig(TYPE_FQN, List.of());

        assertTrue(config.getPropertyMappings().isEmpty());
    }

    @Test
    void getPropertyMappingsIsUnmodifiable() {
        ProcessorTypeConfig config = new ProcessorTypeConfig(
                TYPE_FQN,
                List.of(PropertyMapping.of("SQL Query", "query.sql")));

        assertThrows(UnsupportedOperationException.class, () -> config.getPropertyMappings().add(
                PropertyMapping.of("Other", "other.txt")));
    }
}

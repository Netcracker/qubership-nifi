package org.qubership.nifi.maven.transform.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.maven.transform.exception.ConfigException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    /** Temporary directory provided by JUnit for each test. */
    @TempDir
    private Path tempDir;

    /**
     * Returns the temporary directory used by this test.
     * @return path to temporary directory
     */
    Path getTempDir() {
        return tempDir;
    }

    private final ConfigLoader loader = new ConfigLoader();

    // -------------------------------------------------------------------------
    // Valid configs
    // -------------------------------------------------------------------------

    @Test
    void loadValidYamlParsesProcessorTypesAndMappings() throws Exception {
        Path config = yaml("""
                processorTypes:
                  - org.apache.nifi.processors.standard.ExecuteSQL:
                      query.sql: SQL Query
                  - org.apache.nifi.processors.standard.ReplaceText:
                      replacement.txt: Replacement Value
                """);

        PluginConfig result = loader.load(config);

        assertEquals(2, result.getProcessorTypes().size());

        ProcessorTypeConfig sql = result.getProcessorTypes().get(0);
        assertEquals("org.apache.nifi.processors.standard.ExecuteSQL", sql.getProcessorTypeFqn());
        assertEquals(1, sql.getPropertyMappings().size());
        assertEquals("SQL Query", sql.getPropertyMappings().get(0).getPropertyNameOrRegex());
        assertEquals("query.sql", sql.getPropertyMappings().get(0).getTargetFilename());
    }

    @Test
    void loadMultiplePropertiesForOneTypeParsesAllMappings() throws Exception {
        Path config = yaml("""
                processorTypes:
                  - org.qubership.SomeProcessor:
                      first.sql: First Property
                      second.groovy: Second Property
                """);

        PluginConfig result = loader.load(config);

        List<PropertyMapping> mappings = result.getProcessorTypes().get(0).getPropertyMappings();
        assertEquals(2, mappings.size());
        assertEquals("first.sql", mappings.get(0).getTargetFilename());
        assertEquals("second.groovy", mappings.get(1).getTargetFilename());
    }

    @Test
    void loadRegexPropertyNameIsRecognizedAsRegex() throws Exception {
        Path config = yaml("""
                processorTypes:
                  - org.qubership.SomeProcessor:
                      query.sql: (SQL Query|db-fetch-sql-query)
                """);

        PluginConfig result = loader.load(config);

        PropertyMapping mapping = result.getProcessorTypes().get(0).getPropertyMappings().get(0);
        assertTrue(mapping.isRegex());
        assertTrue(mapping.matches("SQL Query"));
        assertTrue(mapping.matches("db-fetch-sql-query"));
    }

    @Test
    void loadNoProcessorTypesKeyReturnsEmptyConfig() throws Exception {
        Path config = yaml("someOtherKey: value\n");

        PluginConfig result = loader.load(config);

        assertTrue(result.getProcessorTypes().isEmpty());
    }

    @Test
    void loadEmptyFileReturnsEmptyConfig() throws Exception {
        Path config = yaml("");

        PluginConfig result = loader.load(config);

        assertTrue(result.getProcessorTypes().isEmpty());
    }

    @Test
    void loadEmptyProcessorTypesListReturnsEmptyConfig() throws Exception {
        Path config = yaml("processorTypes: []\n");

        PluginConfig result = loader.load(config);

        assertTrue(result.getProcessorTypes().isEmpty());
    }

    // -------------------------------------------------------------------------
    // File validation errors
    // -------------------------------------------------------------------------

    @Test
    void loadNonExistentFileThrowsConfigException() {
        Path missing = tempDir.resolve("missing.yaml");

        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(missing));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void loadDirectoryThrowsConfigException() {
        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(tempDir));
        assertTrue(e.getMessage().contains("not found"));
    }

    // -------------------------------------------------------------------------
    // Structural errors
    // -------------------------------------------------------------------------

    @Test
    void loadProcessorTypesIsNotListThrowsConfigException() throws Exception {
        Path config = yaml("processorTypes: not-a-list\n");

        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(config));
        assertTrue(e.getMessage().contains("must be a list"));
    }

    @Test
    void loadProcessorTypeEntryIsNotMapThrowsConfigException() throws Exception {
        Path config = yaml("""
                processorTypes:
                  - just-a-string
                """);

        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(config));
        assertTrue(e.getMessage().contains("must be a map"));
    }

    @Test
    void loadProcessorTypeEntryHasMultipleKeysThrowsConfigException() throws Exception {
        Path config = yaml("""
                processorTypes:
                  - TypeA: {Prop: file.txt}
                    TypeB: {Prop: file.txt}
                """);

        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(config));
        assertTrue(e.getMessage().contains("exactly one key"));
    }

    @Test
    void loadPropertyMappingsIsNotMapThrowsConfigException() throws Exception {
        Path config = yaml("""
                processorTypes:
                  - org.qubership.SomeProcessor: not-a-map
                """);

        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(config));
        assertTrue(e.getMessage().contains("must be a map"));
    }

    @Test
    void loadEmptyPropertyNameThrowsConfigException() throws Exception {
        Path config = yaml("""
                processorTypes:
                  - org.qubership.SomeProcessor:
                      query.sql:
                """);

        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(config));
        assertTrue(e.getMessage().contains("must not be empty"));
    }

    @Test
    void loadNoPropertyMappingsThrowsConfigException() throws Exception {
        Path config = yaml("""
                processorTypes:
                  - org.qubership.SomeProcessor: {}
                """);

        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(config));
        assertTrue(e.getMessage().contains("no property mappings"));
    }

    @Test
    void loadTargetFilenameWithDotDotSegmentThrowsConfigException() throws Exception {
        Path config = yaml("""
                processorTypes:
                  - org.qubership.SomeProcessor:
                      "../../../../etc/passwd": SQL Query
                """);

        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(config));
        assertTrue(e.getMessage().contains("'..'"));
    }

    @Test
    void loadInvalidRegexInPropertyNameThrowsConfigException() throws Exception {
        Path config = yaml("""
                processorTypes:
                  - org.qubership.SomeProcessor:
                      file.txt: '[invalid'
                """);

        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(config));
        assertTrue(e.getMessage().contains("Invalid regex"));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private Path yaml(String content) throws IOException {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, content);
        return file;
    }
}

package org.qubership.nifi.maven.transform.config;

import org.qubership.nifi.maven.transform.exception.ConfigException;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

/**
 * Loads the plugin configuration YAML file and builds the PluginConfig model.
 *
 */
public class ConfigLoader {

    /**
     * Loads and parses the plugin configuration from the given path.
     *
     * @param configPath path to the YAML configuration file
     * @return parsed PluginConfig
     * @throws ConfigException if the file is not found, not readable,
     *                         contains invalid YAML, or has an incorrect structure
     */
    public PluginConfig load(Path configPath) throws ConfigException {
        validatePath(configPath);
        return parse(configPath);
    }

    private void validatePath(Path configPath) throws ConfigException {
        if (!Files.isRegularFile(configPath)) {
            throw new ConfigException(
                    "Config file not found or is not a regular file: " +
                            configPath.toAbsolutePath());
        }
        if (!Files.isReadable(configPath)) {
            throw new ConfigException(
                    "Config file is not readable (check permissions): " +
                            configPath.toAbsolutePath());
        }
    }

    private PluginConfig parse(Path configPath) throws ConfigException {
        Yaml yaml = new Yaml();

        try (InputStream is = Files.newInputStream(configPath)) {
            Map<String, Object> root = yaml.load(is);

            if (root == null || !root.containsKey("processorTypes")) {
                return new PluginConfig(Collections.emptyList());
            }

            Object processorTypesObj = root.get("processorTypes");
            if (!(processorTypesObj instanceof List<?> processorTypesList)) {
                throw new ConfigException(
                        "'processorTypes' must be a list in: " + configPath.toAbsolutePath());
            }

            return new PluginConfig(parseProcessorTypes(processorTypesList, configPath));

        } catch (ConfigException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ConfigException(e.getMessage(), e.getCause() != null ? e.getCause() : e);
        } catch (IOException e) {
            throw new ConfigException(
                    "Failed to read config file " + configPath.toAbsolutePath() +
                            ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses the list of processor type entries from the YAML structure.
     * Each entry is a single-key map: processor type FQN → property mappings map.
     */
    private List<ProcessorTypeConfig> parseProcessorTypes(List<?> processorTypesList,
                                                          Path configPath) throws ConfigException {
        List<ProcessorTypeConfig> result = new ArrayList<>();

        for (Object item : processorTypesList) {
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new ConfigException(
                        "Each entry in 'processorTypes' must be a map with one key " +
                                "(processor type FQN) in: " + configPath.toAbsolutePath());
            }
            if (itemMap.size() != 1) {
                throw new ConfigException(
                        "Each entry in 'processorTypes' must have exactly one key " +
                                "(processor type FQN), but found " + itemMap.size() +
                                " keys: " + itemMap.keySet() +
                                " in: " + configPath.toAbsolutePath());
            }

            Map.Entry<?, ?> entry = itemMap.entrySet().iterator().next();
            String typeFqn = entry.getKey().toString();
            Object mappingsObj = entry.getValue();

            if (!(mappingsObj instanceof Map<?, ?> mappingsMap)) {
                throw new ConfigException(
                        "Property mappings for type '" + typeFqn +
                                "' must be a map (propertyName: targetFilename) " +
                                "in: " + configPath.toAbsolutePath());
            }

            result.add(new ProcessorTypeConfig(typeFqn, parseMappings(typeFqn, mappingsMap, configPath)));
        }

        return result;
    }

    /**
     * Parses property mappings for a single processor type.
     * Each entry: key = property name or regex, value = target filename.
     */
    private List<PropertyMapping> parseMappings(String typeFqn, Map<?, ?> mappingsMap,
                                                Path configPath) throws ConfigException {
        List<PropertyMapping> mappings = new ArrayList<>();

        for (Map.Entry<?, ?> entry : mappingsMap.entrySet()) {
            String propertyNameOrRegex = entry.getKey().toString();
            Object filenameObj = entry.getValue();

            if (filenameObj == null || filenameObj.toString().isBlank()) {
                throw new ConfigException(
                        "Target filename for property '" + propertyNameOrRegex +
                                "' in processor type '" + typeFqn +
                                "' must not be empty in: " + configPath.toAbsolutePath());
            }

            String targetFilename = filenameObj.toString().trim();

            try {
                mappings.add(PropertyMapping.of(propertyNameOrRegex, targetFilename));
            } catch (PatternSyntaxException e) {
                throw new ConfigException(
                        "Invalid regex pattern '" + propertyNameOrRegex +
                                "' for processor type '" + typeFqn +
                                "': " + e.getMessage(), e);
            }
        }

        if (mappings.isEmpty()) {
            throw new ConfigException(
                    "Processor type '" + typeFqn +
                            "' has no property mappings defined in: " + configPath.toAbsolutePath());
        }

        return mappings;
    }
}

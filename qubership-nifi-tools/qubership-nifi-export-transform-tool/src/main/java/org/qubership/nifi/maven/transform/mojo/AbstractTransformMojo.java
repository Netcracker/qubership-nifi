package org.qubership.nifi.maven.transform.mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.qubership.nifi.maven.transform.config.ConfigLoader;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.exception.ConfigException;

import java.io.File;

/**
 * Base class for ExtractMojo and BuildMojo.
 * Holds shared parameters and config loading logic.
 */
public abstract class AbstractTransformMojo extends AbstractMojo {

    /**
     * Path to the processor types configuration YAML file.
     * Required parameter.
     */
    @Parameter(property = "config", required = true)
    protected File configFile;

    /**
     * Path to the directory containing exported NiFi flow JSON files.
     * Defaults to the "nifi" subdirectory of the current working directory.
     */
    @Parameter(property = "export-dir", defaultValue = "nifi")
    protected File exportDir;

    /**
     * Loads and parses the plugin configuration.
     *
     * @return parsed PluginConfig
     * @throws MojoExecutionException if a config file is not found, not readable,
     *                                or contains invalid YAML
     */
    protected PluginConfig loadConfig() throws MojoExecutionException {
        getLog().info("Loading config from: " + configFile.getAbsolutePath());
        try {
            return new ConfigLoader().load(configFile.toPath());
        } catch (ConfigException e) {
            throw new MojoExecutionException("Failed to load config: " + e.getMessage(), e);
        }
    }
}

package org.qubership.nifi.maven.transform.mojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.exception.ExtractException;
import org.qubership.nifi.maven.transform.extract.ExtractService;
import org.qubership.nifi.maven.transform.extract.PropertyResolver;
import org.qubership.nifi.maven.transform.extract.ReferenceBuilder;
import org.qubership.nifi.maven.transform.flow.FlowReader;
import org.qubership.nifi.maven.transform.flow.FlowScanner;
import org.qubership.nifi.maven.transform.flow.FlowValidator;
import org.qubership.nifi.maven.transform.flow.FlowWriter;
import org.qubership.nifi.maven.transform.io.FileSystemService;

import java.io.IOException;

/**
 * Maven Mojo for the Extract operation.
 *
 * Extracts processor property values from exported NiFi flow JSON files
 * into separate files and replaces the values with file references of the form @path.
 */
@Mojo(name = "extract", defaultPhase = LifecyclePhase.NONE)
public class ExtractMojo extends AbstractTransformMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        PluginConfig config = loadConfig();

        if (config.getProcessorTypes().isEmpty()) {
            getLog().warn("No processor types defined in config, nothing to extract.");
            return;
        }

        ExtractService service = new ExtractService(
                getLog(),
                new FlowReader(new ObjectMapper()),
                new FlowWriter(new ObjectMapper()),
                new FlowScanner(),
                new FlowValidator(),
                new FileSystemService(),
                new PropertyResolver(getLog()),
                new ReferenceBuilder());

        try {
            service.extract(config, exportDir.toPath());
        } catch (ExtractException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("Unexpected I/O error during Extract", e);
        }
    }
}

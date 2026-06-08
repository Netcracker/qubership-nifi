package org.qubership.nifi.maven.transform.mojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.qubership.nifi.maven.transform.build.BuildService;
import org.qubership.nifi.maven.transform.build.CleanupService;
import org.qubership.nifi.maven.transform.build.ReferenceResolver;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.exception.BuildException;
import org.qubership.nifi.maven.transform.flow.FlowReader;
import org.qubership.nifi.maven.transform.flow.FlowWriter;
import org.qubership.nifi.maven.transform.extract.PropertyResolver;
import org.qubership.nifi.maven.transform.io.FileSystemService;

import java.io.IOException;

/**
 * Maven Mojo for the Build operation.
 *
 * Reads extracted configuration files and writes their contents back
 * into the processor properties of the exported NiFi flow JSON files.
 * Replaces file references of the form @path with the actual file content.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class BuildMojo extends AbstractTransformMojo {

    /**
     * Whether to delete extracted configuration files and their directories
     * after a successful Build operation.
     * Defaults to false — files are kept after Build.
     */
    @Parameter(property = "delete", defaultValue = "false")
    private boolean delete;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        PluginConfig config = loadConfig();

        if (config.getProcessorTypes().isEmpty()) {
            getLog().warn("No processor types defined in config, nothing to build.");
            return;
        }

        BuildService service = new BuildService(
                getLog(),
                new FlowReader(new ObjectMapper()),
                new FlowWriter(new ObjectMapper()),
                new FileSystemService(),
                new PropertyResolver(getLog()),
                new ReferenceResolver(),
                new CleanupService(new FileSystemService(), getLog()));

        try {
            service.build(config, exportDir.toPath(), delete);
        } catch (BuildException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("Unexpected I/O error during Build", e);
        }
    }
}

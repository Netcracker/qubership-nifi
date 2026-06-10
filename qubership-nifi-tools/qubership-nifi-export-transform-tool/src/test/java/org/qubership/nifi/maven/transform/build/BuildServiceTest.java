package org.qubership.nifi.maven.transform.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.config.ProcessorTypeConfig;
import org.qubership.nifi.maven.transform.config.PropertyMapping;
import org.qubership.nifi.maven.transform.exception.BuildException;
import org.qubership.nifi.maven.transform.extract.PropertyResolver;
import org.qubership.nifi.maven.transform.flow.FlowFile;
import org.qubership.nifi.maven.transform.flow.FlowReader;
import org.qubership.nifi.maven.transform.flow.FlowWriter;
import org.qubership.nifi.maven.transform.flow.ProcessGroup;
import org.qubership.nifi.maven.transform.flow.Processor;
import org.qubership.nifi.maven.transform.flow.ProcessorProperty;
import org.qubership.nifi.maven.transform.io.FileSystemService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuildServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TYPE = "org.qubership.nifi.TestProcessor";

    @Mock Log log;
    @Mock FlowReader flowReader;
    @Mock FlowWriter flowWriter;
    @Mock FileSystemService fileSystem;
    @Mock PropertyResolver propertyResolver;
    @Mock ReferenceResolver referenceResolver;
    @Mock CleanupService cleanupService;

    BuildService service;

    @BeforeEach
    void setUp() {
        service = new BuildService(log, flowReader, flowWriter, fileSystem,
                propertyResolver, referenceResolver, cleanupService);
    }

    private PluginConfig config(PropertyMapping... mappings) {
        return new PluginConfig(List.of(new ProcessorTypeConfig(TYPE, List.of(mappings))));
    }

    private ProcessGroup rootGroup() {
        return new ProcessGroup("root", "root-id", List.of(), List.of(), null, false);
    }

    private FlowFile flowWith(Path path, Processor processor) {
        return new FlowFile(path, MAPPER.createObjectNode(), rootGroup(),
                Map.of(TYPE, List.of(processor)));
    }

    private ProcessorProperty referenceProperty(String name) {
        ObjectNode props = MAPPER.createObjectNode();
        props.put(name, "@flowConf_flow/MyProcessor/query.sql");
        return new ProcessorProperty(name, props);
    }

    private ProcessorProperty emptyProperty(String name) {
        ObjectNode props = MAPPER.createObjectNode();
        props.putNull(name);
        return new ProcessorProperty(name, props);
    }

    private ProcessorProperty inlineProperty(String name, String value) {
        ObjectNode props = MAPPER.createObjectNode();
        props.put(name, value);
        return new ProcessorProperty(name, props);
    }

    @Test
    void buildDoesNothingWhenNoFlowFilesFound() throws BuildException, IOException {
        Path exportDir = Path.of("exports");
        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of());

        service.build(config(PropertyMapping.of("SQL Query", "query.sql")), exportDir, false);

        verify(flowReader, never()).read(any());
        verify(flowWriter, never()).write(any());
    }

    @Test
    void buildRestoresPropertyValueFromReferencedFile() throws BuildException, IOException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        ObjectNode props = MAPPER.createObjectNode();
        props.put("SQL Query", "@flowConf_flow/MyProcessor/query.sql");
        Processor processor = new Processor("MyProcessor", TYPE, "id", props, rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty property = new ProcessorProperty("SQL Query", props);
        Path targetFile = Path.of("exports", "flowConf_flow", "MyProcessor", "query.sql");

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(propertyResolver.resolve(processor, mapping)).thenReturn(Optional.of(property));
        when(referenceResolver.resolve(flow, processor, property)).thenReturn(targetFile);
        when(fileSystem.readText(targetFile)).thenReturn("SELECT 1");

        service.build(config, exportDir, false);

        assertEquals("SELECT 1", property.getValue());
        verify(flowWriter).write(flow);
    }

    @Test
    void buildSkipsWhenPropertyResolverReturnsEmpty() throws BuildException, IOException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        Processor processor = new Processor("P", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(propertyResolver.resolve(processor, mapping)).thenReturn(Optional.empty());

        service.build(config, exportDir, false);

        verify(fileSystem, never()).readText(any());
        verify(flowWriter, never()).write(any());
    }

    @Test
    void buildSkipsEmptyProperty() throws BuildException, IOException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        Processor processor = new Processor("P", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty property = emptyProperty("SQL Query");

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(propertyResolver.resolve(processor, mapping)).thenReturn(Optional.of(property));

        service.build(config, exportDir, false);

        verify(fileSystem, never()).readText(any());
        verify(flowWriter, never()).write(any());
    }

    @Test
    void buildCollectsInlineValueErrorAndSkipsFlowWriter() throws IOException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        Processor processor = new Processor("P", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty property = inlineProperty("SQL Query", "SELECT 1");

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(propertyResolver.resolve(processor, mapping)).thenReturn(Optional.of(property));

        assertThrows(BuildException.class, () -> service.build(config, exportDir, false));
        verify(flowWriter, never()).write(any());
    }

    @Test
    void buildCollectsReferenceFileErrorAndSkipsFlowWriter() throws IOException, BuildException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        Processor processor = new Processor("P", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty property = referenceProperty("SQL Query");

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(propertyResolver.resolve(processor, mapping)).thenReturn(Optional.of(property));
        when(referenceResolver.resolve(flow, processor, property))
                .thenThrow(new BuildException("Referenced file does not exist"));

        assertThrows(BuildException.class, () -> service.build(config, exportDir, false));
        verify(flowWriter, never()).write(any());
    }

    @Test
    void buildRunsCleanupAfterSuccessWithDeleteTrue() throws BuildException, IOException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        ObjectNode props = MAPPER.createObjectNode();
        props.put("SQL Query", "@flowConf_flow/MyProcessor/query.sql");
        Processor processor = new Processor("P", TYPE, "id", props, rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty property = new ProcessorProperty("SQL Query", props);
        Path targetFile = Path.of("exports", "flowConf_flow", "P", "query.sql");

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(propertyResolver.resolve(processor, mapping)).thenReturn(Optional.of(property));
        when(referenceResolver.resolve(flow, processor, property)).thenReturn(targetFile);
        when(fileSystem.readText(targetFile)).thenReturn("SELECT 1");

        service.build(config, exportDir, true);

        verify(cleanupService).cleanup(exportDir);
    }

    @Test
    void buildSkipsCleanupWithDeleteFalse() throws BuildException, IOException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        ObjectNode props = MAPPER.createObjectNode();
        props.put("SQL Query", "@flowConf_flow/MyProcessor/query.sql");
        Processor processor = new Processor("P", TYPE, "id", props, rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty property = new ProcessorProperty("SQL Query", props);
        Path targetFile = Path.of("exports", "flowConf_flow", "P", "query.sql");

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(propertyResolver.resolve(processor, mapping)).thenReturn(Optional.of(property));
        when(referenceResolver.resolve(flow, processor, property)).thenReturn(targetFile);
        when(fileSystem.readText(targetFile)).thenReturn("SELECT 1");

        service.build(config, exportDir, false);

        verify(cleanupService, never()).cleanup(any());
    }

    @Test
    void buildSkipsCleanupWhenErrorsPresent() throws IOException, BuildException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        Processor processor = new Processor("P", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty property = referenceProperty("SQL Query");

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(propertyResolver.resolve(processor, mapping)).thenReturn(Optional.of(property));
        when(referenceResolver.resolve(flow, processor, property))
                .thenThrow(new BuildException("file missing"));

        assertThrows(BuildException.class, () -> service.build(config, exportDir, true));
        verify(cleanupService, never()).cleanup(any());
    }

    @Test
    void buildContinuesProcessingNextFlowAfterErrorAndThrows() throws IOException, BuildException {
        Path exportDir = Path.of("exports");
        Path flowPath1 = Path.of("exports", "flow1.json");
        Path flowPath2 = Path.of("exports", "flow2.json");
        ObjectNode props2 = MAPPER.createObjectNode();
        props2.put("SQL Query", "@flowConf_flow2/P2/query.sql");
        Processor p1 = new Processor("P1", TYPE, "id1", MAPPER.createObjectNode(), rootGroup());
        Processor p2 = new Processor("P2", TYPE, "id2", props2, rootGroup());
        FlowFile flow1 = flowWith(flowPath1, p1);
        FlowFile flow2 = flowWith(flowPath2, p2);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty refProp1 = referenceProperty("SQL Query");
        ProcessorProperty refProp2 = new ProcessorProperty("SQL Query", props2);
        Path targetFile2 = Path.of("exports", "flowConf_flow2", "P2", "query.sql");

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath1, flowPath2));
        when(flowReader.read(flowPath1)).thenReturn(flow1);
        when(flowReader.read(flowPath2)).thenReturn(flow2);
        when(propertyResolver.resolve(eq(p1), any())).thenReturn(Optional.of(refProp1));
        when(propertyResolver.resolve(eq(p2), any())).thenReturn(Optional.of(refProp2));
        when(referenceResolver.resolve(eq(flow1), eq(p1), any()))
                .thenThrow(new BuildException("file missing"));
        when(referenceResolver.resolve(eq(flow2), eq(p2), any())).thenReturn(targetFile2);
        when(fileSystem.readText(targetFile2)).thenReturn("SELECT 1");

        assertThrows(BuildException.class, () -> service.build(config, exportDir, false));
        verify(flowWriter, never()).write(flow1);
        verify(flowWriter).write(flow2);
    }
}

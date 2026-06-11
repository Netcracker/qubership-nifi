package org.qubership.nifi.maven.transform.extract;

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
import org.qubership.nifi.maven.transform.exception.ExtractException;
import org.qubership.nifi.maven.transform.flow.FlowFile;
import org.qubership.nifi.maven.transform.flow.FlowReader;
import org.qubership.nifi.maven.transform.flow.FlowValidator;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExtractServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TYPE = "org.qubership.nifi.TestProcessor";

    /** Mock Maven logger. */
    @Mock
    private Log log;

    @Mock
    private FlowReader flowReader;
    @Mock
    private FlowWriter flowWriter;
    @Mock
    private FlowValidator flowValidator;
    @Mock
    private FileSystemService fileSystem;
    @Mock
    private PropertyResolver propertyResolver;
    @Mock
    private ReferenceBuilder referenceBuilder;

    private ExtractService service;

    @BeforeEach
    void setUp() {
        service = new ExtractService(log, flowReader, flowWriter, flowValidator,
                fileSystem, propertyResolver, referenceBuilder);
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

    @Test
    void extractDoesNothingWhenNoFlowFilesFound() throws ExtractException, IOException {
        Path exportDir = Path.of("exports");
        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of());

        service.extract(config(PropertyMapping.of("SQL Query", "query.sql")), exportDir);

        verify(flowReader, never()).read(any());
        verify(flowWriter, never()).write(any());
    }

    @Test
    void extractThrowsWhenValidationErrorsExist() throws IOException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        Processor processor = new Processor("P", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PluginConfig config = config(PropertyMapping.of("SQL Query", "query.sql"));

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(flowValidator.validate(flow, config)).thenReturn(List.of("Duplicate path: P"));

        assertThrows(ExtractException.class, () -> service.extract(config, exportDir));
        verify(flowWriter, never()).write(any());
    }

    @Test
    void extractProcessesFlowAndWritesExtractedProperty() throws ExtractException, IOException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        ObjectNode props = MAPPER.createObjectNode();
        props.put("SQL Query", "SELECT 1");
        Processor processor = new Processor("MyProcessor", TYPE, "id", props, rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty property = new ProcessorProperty("SQL Query", props);
        Path targetFile = Path.of("exports", "flowConf_flow", "MyProcessor", "query.sql");

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(flowValidator.validate(flow, config)).thenReturn(List.of());
        when(propertyResolver.resolve(processor, mapping)).thenReturn(Optional.of(property));
        when(referenceBuilder.buildAbsoluteFilePath(flow, processor, "query.sql"))
                .thenReturn(targetFile);
        when(referenceBuilder.buildReference(flow, processor, "query.sql"))
                .thenReturn("@flowConf_flow/MyProcessor/query.sql");

        service.extract(config, exportDir);

        verify(fileSystem).createDirectories(targetFile.getParent());
        verify(fileSystem).writeText(targetFile, "SELECT 1");
        verify(flowWriter).write(flow);
        assertEquals("@flowConf_flow/MyProcessor/query.sql", property.getValue());
    }

    @Test
    void extractSkipsPropertyAlreadyAReference() throws ExtractException, IOException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        ObjectNode props = MAPPER.createObjectNode();
        props.put("SQL Query", "@flowConf_flow/MyProcessor/query.sql");
        Processor processor = new Processor("MyProcessor", TYPE, "id", props, rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty property = new ProcessorProperty("SQL Query", props);

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(flowValidator.validate(flow, config)).thenReturn(List.of());
        when(propertyResolver.resolve(processor, mapping)).thenReturn(Optional.of(property));

        service.extract(config, exportDir);

        verify(fileSystem, never()).writeText(any(), any());
        verify(flowWriter, never()).write(any());
    }

    @Test
    void extractSkipsEmptyProperty() throws ExtractException, IOException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        ObjectNode props = MAPPER.createObjectNode();
        props.putNull("SQL Query");
        Processor processor = new Processor("MyProcessor", TYPE, "id", props, rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty property = new ProcessorProperty("SQL Query", props);

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(flowValidator.validate(flow, config)).thenReturn(List.of());
        when(propertyResolver.resolve(processor, mapping)).thenReturn(Optional.of(property));

        service.extract(config, exportDir);

        verify(fileSystem, never()).writeText(any(), any());
        verify(flowWriter, never()).write(any());
    }

    @Test
    void extractSkipsWhenPropertyResolverReturnsEmpty() throws ExtractException, IOException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        Processor processor = new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
        FlowFile flow = flowWith(flowPath, processor);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(flowValidator.validate(flow, config)).thenReturn(List.of());
        when(propertyResolver.resolve(processor, mapping)).thenReturn(Optional.empty());

        service.extract(config, exportDir);

        verify(fileSystem, never()).writeText(any(), any());
        verify(flowWriter, never()).write(any());
    }

    @Test
    void extractCleansUpWrittenFilesWhenIOExceptionOccursMidFlow() throws IOException {
        Path exportDir = Path.of("exports");
        Path flowPath = Path.of("exports", "flow.json");
        ObjectNode props1 = MAPPER.createObjectNode();
        props1.put("SQL Query", "SELECT 1");
        ObjectNode props2 = MAPPER.createObjectNode();
        props2.put("SQL Query", "SELECT 2");
        Processor p1 = new Processor("P1", TYPE, "id1", props1, rootGroup());
        Processor p2 = new Processor("P2", TYPE, "id2", props2, rootGroup());
        FlowFile flow = new FlowFile(flowPath, MAPPER.createObjectNode(), rootGroup(),
                Map.of(TYPE, List.of(p1, p2)));
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty prop1 = new ProcessorProperty("SQL Query", props1);
        ProcessorProperty prop2 = new ProcessorProperty("SQL Query", props2);
        Path targetFile1 = Path.of("exports", "flowConf_flow", "P1", "query.sql");
        Path targetFile2 = Path.of("exports", "flowConf_flow", "P2", "query.sql");

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath));
        when(flowReader.read(flowPath)).thenReturn(flow);
        when(flowValidator.validate(flow, config)).thenReturn(List.of());
        when(propertyResolver.resolve(eq(p1), any())).thenReturn(Optional.of(prop1));
        when(propertyResolver.resolve(eq(p2), any())).thenReturn(Optional.of(prop2));
        when(referenceBuilder.buildAbsoluteFilePath(flow, p1, "query.sql")).thenReturn(targetFile1);
        when(referenceBuilder.buildAbsoluteFilePath(flow, p2, "query.sql")).thenReturn(targetFile2);
        when(referenceBuilder.buildReference(eq(flow), eq(p1), any())).thenReturn("@ref1");
        when(referenceBuilder.buildReference(eq(flow), eq(p2), any())).thenReturn("@ref2");
        doNothing().when(fileSystem).writeText(eq(targetFile1), any());
        doThrow(new IOException("disk full")).when(fileSystem).writeText(eq(targetFile2), any());

        assertThrows(IOException.class, () -> service.extract(config, exportDir));
        verify(fileSystem).deleteIfExists(targetFile1);
        verify(flowWriter, never()).write(any());
    }

    @Test
    void extractContinuesProcessingNextFlowAfterValidationErrorsAndThrows() throws IOException {
        Path exportDir = Path.of("exports");
        Path flowPath1 = Path.of("exports", "flow1.json");
        Path flowPath2 = Path.of("exports", "flow2.json");
        Processor p1 = new Processor("P1", TYPE, "id1", MAPPER.createObjectNode(), rootGroup());
        ObjectNode props2 = MAPPER.createObjectNode();
        props2.put("SQL Query", "SELECT 2");
        Processor p2 = new Processor("P2", TYPE, "id2", props2, rootGroup());
        FlowFile flow1 = flowWith(flowPath1, p1);
        FlowFile flow2 = flowWith(flowPath2, p2);
        PropertyMapping mapping = PropertyMapping.of("SQL Query", "query.sql");
        PluginConfig config = config(mapping);
        ProcessorProperty prop2 = new ProcessorProperty("SQL Query", props2);
        Path targetFile2 = Path.of("exports", "flowConf_flow2", "P2", "query.sql");

        when(flowReader.findFlowPaths(exportDir)).thenReturn(List.of(flowPath1, flowPath2));
        when(flowReader.read(flowPath1)).thenReturn(flow1);
        when(flowReader.read(flowPath2)).thenReturn(flow2);
        when(flowValidator.validate(eq(flow1), any())).thenReturn(List.of("Duplicate path: P1"));
        when(flowValidator.validate(eq(flow2), any())).thenReturn(List.of());
        when(propertyResolver.resolve(eq(p2), any())).thenReturn(Optional.of(prop2));
        when(referenceBuilder.buildAbsoluteFilePath(flow2, p2, "query.sql")).thenReturn(targetFile2);
        when(referenceBuilder.buildReference(flow2, p2, "query.sql"))
                .thenReturn("@flowConf_flow2/P2/query.sql");

        assertThrows(ExtractException.class, () -> service.extract(config, exportDir));
        verify(flowWriter, never()).write(flow1);
        verify(flowWriter).write(flow2);
    }
}

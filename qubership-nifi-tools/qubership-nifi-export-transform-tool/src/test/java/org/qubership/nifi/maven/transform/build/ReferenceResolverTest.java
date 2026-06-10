package org.qubership.nifi.maven.transform.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.maven.transform.exception.BuildException;
import org.qubership.nifi.maven.transform.flow.FlowFile;
import org.qubership.nifi.maven.transform.flow.ProcessGroup;
import org.qubership.nifi.maven.transform.flow.Processor;
import org.qubership.nifi.maven.transform.flow.ProcessorProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TYPE = "org.qubership.nifi.TestProcessor";

    @TempDir
    Path tempDir;

    ReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ReferenceResolver();
    }

    private ProcessGroup rootGroup() {
        return new ProcessGroup("root", "root-id", List.of(), List.of(), null, false);
    }

    private FlowFile flowFile() {
        return new FlowFile(tempDir.resolve("flow.json"), MAPPER.createObjectNode(),
                rootGroup(), Map.of());
    }

    private Processor processor() {
        return new Processor("MyProcessor", TYPE, "id", MAPPER.createObjectNode(), rootGroup());
    }

    private ProcessorProperty referenceProperty(String referencePath) {
        ObjectNode props = MAPPER.createObjectNode();
        props.put("SQL Query", "@" + referencePath);
        return new ProcessorProperty("SQL Query", props);
    }

    private ProcessorProperty inlineProperty(String value) {
        ObjectNode props = MAPPER.createObjectNode();
        props.put("SQL Query", value);
        return new ProcessorProperty("SQL Query", props);
    }

    @Test
    void resolveReturnsAbsolutePathWhenReferencedFileExists() throws IOException, BuildException {
        Path referencedFile = tempDir.resolve("flowConf_flow")
                .resolve("MyProcessor").resolve("query.sql");
        Files.createDirectories(referencedFile.getParent());
        Files.writeString(referencedFile, "SELECT 1");

        Path result = resolver.resolve(flowFile(), processor(),
                referenceProperty("flowConf_flow/MyProcessor/query.sql"));

        assertEquals(referencedFile, result);
    }

    @Test
    void resolveThrowsBuildExceptionWhenReferencedFileDoesNotExist() {
        ProcessorProperty property = referenceProperty("flowConf_flow/MyProcessor/query.sql");

        assertThrows(BuildException.class,
                () -> resolver.resolve(flowFile(), processor(), property));
    }

    @Test
    void checkConflictDoesNothingWhenNoExtractedFileExists() {
        assertDoesNotThrow(() ->
                resolver.checkConflict(flowFile(), processor(), inlineProperty("SELECT 1"), "query.sql"));
    }

    @Test
    void checkConflictThrowsBuildExceptionWhenExtractedFileAlreadyExists()
            throws IOException {
        Path extractedFile = tempDir.resolve("flowConf_flow")
                .resolve("MyProcessor").resolve("query.sql");
        Files.createDirectories(extractedFile.getParent());
        Files.writeString(extractedFile, "SELECT 1");

        assertThrows(BuildException.class, () ->
                resolver.checkConflict(flowFile(), processor(), inlineProperty("SELECT 1"), "query.sql"));
    }

    @Test
    void resolveThrowsBuildExceptionWhenReferencePathEscapesBaseDirectory() {
        ProcessorProperty property = referenceProperty("../../outside/file.txt");

        BuildException e = assertThrows(BuildException.class,
                () -> resolver.resolve(flowFile(), processor(), property));
        assertTrue(e.getMessage().contains("escapes the export directory"));
    }

    @Test
    void checkConflictThrowsBuildExceptionForProcessorInNestedGroup() throws IOException {
        ProcessGroup root = rootGroup();
        ProcessGroup group = new ProcessGroup("Extract", "g-id", List.of(), List.of(), root, false);
        Processor nestedProcessor = new Processor("MyProcessor", TYPE, "id",
                MAPPER.createObjectNode(), group);

        Path extractedFile = tempDir.resolve("flowConf_flow")
                .resolve("Extract").resolve("MyProcessor").resolve("query.sql");
        Files.createDirectories(extractedFile.getParent());
        Files.writeString(extractedFile, "SELECT 1");

        assertThrows(BuildException.class, () ->
                resolver.checkConflict(flowFile(), nestedProcessor,
                        inlineProperty("SELECT 1"), "query.sql"));
    }
}

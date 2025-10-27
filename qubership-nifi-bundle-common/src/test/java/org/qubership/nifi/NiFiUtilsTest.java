package org.qubership.nifi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class NiFiUtilsTest {
    private TestRunner tr;
    @BeforeEach
    public void setUp() {
        tr = TestRunners.newTestRunner(TestProcessor.class);
    }

    private static FlowFile createFlowFile(ProcessSession session, String content) {
        return createFlowFile(session, content, null);
    }

    private static FlowFile createFlowFile(ProcessSession session, String content, Map<String, String> attributes) {
        FlowFile ff = session.create();
        if (attributes != null) {
            ff = session.putAllAttributes(ff, attributes);
        }
        if (StringUtils.isNotEmpty(content)) {
            session.write(ff, new OutputStreamCallback() {
                @Override
                public void process(OutputStream outputStream) throws IOException {
                    outputStream.write(content.getBytes(StandardCharsets.UTF_8));
                }
            });
        }
        return ff;
    }

    @Test
    public void testReadJsonNodeFromFlowFile() {
        ProcessSession session = tr.getProcessSessionFactory().createSession();
        FlowFile ff = createFlowFile(session, "{\"a\":\"1\"}");
        JsonNode json = NiFiUtils.readJsonNodeFromFlowFile(session, ff);
        Assertions.assertNotNull(json);
        Assertions.assertEquals("1", json.get("a").asText());
    }

    private static final String TEST_CONTAINER_CONTENT = "{\"name\":\"SomeName\","
            + "\"description\":\"SomeDescription\",\"enabled\":true}";

    @Test
    public void testReadJsonNodeFromFlowFileWithTargetClass() {
        ProcessSession session = tr.getProcessSessionFactory().createSession();
        FlowFile ff = createFlowFile(session, TEST_CONTAINER_CONTENT);
        TestContainer container = NiFiUtils.readJsonNodeFromFlowFile(session, ff, TestContainer.class);
        Assertions.assertNotNull(container);
        Assertions.assertEquals("SomeName", container.getName());
        Assertions.assertTrue(container.isEnabled());
    }

    @Test
    public void testReadJsonNodeFromFlowFileWithTypeReference() {
        ProcessSession session = tr.getProcessSessionFactory().createSession();
        FlowFile ff = createFlowFile(session, TEST_CONTAINER_CONTENT);
        TypeReference<TestContainer> testTypeReference = new TypeReference<TestContainer>() {
            @Override
            public Type getType() {
                return super.getType();
            }
        };
        TestContainer container = NiFiUtils.readJsonNodeFromFlowFile(session, ff, testTypeReference);
        Assertions.assertNotNull(container);
        Assertions.assertEquals("SomeName", container.getName());
        Assertions.assertTrue(container.isEnabled());
    }

    @Test
    public void testExtractContent() {
        ProcessSession session = tr.getProcessSessionFactory().createSession();
        FlowFile ff = createFlowFile(session, TEST_CONTAINER_CONTENT);
        String content = NiFiUtils.extractContent(session, ff);
        Assertions.assertNotNull(content);
        Assertions.assertEquals(TEST_CONTAINER_CONTENT, content);
    }

    @Test
    public void testGetEvaluatedValueWithFF() {
        ProcessSession session = tr.getProcessSessionFactory().createSession();
        tr.setProperty(TestProcessor.TEST_PROP, "${attr1}_${attr2}");
        FlowFile ff = createFlowFile(session, TEST_CONTAINER_CONTENT,
                Map.of("attr1", "val1", "attr2", "val2"));
        ProcessContext pc = tr.getProcessContext();
        String propertyValue = NiFiUtils.getEvaluatedValue(TestProcessor.TEST_PROP, pc, ff);
        Assertions.assertNotNull(propertyValue);
        Assertions.assertEquals("val1_val2", propertyValue);
    }

    @Test
    public void testGetEvaluatedValueWoFF() {
        tr.setProperty(TestProcessor.TEST_PROP, "${attr1}_${attr2}");
        ProcessContext pc = tr.getProcessContext();
        String propertyValue = NiFiUtils.getEvaluatedValue(TestProcessor.TEST_PROP, pc);
        Assertions.assertNotNull(propertyValue);
        Assertions.assertEquals("_", propertyValue);
    }

    @Test
    public void testCreateFlowFileInListFormat() {
        ProcessSession session = tr.getProcessSessionFactory().createSession();
        Map<String, String> attributes = Map.of("attr1", "val1", "attr2", "val2");
        List<String> sourceIdsList = List.of("sourceId1", "sourceId2", "sourceId3");
        FlowFile ff = NiFiUtils.createFlowFileInListFormat(session, attributes, sourceIdsList);
        Assertions.assertNotNull(ff);
        Assertions.assertEquals("val1", ff.getAttribute("attr1"));
        Assertions.assertEquals("val2", ff.getAttribute("attr2"));
        JsonNode jsonContent = NiFiUtils.readJsonNodeFromFlowFile(session, ff);
        JsonNode expectedContent = null;
        try {
            expectedContent = NiFiUtils.MAPPER.readTree(
                    "[{\"source_id\":\"sourceId1\"},{\"source_id\":\"sourceId2\"},"
                            + "{\"source_id\":\"sourceId3\"}]");
        } catch (JsonProcessingException e) {
            Assertions.fail("Failed to parse expected JSON", e);
        }
        Assertions.assertEquals(expectedContent, jsonContent);
    }
}

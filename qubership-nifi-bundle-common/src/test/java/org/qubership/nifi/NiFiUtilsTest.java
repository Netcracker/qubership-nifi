package org.qubership.nifi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.nifi.flowfile.FlowFile;
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

public class NiFiUtilsTest {
    private TestRunner tr;
    @BeforeEach
    public void setUp() {
        tr = TestRunners.newTestRunner(TestProcessor.class);
    }

    @Test
    public void testReadJsonNodeFromFlowFile() {
        ProcessSession session = tr.getProcessSessionFactory().createSession();
        FlowFile ff = session.create();
        session.write(ff, new OutputStreamCallback() {
            @Override
            public void process(OutputStream outputStream) throws IOException {
                outputStream.write("{\"a\":\"1\"}".getBytes(StandardCharsets.UTF_8));
            }
        });
        JsonNode json = NiFiUtils.readJsonNodeFromFlowFile(session, ff);
        Assertions.assertNotNull(json);
        Assertions.assertEquals("1", json.get("a").asText());
    }

    @Test
    public void testReadJsonNodeFromFlowFileWithTargetClass() {
        ProcessSession session = tr.getProcessSessionFactory().createSession();
        FlowFile ff = session.create();
        session.write(ff, new OutputStreamCallback() {
            @Override
            public void process(OutputStream outputStream) throws IOException {
                outputStream.write(("{\"name\":\"SomeName\","
                        + "\"description\":\"SomeDescription\",\"enabled\":true}").
                        getBytes(StandardCharsets.UTF_8));
            }
        });
        TestContainer container = NiFiUtils.readJsonNodeFromFlowFile(session, ff, TestContainer.class);
        Assertions.assertNotNull(container);
        Assertions.assertEquals("SomeName", container.getName());
        Assertions.assertTrue(container.isEnabled());
    }

    @Test
    public void testReadJsonNodeFromFlowFileWithTypeReference() {
        ProcessSession session = tr.getProcessSessionFactory().createSession();
        FlowFile ff = session.create();
        session.write(ff, new OutputStreamCallback() {
            @Override
            public void process(OutputStream outputStream) throws IOException {
                outputStream.write(("{\"name\":\"SomeName\","
                        + "\"description\":\"SomeDescription\",\"enabled\":true}").
                        getBytes(StandardCharsets.UTF_8));
            }
        });
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
}

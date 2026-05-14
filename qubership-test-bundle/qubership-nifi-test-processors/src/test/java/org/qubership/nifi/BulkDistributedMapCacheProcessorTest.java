package org.qubership.nifi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.nifi.distributed.cache.client.Serializer;
import org.apache.nifi.distributed.cache.client.exception.SerializationException;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.processor.BulkDistributedMapCacheProcessor;
import org.qubership.nifi.service.BulkDistributedMapCacheClient;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class BulkDistributedMapCacheProcessorTest {
    private static final BulkDistributedMapCacheClient CACHE = new TestBulkDistributedMapCacheClientService();

    private static final Serializer<String> STRING_SERIALIZER = new StringSerializer();
    private TestRunner testRunner;

    @BeforeEach
    public void setUp() throws InitializationException {
        testRunner = TestRunners.newTestRunner(BulkDistributedMapCacheProcessor.class);
        testRunner.addControllerService("cache", CACHE);
        testRunner.enableControllerService(CACHE);
        testRunner.assertValid(CACHE);
        testRunner.setProperty(BulkDistributedMapCacheProcessor.BULK_DISTRIBUTED_MAP_CACHE, "cache");
    }

    @AfterEach
    public void tearDown() {
        testRunner.disableControllerService(CACHE);
    }

    @Test
    public void testPutIfAbsent() {
        testRunner.enqueue("""
                {
                    "getAndPutIfAbsent": {
                        "key1": "value1",
                        "key2": "value2"
                    }
                }
                """);
        testRunner.run();
        List<MockFlowFile> flowFileListSuccess = testRunner.
                getFlowFilesForRelationship(BulkDistributedMapCacheProcessor.REL_SUCCESS);
        List<MockFlowFile> flowFileListFailure = testRunner.
                getFlowFilesForRelationship(BulkDistributedMapCacheProcessor.REL_FAILURE);
        Assertions.assertEquals(0, flowFileListFailure.size());
        Assertions.assertEquals(1, flowFileListSuccess.size());
        MockFlowFile ff = flowFileListSuccess.get(0);
        JsonNode jsonContent = null;
        jsonContent = parseJsonFromString(ff.getContent());
        JsonNode expectedJson = parseJsonFromString("""
                    {
                        "getAndPutIfAbsent": {
                            "key1": null,
                            "key2": null
                        }
                    }
                    """);
        Assertions.assertEquals(expectedJson, jsonContent);
    }

    @Test
    public void testRemoveNonExisting() {
        testRunner.enqueue("""
                {
                    "remove": ["key11", "key12"]
                }
                """);
        testRunner.run();
        List<MockFlowFile> flowFileListSuccess = testRunner.
                getFlowFilesForRelationship(BulkDistributedMapCacheProcessor.REL_SUCCESS);
        List<MockFlowFile> flowFileListFailure = testRunner.
                getFlowFilesForRelationship(BulkDistributedMapCacheProcessor.REL_FAILURE);
        Assertions.assertEquals(0, flowFileListFailure.size());
        Assertions.assertEquals(1, flowFileListSuccess.size());
        MockFlowFile ff = flowFileListSuccess.get(0);
        JsonNode jsonContent = null;
        jsonContent = parseJsonFromString(ff.getContent());
        JsonNode expectedJson = parseJsonFromString("""
                    {
                        "remove": 0
                    }
                    """);
        Assertions.assertEquals(expectedJson, jsonContent);
    }

    @Test
    public void testRemoveExisting() {
        //create entries:
        try {
            CACHE.put("key111", "value111", STRING_SERIALIZER, STRING_SERIALIZER);
            CACHE.put("key112", "value112", STRING_SERIALIZER, STRING_SERIALIZER);
        } catch (IOException e) {
            Assertions.fail("Failed to initialize cache before test", e);
        }
        testRunner.enqueue("""
                {
                    "remove": ["key111", "key112"]
                }
                """);
        testRunner.run();
        List<MockFlowFile> flowFileListSuccess = testRunner.
                getFlowFilesForRelationship(BulkDistributedMapCacheProcessor.REL_SUCCESS);
        List<MockFlowFile> flowFileListFailure = testRunner.
                getFlowFilesForRelationship(BulkDistributedMapCacheProcessor.REL_FAILURE);
        Assertions.assertEquals(0, flowFileListFailure.size());
        Assertions.assertEquals(1, flowFileListSuccess.size());
        MockFlowFile ff = flowFileListSuccess.get(0);
        JsonNode jsonContent = null;
        jsonContent = parseJsonFromString(ff.getContent());
        JsonNode expectedJson = parseJsonFromString("""
                    {
                        "remove": 2
                    }
                    """);
        Assertions.assertEquals(expectedJson, jsonContent);
    }

    //
    private static JsonNode parseJsonFromString(String content) {
        JsonNode jsonContent = null;
        try {
            jsonContent = NiFiUtils.MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            Assertions.fail("Failed to parse JSON from string", e);
        }
        return jsonContent;
    }

    private static final class StringSerializer implements Serializer<String> {
        @Override
        public void serialize(String s, OutputStream outputStream) throws SerializationException, IOException {
            if (s != null) {
                outputStream.write(s.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}

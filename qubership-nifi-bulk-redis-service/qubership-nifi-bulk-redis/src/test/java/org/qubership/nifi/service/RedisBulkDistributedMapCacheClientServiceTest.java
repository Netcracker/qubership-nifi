package org.qubership.nifi.service;

import org.apache.nifi.distributed.cache.client.exception.SerializationException;
import org.apache.nifi.distributed.cache.client.exception.DeserializationException;
import org.apache.nifi.redis.service.RedisConnectionPoolService;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.distributed.cache.client.Serializer;
import org.apache.nifi.distributed.cache.client.Deserializer;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.qubership.nifi.service.RedisTestProcessor.REDIS_MAP_CACHE_SERVICE;

public class RedisBulkDistributedMapCacheClientServiceTest {
    protected static final String REDIS_IMAGE = "redis:7.0.12-alpine";
    private static final String REDIS_CON_STRING = "localhost:16379";

    private TestRunner testRunner;
    private RedisConnectionPoolService redisConnectionPool;
    private RedisBulkDistributedMapCacheClientService redisBulkDistributedMapCacheClientService;

    private static Serializer<String> stringSerializer = new StringSerializer();
    private static Deserializer<String> stringDeserializer = new StringDeserializer();

    private static GenericContainer<?> redis;

    @BeforeAll
    public static void initContainer() {
        List<String> redisPorts = new ArrayList<>();
        redisPorts.add("16379:6379");

        redis = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE));
        redis.setPortBindings(redisPorts);
        redis.start();
    }

    /**
     * Setups test.
     * @throws InitializationException
     */
    @BeforeEach
    public void setup() throws InitializationException {
        testRunner = TestRunners.newTestRunner(RedisTestProcessor.class);
        redisConnectionPool = new RedisConnectionPoolService();

        testRunner.addControllerService("redis-connection-pool", redisConnectionPool);
        testRunner.setProperty(redisConnectionPool, "Connection String", REDIS_CON_STRING);
        testRunner.enableControllerService(redisConnectionPool);

        redisBulkDistributedMapCacheClientService = new RedisBulkDistributedMapCacheClientService();
        testRunner.addControllerService("redis-map-cache-client", redisBulkDistributedMapCacheClientService);
        testRunner.setProperty(redisBulkDistributedMapCacheClientService,
                RedisBulkDistributedMapCacheClientService.REDIS_CONNECTION_POOL, "redis-connection-pool");
        testRunner.setProperty("Redis-Map-Cache", "redis-map-cache-client");
        testRunner.enableControllerService(redisBulkDistributedMapCacheClientService);
    }

    @Test
    public void testPutAndGet() throws IOException {
        //prepare data for test
        long timestamp = System.currentTimeMillis();
        String prop = "testPutAndGet-redis-processor-" + timestamp;
        String value = "the time is " + timestamp;

        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop, stringSerializer));
        redisBulkDistributedMapCacheClientService.put(prop, value, stringSerializer, stringSerializer);
        assertTrue(redisBulkDistributedMapCacheClientService.containsKey(prop, stringSerializer));

        String retrievedValue = redisBulkDistributedMapCacheClientService
                .get(prop, stringSerializer, stringDeserializer);
        assertEquals(value, retrievedValue);
    }

    @Test
    public void testRemove() throws IOException {
        String prop1 = "testRemove-1";
        String value1 = "value-1";
        String prop2 = "testRemove-2";
        String value2 = "value-2";

        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop1, stringSerializer));
        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop2, stringSerializer));
        redisBulkDistributedMapCacheClientService.put(prop1, value1, stringSerializer, stringSerializer);
        redisBulkDistributedMapCacheClientService.put(prop2, value2, stringSerializer, stringSerializer);
        assertTrue(redisBulkDistributedMapCacheClientService.containsKey(prop1, stringSerializer));
        assertTrue(redisBulkDistributedMapCacheClientService.containsKey(prop2, stringSerializer));

        List<String> listKeysForRemove = new ArrayList<>();

        long removeResult;
        removeResult = redisBulkDistributedMapCacheClientService.remove(listKeysForRemove, stringSerializer);
        assertEquals(0, removeResult);

        listKeysForRemove.add(prop1);
        listKeysForRemove.add(prop2);

        removeResult = redisBulkDistributedMapCacheClientService.remove(listKeysForRemove, stringSerializer);
        assertEquals(listKeysForRemove.size(), removeResult);
        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop1, stringSerializer));
        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop2, stringSerializer));
    }

    @Test
    public void testPutIfAbsent() throws IOException {
        String prop = "testPutIfAbsent";
        String value = "value";

        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop, stringSerializer));
        assertTrue(redisBulkDistributedMapCacheClientService.putIfAbsent(prop, value, stringSerializer,
                stringSerializer));
        assertFalse(redisBulkDistributedMapCacheClientService.putIfAbsent(prop, "some other value",
                stringSerializer, stringSerializer));
        assertEquals(value, redisBulkDistributedMapCacheClientService.get(prop, stringSerializer,
                stringDeserializer));
    }

    @Test
    public void testGetAndPutIfAbsent() throws IOException {
        long timestamp = System.currentTimeMillis();
        String prop = "testGetAndPutIfAbsent-prop-" + timestamp;
        String value1 = "value-1-" + timestamp;

        Map<String, String> stringMap1 = new HashMap<>();
        stringMap1.put(prop, value1);

        Map<String, String> result1 = redisBulkDistributedMapCacheClientService
                .getAndPutIfAbsent(stringMap1, stringSerializer, stringSerializer, stringDeserializer);
        assertEquals(null, result1.get(prop));
        assertEquals(value1, redisBulkDistributedMapCacheClientService.get(prop, stringSerializer, stringDeserializer));

        String value2 = "value-2-" + timestamp;
        Map<String, String> stringMap2 = new HashMap<>();
        stringMap2.put(prop, value2);

        Map<String, String> result2 = redisBulkDistributedMapCacheClientService
                .getAndPutIfAbsent(stringMap2, stringSerializer, stringSerializer, stringDeserializer);
        assertEquals(value1, result2.get(prop));

        String keyNotExist = prop + "_DOES_NOT_EXIST";
        String value3 = "value-3";
        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(keyNotExist, stringSerializer));

        Map<String, String> keyNoExist = new HashMap<>();
        keyNoExist.put(keyNotExist, value3);

        Map<String, String> resultNotExist = redisBulkDistributedMapCacheClientService
                .getAndPutIfAbsent(keyNoExist, stringSerializer, stringSerializer, stringDeserializer);
        assertEquals(null, resultNotExist.get(keyNotExist));
        assertEquals(value3, redisBulkDistributedMapCacheClientService.get(keyNotExist, stringSerializer, stringDeserializer));
    }

    @Test
    public void testBulkGetAndPutIfAbsent() throws IOException {
        long timestamp = System.currentTimeMillis();
        String prop1 = "testBulkGetAndPutIfAbsent-1" + timestamp;
        String prop2 = "testBulkGetAndPutIfAbsent-2" + timestamp;
        String prop3 = "testBulkGetAndPutIfAbsent-3" + timestamp;
        String value1 = "value-1-" + timestamp;
        String value2 = "value-2-" + timestamp;
        String value3 = "value-3-" + timestamp;

        Map<String, String> keyAndValue = new HashMap<>();
        keyAndValue.put(prop1, value1);
        keyAndValue.put(prop2, value2);
        keyAndValue.put(prop3, value3);

        redisBulkDistributedMapCacheClientService.put(prop1, value1, stringSerializer, stringSerializer);
        redisBulkDistributedMapCacheClientService.put(prop3, value3, stringSerializer, stringSerializer);

        Map<String, String> bulkResult = redisBulkDistributedMapCacheClientService
                .getAndPutIfAbsent(keyAndValue, stringSerializer, stringSerializer, stringDeserializer);
        assertEquals(value1, bulkResult.get(prop1));
        assertEquals(null, bulkResult.get(prop2));
        assertEquals(value3, bulkResult.get(prop3));
    }

    private static final class StringSerializer implements Serializer<String> {
        @Override
        public void serialize(String s, OutputStream outputStream) throws SerializationException, IOException {
            if (s != null) {
                outputStream.write(s.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static final class StringDeserializer implements Deserializer<String> {
        @Override
        public String deserialize(byte[] input) throws DeserializationException, IOException {
            return input == null ? null : new String(input, StandardCharsets.UTF_8);
        }
    }

    @AfterEach
    public void tearDown() {
        if (redisConnectionPool != null) {
            redisConnectionPool.onDisabled();
        }
        if (redisBulkDistributedMapCacheClientService != null) {
            redisBulkDistributedMapCacheClientService.shutdown();
        }
    }

    @AfterAll
    public static void stopContainer() {
        redis.stop();
    }
}

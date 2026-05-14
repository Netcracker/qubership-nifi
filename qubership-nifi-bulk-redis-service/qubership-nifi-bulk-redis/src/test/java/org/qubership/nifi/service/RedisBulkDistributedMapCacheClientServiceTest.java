package org.qubership.nifi.service;

import org.apache.nifi.redis.service.RedisConnectionPoolService;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.distributed.cache.client.Serializer;
import org.apache.nifi.distributed.cache.client.Deserializer;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisBulkDistributedMapCacheClientServiceTest {
    private static final String REDIS_IMAGE = "redis:7.0.12-alpine";
    private static final String REDIS_CON_STRING = "localhost:16379";

    private TestRunner testRunner;
    private RedisConnectionPoolService redisConnectionPool;
    private RedisBulkDistributedMapCacheClientService redisBulkDistributedMapCacheClientService;

    private static final Serializer<String> STRING_SERIALIZER = new StringSerializer();
    private static final Deserializer<String> STRING_DESERIALIZER = new StringDeserializer();

    private static GenericContainer<?> redis;

    @BeforeAll
    static void initContainer() {
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
    void setup() throws InitializationException {
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
    void testPutAndGet() throws IOException {
        //prepare data for test
        long timestamp = System.currentTimeMillis();
        String prop = "testPutAndGet-redis-processor-" + timestamp;
        String value = "the time is " + timestamp;

        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop, STRING_SERIALIZER));
        redisBulkDistributedMapCacheClientService.put(prop, value, STRING_SERIALIZER, STRING_SERIALIZER);
        assertTrue(redisBulkDistributedMapCacheClientService.containsKey(prop, STRING_SERIALIZER));

        String retrievedValue = redisBulkDistributedMapCacheClientService
                .get(prop, STRING_SERIALIZER, STRING_DESERIALIZER);
        assertEquals(value, retrievedValue);
    }


    @Test
    void testPutAndGetWithTTL() throws IOException {
        //prepare data for test
        long timestamp = System.currentTimeMillis();
        String prop = "testPutAndGet-redis-processor-" + timestamp;
        String value = "the time is " + timestamp;

        testRunner.disableControllerService(redisBulkDistributedMapCacheClientService);
        testRunner.setProperty(redisBulkDistributedMapCacheClientService,
                RedisBulkDistributedMapCacheClientService.TTL, "2 secs");
        testRunner.enableControllerService(redisBulkDistributedMapCacheClientService);
        testRunner.assertValid(redisBulkDistributedMapCacheClientService);

        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop, STRING_SERIALIZER));
        redisBulkDistributedMapCacheClientService.put(prop, value, STRING_SERIALIZER, STRING_SERIALIZER);
        assertTrue(redisBulkDistributedMapCacheClientService.containsKey(prop, STRING_SERIALIZER));

        String retrievedValue = redisBulkDistributedMapCacheClientService
                .get(prop, STRING_SERIALIZER, STRING_DESERIALIZER);
        assertEquals(value, retrievedValue);
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Assertions.fail("Failed to wait for 3 seconds", e);
        }
        //assert that value is gone after TTL:
        assertNull(redisBulkDistributedMapCacheClientService.get(prop, STRING_SERIALIZER,
                STRING_DESERIALIZER));
    }

    @Test
    void testRemove() throws IOException {
        String prop1 = "testRemove-1";
        String value1 = "value-1";
        String prop2 = "testRemove-2";
        String value2 = "value-2";

        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop1, STRING_SERIALIZER));
        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop2, STRING_SERIALIZER));
        redisBulkDistributedMapCacheClientService.put(prop1, value1, STRING_SERIALIZER, STRING_SERIALIZER);
        redisBulkDistributedMapCacheClientService.put(prop2, value2, STRING_SERIALIZER, STRING_SERIALIZER);
        assertTrue(redisBulkDistributedMapCacheClientService.containsKey(prop1, STRING_SERIALIZER));
        assertTrue(redisBulkDistributedMapCacheClientService.containsKey(prop2, STRING_SERIALIZER));

        List<String> listKeysForRemove = new ArrayList<>();

        long removeResult;
        removeResult = redisBulkDistributedMapCacheClientService.remove(listKeysForRemove, STRING_SERIALIZER);
        assertEquals(0, removeResult);

        listKeysForRemove.add(prop1);
        listKeysForRemove.add(prop2);

        removeResult = redisBulkDistributedMapCacheClientService.remove(listKeysForRemove, STRING_SERIALIZER);
        assertEquals(listKeysForRemove.size(), removeResult);
        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop1, STRING_SERIALIZER));
        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop2, STRING_SERIALIZER));
    }

    @Test
    void testPutIfAbsent() throws IOException {
        String prop = "testPutIfAbsent";
        String value = "value";

        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop, STRING_SERIALIZER));
        assertTrue(redisBulkDistributedMapCacheClientService.putIfAbsent(prop, value, STRING_SERIALIZER,
                STRING_SERIALIZER));
        assertFalse(redisBulkDistributedMapCacheClientService.putIfAbsent(prop, "some other value",
                STRING_SERIALIZER, STRING_SERIALIZER));
        assertEquals(value, redisBulkDistributedMapCacheClientService.get(prop, STRING_SERIALIZER,
                STRING_DESERIALIZER));
    }

    @Test
    void testPutIfAbsentWithTTL() throws IOException {
        String prop = "testPutIfAbsent";
        String value = "value";

        testRunner.disableControllerService(redisBulkDistributedMapCacheClientService);
        testRunner.setProperty(redisBulkDistributedMapCacheClientService,
                RedisBulkDistributedMapCacheClientService.TTL, "2 secs");
        testRunner.enableControllerService(redisBulkDistributedMapCacheClientService);
        testRunner.assertValid(redisBulkDistributedMapCacheClientService);

        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop, STRING_SERIALIZER));
        assertTrue(redisBulkDistributedMapCacheClientService.putIfAbsent(prop, value, STRING_SERIALIZER,
                STRING_SERIALIZER));
        assertFalse(redisBulkDistributedMapCacheClientService.putIfAbsent(prop, "some other value",
                STRING_SERIALIZER, STRING_SERIALIZER));
        assertEquals(value, redisBulkDistributedMapCacheClientService.get(prop, STRING_SERIALIZER,
                STRING_DESERIALIZER));
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Assertions.fail("Failed to wait for 3 seconds", e);
        }
        //assert that value is gone after TTL:
        assertNull(redisBulkDistributedMapCacheClientService.get(prop, STRING_SERIALIZER,
                STRING_DESERIALIZER));
    }

    @Test
    void testGetAndPutIfAbsent() throws IOException {
        long timestamp = System.currentTimeMillis();
        String prop = "testGetAndPutIfAbsent-prop-" + timestamp;
        String value1 = "value-1-" + timestamp;

        Map<String, String> stringMap1 = new HashMap<>();
        stringMap1.put(prop, value1);

        Map<String, String> result1 = redisBulkDistributedMapCacheClientService
                .getAndPutIfAbsent(stringMap1, STRING_SERIALIZER, STRING_SERIALIZER, STRING_DESERIALIZER);
        assertNull(result1.get(prop));
        assertEquals(value1,
                redisBulkDistributedMapCacheClientService.get(prop, STRING_SERIALIZER, STRING_DESERIALIZER));

        String value2 = "value-2-" + timestamp;
        Map<String, String> stringMap2 = new HashMap<>();
        stringMap2.put(prop, value2);

        Map<String, String> result2 = redisBulkDistributedMapCacheClientService
                .getAndPutIfAbsent(stringMap2, STRING_SERIALIZER, STRING_SERIALIZER, STRING_DESERIALIZER);
        assertEquals(value1, result2.get(prop));

        String keyNotExist = prop + "_DOES_NOT_EXIST";
        String value3 = "value-3";
        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(keyNotExist, STRING_SERIALIZER));

        Map<String, String> keyNoExist = new HashMap<>();
        keyNoExist.put(keyNotExist, value3);

        Map<String, String> resultNotExist = redisBulkDistributedMapCacheClientService
                .getAndPutIfAbsent(keyNoExist, STRING_SERIALIZER, STRING_SERIALIZER, STRING_DESERIALIZER);
        assertNull(resultNotExist.get(keyNotExist));
        assertEquals(value3, redisBulkDistributedMapCacheClientService.get(keyNotExist, STRING_SERIALIZER,
                STRING_DESERIALIZER));
    }


    @Test
    void testGetAndPutIfAbsentWithUnavailableRedis() {
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
        redis.stop();

        Assertions.assertThrows(RedisConnectionFailureException.class, () -> {
            redisBulkDistributedMapCacheClientService
                    .getAndPutIfAbsent(keyAndValue, STRING_SERIALIZER, STRING_SERIALIZER, STRING_DESERIALIZER);
        });
    }

    @Test
    void testBulkGetAndPutIfAbsent() throws IOException {
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

        redisBulkDistributedMapCacheClientService.put(prop1, value1, STRING_SERIALIZER, STRING_SERIALIZER);
        redisBulkDistributedMapCacheClientService.put(prop3, value3, STRING_SERIALIZER, STRING_SERIALIZER);

        Map<String, String> bulkResult = redisBulkDistributedMapCacheClientService
                .getAndPutIfAbsent(keyAndValue, STRING_SERIALIZER, STRING_SERIALIZER, STRING_DESERIALIZER);
        assertEquals(value1, bulkResult.get(prop1));
        assertNull(bulkResult.get(prop2));
        assertEquals(value3, bulkResult.get(prop3));
    }

    @Test
    void testEmptyGetAndPutIfAbsent() throws IOException {
        Map<String, String> keyAndValue = new HashMap<>();
        Map<String, String> bulkResult = redisBulkDistributedMapCacheClientService
                .getAndPutIfAbsent(keyAndValue, STRING_SERIALIZER, STRING_SERIALIZER, STRING_DESERIALIZER);
        assertEquals(0, bulkResult.size());
    }

    @Test
    void getAndPutIfAbsentWithTTL() throws IOException {
        long timestamp = System.currentTimeMillis();
        String prop = "testGetAndPutIfAbsentWithTTL-prop-" + timestamp;
        String value = "value-1-" + timestamp;

        Map<String, String> stringMap1 = new HashMap<>();
        stringMap1.put(prop, value);

        testRunner.disableControllerService(redisBulkDistributedMapCacheClientService);
        testRunner.setProperty(redisBulkDistributedMapCacheClientService,
                RedisBulkDistributedMapCacheClientService.TTL, "2 secs");
        testRunner.enableControllerService(redisBulkDistributedMapCacheClientService);
        testRunner.assertValid(redisBulkDistributedMapCacheClientService);

        Map<String, String> result1 = redisBulkDistributedMapCacheClientService
                .getAndPutIfAbsent(stringMap1, STRING_SERIALIZER, STRING_SERIALIZER, STRING_DESERIALIZER);
        assertNull(result1.get(prop));
        assertEquals(value,
                redisBulkDistributedMapCacheClientService.get(prop, STRING_SERIALIZER, STRING_DESERIALIZER));

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Assertions.fail("Failed to wait for 3 seconds", e);
        }
        //assert that value is gone after TTL:
        assertNull(redisBulkDistributedMapCacheClientService.get(prop, STRING_SERIALIZER,
                STRING_DESERIALIZER));
    }

    @AfterEach
    void tearDown() {
        if (redisConnectionPool != null) {
            redisConnectionPool.onDisabled();
        }
        if (redisBulkDistributedMapCacheClientService != null) {
            redisBulkDistributedMapCacheClientService.shutdown();
        }
        if (!redis.isRunning()) {
            //start again, if it was stopped during test
            redis.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        redis.stop();
    }
}

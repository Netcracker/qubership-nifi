package org.qubership.nifi.service;

import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.distributed.cache.client.Deserializer;
import org.apache.nifi.distributed.cache.client.Serializer;
import org.apache.nifi.redis.RedisConnectionPool;
import org.apache.nifi.redis.RedisType;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.RedisStringCommands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.data.redis.connection.ReturnType.MULTI;

class RedisBulkDistributedMapCacheClientServiceMockTest {
    private TestRunner testRunner;
    private TestRedisConnectionPoolService redisConnectionPool;
    private RedisBulkDistributedMapCacheClientService redisBulkDistributedMapCacheClientService;

    private static final Serializer<String> STRING_SERIALIZER = new StringSerializer();
    private static final Deserializer<String> STRING_DESERIALIZER = new StringDeserializer();

    /**
     * Setups test.
     * @throws InitializationException
     */
    @BeforeEach
    void setup() throws InitializationException {
        testRunner = TestRunners.newTestRunner(RedisTestProcessor.class);
        redisConnectionPool = new TestRedisConnectionPoolService();

        testRunner.addControllerService("redis-connection-pool", redisConnectionPool);
        testRunner.enableControllerService(redisConnectionPool);

        redisBulkDistributedMapCacheClientService = new RedisBulkDistributedMapCacheClientService();
        testRunner.addControllerService("redis-map-cache-client", redisBulkDistributedMapCacheClientService);
        testRunner.setProperty(redisBulkDistributedMapCacheClientService,
                RedisBulkDistributedMapCacheClientService.REDIS_CONNECTION_POOL, "redis-connection-pool");
        testRunner.setProperty("Redis-Map-Cache", "redis-map-cache-client");
        testRunner.enableControllerService(redisBulkDistributedMapCacheClientService);
    }

    @Test
    void testGetAndPutIfAbsentWithSystemException() {
        long timestamp = System.currentTimeMillis();
        String prop = "testGetAndPutIfAbsent-prop-" + timestamp;
        String value1 = "value-1-" + timestamp;

        Map<String, String> stringMap1 = new HashMap<>();
        stringMap1.put(prop, value1);

        assertThrows(RedisSystemException.class, () -> {
            redisBulkDistributedMapCacheClientService
                    .getAndPutIfAbsent(stringMap1, STRING_SERIALIZER, STRING_SERIALIZER, STRING_DESERIALIZER);
        });
    }

    @Test
    void testGetAndPutIfAbsentWithNullResult() throws IOException {
        Map<String, String> stringMap1 = new HashMap<>();

        Map<String, String> result = redisBulkDistributedMapCacheClientService
                .getAndPutIfAbsent(stringMap1, STRING_SERIALIZER, STRING_SERIALIZER, STRING_DESERIALIZER);
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void testGetAndPutIfAbsentWithTransientDataAccessException() {
        long timestamp = System.currentTimeMillis();
        Map<String, String> stringMap1 = new HashMap<>();
        stringMap1.put("testGetAndPutIfAbsent-prop1-" + timestamp, "value-1-" + timestamp);
        stringMap1.put("testGetAndPutIfAbsent-prop2-" + timestamp, "value-1-" + timestamp);

        assertThrows(TransientDataAccessResourceException.class, () -> {
            redisBulkDistributedMapCacheClientService
                    .getAndPutIfAbsent(stringMap1, STRING_SERIALIZER, STRING_SERIALIZER, STRING_DESERIALIZER);
        });
    }

    @Test
    void testGetAndPutIfAbsentWithDuplicateKeyException() {
        long timestamp = System.currentTimeMillis();
        Map<String, String> stringMap1 = new HashMap<>();
        stringMap1.put("testGetAndPutIfAbsent-prop1-" + timestamp, "value-1-" + timestamp);
        stringMap1.put("testGetAndPutIfAbsent-prop2-" + timestamp, "value-1-" + timestamp);
        stringMap1.put("testGetAndPutIfAbsent-prop3-" + timestamp, "value-1-" + timestamp);

        assertThrows(DuplicateKeyException.class, () -> {
            redisBulkDistributedMapCacheClientService
                    .getAndPutIfAbsent(stringMap1, STRING_SERIALIZER, STRING_SERIALIZER, STRING_DESERIALIZER);
        });
    }

    @Test
    void testGetAndPutIfAbsentWithThrowOnClose() throws IOException {
        Map<String, String> stringMap1 = new HashMap<>();
        redisConnectionPool.setDoThrowExceptionOnClose(true);

        //close throws an exception, and it should be logged as warning w/o other impact:
        Map<String, String> result = redisBulkDistributedMapCacheClientService
                .getAndPutIfAbsent(stringMap1, STRING_SERIALIZER, STRING_SERIALIZER, STRING_DESERIALIZER);
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void testContainsKeyNull() throws IOException  {
        String prop = "test-prop";

        assertFalse(redisBulkDistributedMapCacheClientService.containsKey(prop, STRING_SERIALIZER));
    }

    @Test
    void testPutIfAbsentNull() throws IOException {
        String prop = "test-prop";
        String value = "test-value";
        assertFalse(redisBulkDistributedMapCacheClientService.putIfAbsent(prop, value,
                STRING_SERIALIZER, STRING_SERIALIZER));
    }

    @Test
    void testRemoveNull() throws IOException {
        String prop1 = "testRemoveNull";

        List<String> listKeysForRemove = new ArrayList<>();

        listKeysForRemove.add(prop1);

        long removeResult;
        removeResult = redisBulkDistributedMapCacheClientService.remove(listKeysForRemove, STRING_SERIALIZER);
        assertEquals(0, removeResult);
    }

    private static final class TestRedisConnectionPoolService
            extends AbstractControllerService
            implements RedisConnectionPool {

        private boolean doThrowExceptionOnClose = false;
        public void setDoThrowExceptionOnClose(boolean newDoThrowExceptionOnClose) {
            this.doThrowExceptionOnClose = newDoThrowExceptionOnClose;
        }

        @Override
        public RedisConnection getConnection() {
            RedisConnection conn = mock(RedisConnection.class);
            if (doThrowExceptionOnClose) {
                doThrow(new RuntimeException("Failed to close connection")).when(conn).close();
            }
            RedisScriptingCommands scriptingCommands = mock(RedisScriptingCommands.class);
            when(scriptingCommands.scriptLoad(any(byte[].class))).thenReturn("12345");
            //NonTransientDataAccessException case:
            when(scriptingCommands.evalSha(any(byte[].class), eq(MULTI), eq(1), any())).
                    thenThrow(new RedisSystemException("Test error",
                            new RedisSystemException("Test nested error", null)));
            //TransientDataAccessException case:
            when(scriptingCommands.evalSha(any(byte[].class), eq(MULTI), eq(2), any())).
                    thenThrow(new TransientDataAccessResourceException("Test error"));
            //null message case:
            when(scriptingCommands.evalSha(any(byte[].class), eq(MULTI), eq(3), any())).
                    thenThrow(new DuplicateKeyException(null));
            when(conn.scriptingCommands()).thenReturn(scriptingCommands);

            RedisKeyCommands redisKeyCommands = mock(RedisKeyCommands.class);
            //null exists case:
            when(redisKeyCommands.exists(any(byte[].class))).thenReturn(null);
            //null del case:
            when(redisKeyCommands.del(any(byte[].class))).thenReturn(null);
            when(conn.keyCommands()).thenReturn(redisKeyCommands);

            RedisStringCommands redisStringCommands = mock(RedisStringCommands.class);
            //null putIfAbsent case:
            when(redisStringCommands.setNX(any(byte[].class), any(byte[].class))).thenReturn(null);
            when(conn.stringCommands()).thenReturn(redisStringCommands);
            return conn;
        }

        @Override
        public RedisType getRedisType() {
            return RedisType.STANDALONE;
        }
    }
}

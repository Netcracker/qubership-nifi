/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.qubership.nifi.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.distributed.cache.client.Deserializer;
import org.apache.nifi.distributed.cache.client.Serializer;
import org.apache.nifi.redis.util.RedisAction;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.redis.RedisConnectionPool;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.Tuple;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.script.DigestUtils;
import org.springframework.data.redis.core.types.Expiration;
import redis.clients.jedis.util.SafeEncoder;

import static org.springframework.data.redis.connection.ReturnType.MULTI;

@Tags({"redis", "distributed", "cache", "map"})
@CapabilityDescription("Provides a Redis-based distributed map cache client with bulk operation support. \n"
        + "This service enables efficient batch operations on Redis cache, including bulk get-and-put-if-absent \n"
        + "and bulk remove operations. It uses Lua scripting for atomic bulk operations and supports configurable \n"
        + "TTL (time-to-live) for cached entries. The service is particularly useful for high-performance scenarios \n"
        + "requiring atomic bulk cache operations across multiple NiFi instances.")
public class RedisBulkDistributedMapCacheClientService
        extends AbstractControllerService implements BulkDistributedMapCacheClient {

    /**
     * Redis Connection Pool Property Descriptor.
     */
    public static final PropertyDescriptor REDIS_CONNECTION_POOL = new PropertyDescriptor.Builder()
            .name("redis-connection-pool")
            .displayName("Redis Connection Pool")
            .description("A service that provides connections to Redis.")
            .identifiesControllerService(RedisConnectionPool.class)
            .required(false)
            .build();

    /**
     * TTL Property Descriptor.
     */
    public static final PropertyDescriptor TTL = new PropertyDescriptor.Builder()
            .name("redis-cache-ttl")
            .displayName("TTL")
            .description("Indicates how long the data should exist in Redis."
                    + "Setting '0 secs' would mean the data would exist forever")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .required(true)
            .defaultValue("0 secs")
            .build();

    private static final List<PropertyDescriptor> PROPERTY_DESCRIPTORS;
    private volatile RedisConnectionPool redisConnectionPool;
    private Long ttlValue;
    private byte[] ttlValueSerialized;

    static {
        final List<PropertyDescriptor> props = new ArrayList<>();
        props.add(REDIS_CONNECTION_POOL);
        props.add(TTL);
        PROPERTY_DESCRIPTORS = Collections.unmodifiableList(props);
    }

    /**
     * Allows subclasses to register which property descriptor objects are supported. Default return is an empty set.
     * @return PropertyDescriptor objects this processor currently supports
     */
    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTY_DESCRIPTORS;
    }

    private static final Serializer<String> STRING_SERIALIZER = (value, output)
            -> output.write(value.getBytes(StandardCharsets.UTF_8));
    private static final Deserializer<String> VALUE_DESERIALIZER = String::new;

    /**
     * @param context
     *            the configuration context
     * @throws InitializationException
     *             if unable to create a database connection
     */
    @OnEnabled
    public void onEnabled(final ConfigurationContext context) throws InitializationException, IOException {
        this.redisConnectionPool = context.getProperty(REDIS_CONNECTION_POOL)
                .asControllerService(RedisConnectionPool.class);
        this.ttlValue = context.getProperty(TTL).asTimePeriod(TimeUnit.SECONDS);

        if (ttlValue == 0) {
            this.ttlValue = -1L;
        }

        this.ttlValueSerialized = serialize(String.valueOf(ttlValue), STRING_SERIALIZER);
    }

    /**
     * This method is called during disable controller services.
     */
    @OnDisabled
    public void shutdown() {
        this.redisConnectionPool = null;
    }

    private static final String GET_AND_PUT_IF_ABSENT_SCRIPT = "local result = {}\n"
            + "for i in ipairs(KEYS) do\n"
            + "  local currentValue = redis.call(\"GET\", KEYS[i])\n"
            + "  if (not currentValue) then \n"
            + "    redis.call(\"SET\", KEYS[i], ARGV[i])\n"
            + "  end\n"
            + "  result[i] = currentValue \n"
            + "end \n"
            + " return result";

    private static final String GET_AND_PUT_IF_ABSENT_WITH_TTL_SCRIPT = "local result = {}\n"
            + "local ttl = tonumber(ARGV[#KEYS + 1])\n"
            + "for i in ipairs(KEYS) do\n"
            + "  local currentValue = redis.call(\"GET\", KEYS[i])\n"
            + "  if (not currentValue) then \n"
            + "    redis.call(\"SET\", KEYS[i], ARGV[i], \"EX\", ttl)\n"
            + "  end\n"
            + "  result[i] = currentValue \n"
            + "end \n"
            + "return result";
    private static final byte[] GET_AND_PUT_IF_ABSENT_SCRIPT_BYTES;
    private static final byte[] GET_AND_PUT_IF_ABSENT_WITH_TTL_SCRIPT_BYTES;
    private static final byte[] GET_AND_PUT_IF_ABSENT_SCRIPT_SHA1_BYTES;
    private static final byte[] GET_AND_PUT_IF_ABSENT_WITH_TTL_SCRIPT_SHA1_BYTES;
    static {
        try {
            GET_AND_PUT_IF_ABSENT_SCRIPT_BYTES = serialize(GET_AND_PUT_IF_ABSENT_SCRIPT, STRING_SERIALIZER);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize GetAndPutIfAbsent script", e);
        }
        GET_AND_PUT_IF_ABSENT_SCRIPT_SHA1_BYTES =
                SafeEncoder.encode(DigestUtils.sha1DigestAsHex(GET_AND_PUT_IF_ABSENT_SCRIPT));
        try {
            GET_AND_PUT_IF_ABSENT_WITH_TTL_SCRIPT_BYTES = serialize(GET_AND_PUT_IF_ABSENT_WITH_TTL_SCRIPT,
                    STRING_SERIALIZER);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize GetAndPutIfAbsentWithTTL script", e);
        }
        GET_AND_PUT_IF_ABSENT_WITH_TTL_SCRIPT_SHA1_BYTES =
                SafeEncoder.encode(DigestUtils.sha1DigestAsHex(GET_AND_PUT_IF_ABSENT_WITH_TTL_SCRIPT));
    }

    @Override
    public <K, V> Map<K, V> getAndPutIfAbsent(
            Map<K, V> map,
            final Serializer<K> keySerializer,
            final Serializer<V> valueSerializer,
            final Deserializer<V> valueDeserializer
    ) throws IOException {
        return withConnection(redisConnection -> {
            List<K> keys = new ArrayList<>();
            int mapSize = map.size();
            byte[][] serialisedParams;

            if (ttlValue < 0) {
                serialisedParams = new byte[mapSize * 2][];
            } else {
                serialisedParams = new byte[mapSize * 2 + 1][];
            }
            int j = 0;
            for (Map.Entry<K, V> entry : map.entrySet()) {
                //add key:
                serialisedParams[j] = serialize(entry.getKey(), keySerializer);
                //add value:
                serialisedParams[j + mapSize] = serialize(entry.getValue(), valueSerializer);
                keys.add(entry.getKey());
                j++;
            }

            List<Object> oldValues;

            if (ttlValue < 0) {
                oldValues = executeGetAndPutIfAbsentScript(
                        redisConnection,
                        keys,
                        serialisedParams,
                        GET_AND_PUT_IF_ABSENT_SCRIPT_BYTES,
                        GET_AND_PUT_IF_ABSENT_SCRIPT_SHA1_BYTES);
            } else {
                serialisedParams[2 * mapSize] = ttlValueSerialized;
                oldValues = executeGetAndPutIfAbsentScript(
                        redisConnection,
                        keys,
                        serialisedParams,
                        GET_AND_PUT_IF_ABSENT_WITH_TTL_SCRIPT_BYTES,
                        GET_AND_PUT_IF_ABSENT_WITH_TTL_SCRIPT_SHA1_BYTES);
            }


            // process results:
            Map<K, V> res = new HashMap<>();
            if (oldValues != null) {
                for (int i = 0; i < oldValues.size(); i++) {
                    Object oldValue = oldValues.get(i);
                    res.put(keys.get(i), oldValue == null ? null : valueDeserializer.deserialize((byte[]) oldValue));
                }
            }

            return res;
        });
    }

    private static <K> List<Object> executeGetAndPutIfAbsentScript(
            RedisConnection redisConnection,
            List<K> keys,
            byte[][] serialisedParams,
            byte[] scriptBytes,
            byte[] scriptSha1Bytes
    ) {
        List<Object> oldValues = null;
        try {
            oldValues = redisConnection.scriptingCommands().evalSha(scriptSha1Bytes,
                            MULTI, keys.size(), serialisedParams);
        } catch (Exception ex) {
            if (exceptionContainsNoScriptError(ex)) {
                //script not found, load script and try again:
                redisConnection.scriptingCommands().scriptLoad(scriptBytes);
                oldValues = redisConnection.scriptingCommands().evalSha(scriptSha1Bytes,
                        MULTI, keys.size(), serialisedParams);
            } else {
                //rethrow Exception
                throw ex;
            }
        }
        return oldValues;
    }

    private static boolean exceptionContainsNoScriptError(Throwable e) {
        if (!(e instanceof NonTransientDataAccessException)) {
            return false;
        }

        Throwable current = e;
        while (current != null) {
            String exMessage = current.getMessage();
            if (exMessage != null && exMessage.contains("NOSCRIPT")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public <K> long remove(List<K> keys, Serializer<K> keySerializer) throws IOException {
        if (keys.isEmpty()) {
            return 0;
        }
        return withConnection(redisConnection -> {
            byte[][] serialisedKeys = new byte[keys.size()][];
            for (int i = 0; i < keys.size(); i++) {
                K key = keys.get(i);
                serialisedKeys[i] = serialize(key, keySerializer);
            }
            Long numRemoved = redisConnection.keyCommands().del(serialisedKeys);
            if (numRemoved == null) {
                numRemoved = 0L;
            }
            return numRemoved;
        });
    }


    @Override
    public <K, V> boolean putIfAbsent(
            final K key,
            final V value,
            final Serializer<K> keySerializer,
            final Serializer<V> valueSerializer
    ) throws IOException {
        return withConnection(redisConnection -> {
            final Tuple<byte[], byte[]> kv = serialize(key, value, keySerializer, valueSerializer);
            Boolean set = redisConnection.stringCommands().setNX(kv.getKey(), kv.getValue());
            if (set == null) {
                set = false;
            }

            if (ttlValue != -1L && set) {
                redisConnection.keyCommands().expire(kv.getKey(), ttlValue);
            }

            return set;
        });
    }


    @Override
    public <K> boolean containsKey(
            final K key,
            final Serializer<K> keySerializer
    ) throws IOException {
        return withConnection(redisConnection -> {
            final byte[] k = serialize(key, keySerializer);
            Boolean exist = redisConnection.keyCommands().exists(k);
            if (exist == null) {
                exist = false;
            }
            return exist;
        });
    }

    @Override
    public <K, V> void put(
            final K key,
            final V value,
            final Serializer<K> keySerializer,
            final Serializer<V> valueSerializer
    ) throws IOException {
        withConnection(redisConnection -> {
            final Tuple<byte[], byte[]> kv = serialize(key, value, keySerializer, valueSerializer);
            redisConnection.stringCommands().set(
                    kv.getKey(),
                    kv.getValue(),
                    Expiration.seconds(ttlValue),
                    RedisStringCommands.SetOption.upsert()
            );
            return null;
        });
    }


    @Override
    public <K, V> V get(
            final K key,
            final Serializer<K> keySerializer,
            final Deserializer<V> valueDeserializer
    ) throws IOException {
        return withConnection(redisConnection -> {
            final byte[] k = serialize(key, keySerializer);
            byte[] v = redisConnection.stringCommands().get(k);
            return valueDeserializer.deserialize(v);
        });
    }


    private <T> T withConnection(final RedisAction<T> action) throws IOException {
        RedisConnection redisConnection = null;
        try {
            redisConnection = redisConnectionPool.getConnection();
            return action.execute(redisConnection);
        } finally {
            if (redisConnection != null) {
                try {
                    redisConnection.close();
                } catch (Exception e) {
                    getLogger().warn("Error closing connection: " + e.getMessage(), e);
                }
            }
        }
    }

    private <K, V> Tuple<byte[], byte[]> serialize(
            K key,
            V value,
            final Serializer<K> keySerializer,
            final Serializer<V> valueSerializer
    ) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();


        keySerializer.serialize(key, out);

        final byte[] k = out.toByteArray();

        out.reset();

        valueSerializer.serialize(value, out);
        final byte[] v = out.toByteArray();

        return new Tuple<>(k, v);

    }

    private static <K> byte[] serialize(final K key, final Serializer<K> keySerializer) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        keySerializer.serialize(key, out);
        return out.toByteArray();

    }

}

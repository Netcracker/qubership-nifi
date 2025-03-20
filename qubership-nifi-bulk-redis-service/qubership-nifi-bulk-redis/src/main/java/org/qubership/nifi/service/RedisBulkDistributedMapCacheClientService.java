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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.types.Expiration;

import static org.springframework.data.redis.connection.ReturnType.MULTI;

@Tags({"redis", "distributed", "cache", "map"})
@CapabilityDescription("")
public class RedisBulkDistributedMapCacheClientService
        extends AbstractControllerService implements BulkDistributedMapCacheClient {

    public static final PropertyDescriptor REDIS_CONNECTION_POOL = new PropertyDescriptor.Builder()
            .name("redis-connection-pool")
            .displayName("Redis Connection Pool")
            .identifiesControllerService(RedisConnectionPool.class)
            .required(false)
            .build();

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
    private Long ttl;

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
    public void onEnabled(final ConfigurationContext context) throws InitializationException {
        this.redisConnectionPool = context.getProperty(REDIS_CONNECTION_POOL)
                .asControllerService(RedisConnectionPool.class);
        this.ttl = context.getProperty(TTL).asTimePeriod(TimeUnit.SECONDS);

        if (ttl == 0) {
            this.ttl = -1L;
        }
    }

    /**
     * This method is called during disable controller services.
     */
    @OnDisabled
    public void shutdown() {
        this.redisConnectionPool = null;
    }

    @Override
    public <K, V> Map<K, V> getAndPutIfAbsent(
            Map<K, V> map,
            final Serializer<K> keySerializer,
            final Serializer<V> valueSerializer,
            final Deserializer<V> valueDeserializer
    ) throws IOException {
        return withConnection(redisConnection -> {
            do {
                String luaScript = "local result = {}\n"
                        + "for i in ipairs(KEYS) do\n"
                        + "  local currentValue = redis.call(\"GET\", KEYS[i])\n"
                        + "  if (not currentValue) then \n"
                        + "    redis.call(\"SET\", KEYS[i], ARGV[i])\n"
                        + "  end\n"
                        + "  result[i] = currentValue \n"
                        + "end \n"
                        + " return result";
                List<K> keys = new ArrayList<>();
                List<V> values = new ArrayList<>();
                map.forEach((key, value) -> {
                    keys.add(key);
                    values.add(value);
                });
                byte[][] serialisedParams = new byte[keys.size() * 2][];
                for (int i = 0; i < keys.size(); i++) {
                    K key = keys.get(i);
                    serialisedParams[i] = serialize(key, keySerializer);
                }
                for (int i = 0; i < values.size(); i++) {
                    V value = values.get(i);
                    serialisedParams[i + keys.size()] = serialize(value, valueSerializer);
                }
                String luaScriptName = redisConnection.scriptingCommands()
                        .scriptLoad(serialize(luaScript, STRING_SERIALIZER));
                List<Object> oldValues = redisConnection.scriptingCommands()
                        .evalSha(luaScriptName, MULTI, keys.size(), serialisedParams);

                // execute the transaction
                Map<K, V> res = new HashMap<>();
                for (int i = 0; i < oldValues.size(); i++) {
                    Object oldValue = oldValues.get(i);
                    res.put(keys.get(i), oldValue == null ? null : valueDeserializer.deserialize((byte[]) oldValue));
                }

                return res;
            } while (isEnabled());
        });
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
            final long numRemoved = redisConnection.del(serialisedKeys);
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
            boolean set = redisConnection.setNX(kv.getKey(), kv.getValue());

            if (ttl != -1L && set) {
                redisConnection.expire(kv.getKey(), ttl);
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
            return redisConnection.exists(k);
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
            redisConnection.set(
                    kv.getKey(),
                    kv.getValue(),
                    Expiration.seconds(ttl),
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
            final byte[] v = redisConnection.get(k);
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

    private <K> byte[] serialize(final K key, final Serializer<K> keySerializer) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        keySerializer.serialize(key, out);
        return out.toByteArray();

    }

}

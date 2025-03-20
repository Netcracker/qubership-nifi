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

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.distributed.cache.client.Deserializer;
import org.apache.nifi.distributed.cache.client.Serializer;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Tags({"BulkDistributedMapCacheClient"})
@CapabilityDescription("BulkDistributedMapCacheClient Service API.")
public interface BulkDistributedMapCacheClient extends ControllerService {

    /**
     * Adds the specified key and value to the cache, if they are not already
     * present, serializing the key and value with the given
     * {@link Serializer}s. If a value already exists in the cache for the given
     * key, the value associated with the key is returned, after being
     * deserialized with the given valueDeserializer.
     *
     * @param <K> type of key
     * @param <V> type of value
     * @param values pair key and value
     * @param keySerializer key serializer
     * @param valueSerializer key serializer
     * @param valueDeserializer value deserializer
     * @return If a value already exists in the cache for the given
     * key, the value associated with the key is returned, after being
     * deserialized with the given {@code valueDeserializer}. If the key does not
     * exist, the key and its value will be added to the cache.
     * @throws IOException if unable to communicate with the remote instance
     */
    <K, V> Map<K, V> getAndPutIfAbsent(
            Map<K, V> values,
            Serializer<K> keySerializer,
            Serializer<V> valueSerializer,
            Deserializer<V> valueDeserializer
    ) throws IOException;

    /**
     * Removes the entry with the given key from the cache, if it is present.
     *
     * @param <K> type of key
     * @param keys list of key to remove
     * @param keySerializer serializer
     * @return <code>true</code> if the entry is removed, <code>false</code> if
     * the key did not exist in the cache
     * @throws IOException if unable to communicate with the remote instance
     */
    <K> long remove(
            List<K> keys,
            Serializer<K> keySerializer
    ) throws IOException;

    /**
     * Adds the specified key and value to the cache, if they are not already
     * present, serializing the key and value with the given
     * {@link Serializer}s.
     *
     * @param <K> type of key
     * @param <V> type of value
     * @param key the key for into the map
     * @param value the value to add to the map if and only if the key is absent
     * @param keySerializer key serializer
     * @param valueSerializer value serializer
     * @return true if the value was added to the cache, false if the value
     * already existed in the cache
     *
     * @throws IOException if unable to communicate with the remote instance
     */
    <K, V> boolean putIfAbsent(
            K key,
            V value,
            Serializer<K> keySerializer,
            Serializer<V> valueSerializer
    ) throws IOException;

    /**
     * Determines if the given value is present in the cache and if so returns
     * <code>true</code>, else returns <code>false</code>.
     *
     * @param <K> type of key
     * @param key key
     * @param keySerializer key serializer
     * @return Determines if the given value is present in the cache and if so returns
     * <code>true</code>, else returns <code>false</code>
     *
     * @throws IOException if unable to communicate with the remote instance
     */
    <K> boolean containsKey(
            K key,
            Serializer<K> keySerializer
    ) throws IOException;

    /**
     * Adds the specified key and value to the cache, overwriting any value that is
     * currently set.
     *
     * @param <K> the key type
     * @param <V> the value type
     * @param key The key to set
     * @param value The value to associate with the given Key
     * @param keySerializer the Serializer that will be used to serialize the key into bytes
     * @param valueSerializer the Serializer that will be used to serialize the value into bytes
     *
     * @throws IOException if unable to communicate with the remote instance
     * @throws NullPointerException if the key or either serializer is null
     */
    <K, V> void put(
            K key,
            V value,
            Serializer<K> keySerializer,
            Serializer<V> valueSerializer
    ) throws IOException;

    /**
     * Returns the value in the cache for the given key, if one exists;
     * otherwise returns <code>null</code>.
     *
     * @param <K> the key type
     * @param <V> the value type
     * @param key the key to lookup in the map
     * @param keySerializer key serializer
     * @param valueDeserializer value serializer
     *
     * @return the value in the cache for the given key, if one exists;
     * otherwise returns <code>null</code>
     * @throws IOException ex
     */
    <K, V> V get(
            K key,
            Serializer<K> keySerializer,
            Deserializer<V> valueDeserializer
    ) throws IOException;
}

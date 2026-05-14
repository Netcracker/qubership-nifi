package org.qubership.nifi;

import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.distributed.cache.client.Deserializer;
import org.apache.nifi.distributed.cache.client.Serializer;
import org.qubership.nifi.service.BulkDistributedMapCacheClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test implementation w/o any concurrency support.
 */
public class TestBulkDistributedMapCacheClientService
    extends AbstractControllerService
    implements BulkDistributedMapCacheClient {

    private static final Map<String, String> CACHE = new HashMap<>();

    private <K> String convertValueToString(K value, Serializer<K> serializer) throws IOException {
        String stringValue = null;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            serializer.serialize(value, out);
            stringValue = out.toString();
        }
        return stringValue;
    }

    @Override
    public <K, V> Map<K, V> getAndPutIfAbsent(Map<K, V> values, Serializer<K> keySerializer,
                                              Serializer<V> valueSerializer,
                                              Deserializer<V> valueDeserializer) throws IOException {
        Map<K, V> processedValues = new HashMap<>();
        for (Map.Entry<K, V> entry : values.entrySet()) {
            String stringKey = null;
            String stringValue = null;
            stringKey = convertValueToString(entry.getKey(), keySerializer);
            stringValue = convertValueToString(entry.getValue(), valueSerializer);
            if (CACHE.containsKey(stringKey)) {
                //get value:
                String oldStringValue = CACHE.get(stringKey);
                V oldValue = null;
                if (oldStringValue != null) {
                    oldValue = valueDeserializer.deserialize(oldStringValue.getBytes(StandardCharsets.UTF_8));
                }
                processedValues.put(entry.getKey(), oldValue);
            } else {
                //put value:
                CACHE.put(stringKey, stringValue);
                processedValues.put(entry.getKey(), null);
            }
        }
        return processedValues;
    }

    @Override
    public <K> long remove(List<K> keys, Serializer<K> keySerializer) throws IOException {
        long counter = 0;
        for (K key : keys) {
            String stringKey = convertValueToString(key, keySerializer);
            if (CACHE.containsKey(stringKey)) {
                counter++;
                CACHE.remove(stringKey);
            }
        }
        return counter;
    }

    @Override
    public <K, V> boolean putIfAbsent(K key, V value,
                                      Serializer<K> keySerializer, Serializer<V> valueSerializer)
            throws IOException {
        String stringKey = convertValueToString(key, keySerializer);
        String stringValue = convertValueToString(value, valueSerializer);
        boolean isPresent = CACHE.containsKey(stringKey);
        CACHE.putIfAbsent(stringKey, stringValue);
        return !isPresent;
    }

    @Override
    public <K> boolean containsKey(K key, Serializer<K> keySerializer) throws IOException {
        String stringKey = convertValueToString(key, keySerializer);
        return CACHE.containsKey(stringKey);
    }

    @Override
    public <K, V> void put(K key, V value, Serializer<K> keySerializer, Serializer<V> valueSerializer)
            throws IOException {
        String stringKey = convertValueToString(key, keySerializer);
        String stringValue = convertValueToString(value, valueSerializer);
        CACHE.put(stringKey, stringValue);
    }

    @Override
    public <K, V> V get(K key, Serializer<K> keySerializer, Deserializer<V> valueDeserializer) throws IOException {
        String stringKey = convertValueToString(key, keySerializer);
        String stringValue = CACHE.get(stringKey);
        return valueDeserializer.deserialize(stringValue.getBytes(StandardCharsets.UTF_8));
    }
}

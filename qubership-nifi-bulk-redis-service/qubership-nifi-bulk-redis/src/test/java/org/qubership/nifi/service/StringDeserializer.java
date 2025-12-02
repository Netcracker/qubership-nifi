package org.qubership.nifi.service;

import org.apache.nifi.distributed.cache.client.Deserializer;
import org.apache.nifi.distributed.cache.client.exception.DeserializationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

class StringDeserializer implements Deserializer<String> {
    @Override
    public String deserialize(byte[] input) throws DeserializationException, IOException {
        return input == null ? null : new String(input, StandardCharsets.UTF_8);
    }
}

package org.qubership.nifi.service;

import org.apache.nifi.distributed.cache.client.Serializer;
import org.apache.nifi.distributed.cache.client.exception.SerializationException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

class StringSerializer implements Serializer<String> {
    @Override
    public void serialize(String s, OutputStream outputStream) throws SerializationException, IOException {
        if (s != null) {
            outputStream.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}

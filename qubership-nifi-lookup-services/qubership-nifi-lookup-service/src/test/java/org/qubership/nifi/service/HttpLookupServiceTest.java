/*
 * Copyright 2020-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.nifi.lookup.LookupFailureException;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.serialization.record.MockRecordParser;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpLookupServiceTest {
    private static TestRunner runner;
    private static MockRecordParser recordReader;
    private static MockHttpLookupService lookupService;

    private static final String JSON_TYPE = "application/json";

    /**
     * Setups test.
     * @throws InitializationException
     */
    @BeforeEach
    public void setup() throws InitializationException {
        recordReader = new MockRecordParser();
        lookupService = new MockHttpLookupService();
        runner = TestRunners.newTestRunner(TestProcessor.class);
        runner.addControllerService("lookupService", lookupService);
        runner.addControllerService("recordReader", recordReader);
        runner.setProperty(lookupService, HttpLookupService.RECORD_READER, "recordReader");
        runner.setProperty("Lookup Service", "lookupService");
    }

    /**
     * Simple lookup test.
     * @throws LookupFailureException
     * @throws InitializationException
     */
    @SuppressWarnings("checkstyle:MagicNumber")
    @Test
    public void testSimpleLookup() throws LookupFailureException, InitializationException {

        runner.setProperty(lookupService, HttpLookupService.URL, "http://${host}:${test.url.port}");
        runner.setProperty(lookupService, "Authorization", "${token.key} ${value}");
        runner.enableControllerService(lookupService);
        runner.enableControllerService(recordReader);

        recordReader.addSchemaField("1", RecordFieldType.STRING);
        recordReader.addSchemaField("2", RecordFieldType.INT);
        recordReader.addSchemaField("3", RecordFieldType.STRING);

        recordReader.addRecord("Test 1", 1, "Test 21");
        recordReader.addRecord("Test 2", 2, "Test 22");
        recordReader.addRecord("Test 3", 3, "Test 23");

        Map<String, String> attributes = new HashMap<>();
        attributes.put("test.url.port", "1234");
        attributes.put("token.key", "Bearer");
        Map<String, Object> coordinates = new HashMap<>();
        coordinates.put("host", "localhost");
        coordinates.put("value", "1234");

        lookupService.response = buildResponse(200);
        Optional<List<Record>> result = lookupService.lookup(coordinates, attributes);

        assertEquals("http://localhost:1234/", lookupService.getUrl());
        assertEquals("Bearer 1234", lookupService.getHeaders().get("Authorization").get(0));

        assertTrue(result.isPresent());
        List<org.apache.nifi.serialization.record.Record> record = result.get();
        assertEquals("Test 1", record.get(0).getAsString("1"));
        assertEquals(Integer.valueOf(1), record.get(0).getAsInt("2"));
        assertEquals("Test 21", record.get(0).getAsString("3"));
    }

    /**
     * Tests for invalid response.
     * @throws LookupFailureException
     */
    @SuppressWarnings("checkstyle:MagicNumber")
    @Test
    public void testInvalidResponse() throws LookupFailureException {

        runner.setProperty(lookupService, HttpLookupService.URL, "http://${host}:8080");
        runner.enableControllerService(lookupService);
        runner.enableControllerService(recordReader);


        lookupService.response = buildResponse(404);

        Map<String, Object> coordinates = new HashMap<>();
        coordinates.put("host", "localhost");

        assertThrows(LookupFailureException.class, () -> lookupService.lookup(coordinates));
    }

    private Response buildResponse(Integer code) {
        return new Response.Builder()
                .code(code)
                .body(
                    ResponseBody.create(MediaType.parse(HttpLookupServiceTest.JSON_TYPE), "{}")
                )
                .message("Test")
                .protocol(Protocol.HTTP_1_1)
                .request(new Request.Builder().url("http://localhost:8080").get().build())
                .build();
    }
}

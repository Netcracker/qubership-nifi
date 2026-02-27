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

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.nifi.annotation.behavior.DynamicProperties;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.components.Validator;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.lookup.LookupFailureException;
import org.apache.nifi.lookup.LookupService;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.MalformedRecordException;
import org.apache.nifi.serialization.RecordReader;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.record.Record;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Controller service to lookup up values within some service with HTTP API.
 */
@Tags({"rest", "lookup", "json", "http"})
@CapabilityDescription(
        "Sends HTTP GET request with specified URL and headers "
        + "(set up via dynamic PROPERTY_DESCRIPTORS) to look up values. \n"
        + "If the response status code is 2xx, the response body is parsed with Record Reader "
        + "and returned as array of records. \n"
        + "Otherwise (status code other than 2xx), the controller service throws exception and logs the response body.")
@DynamicProperties({
        @DynamicProperty(name = "*", value = "*",
                description = "All dynamic PROPERTY_DESCRIPTORS are added as HTTP headers with the "
                + "name as the header name and the value as the header value.",
                expressionLanguageScope = ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
})
public class HttpLookupService extends AbstractControllerService
        implements LookupService<List<Record>> {
    /**
     * URL property descriptor.
     */
    static final PropertyDescriptor URL = new PropertyDescriptor.Builder()
            .name("http-lookup-url")
            .displayName("URL")
            .description("The URL to send request to. Expression language is supported "
                    + "and evaluated against both the lookup key-value pairs and FlowFile attributes.")
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .build();

    /**
     * Record Reader property descriptor.
     */
    static final PropertyDescriptor RECORD_READER = new PropertyDescriptor.Builder()
            .name("http-lookup-record-reader")
            .displayName("Record Reader")
            .description("The record reader to use for loading response body and handling it as a record set.")
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .identifiesControllerService(RecordReaderFactory.class)
            .required(true)
            .build();

    /**
     * Connection Timeout property descriptor.
     */
    public static final PropertyDescriptor PROP_CONNECT_TIMEOUT = new PropertyDescriptor.Builder()
            .name("http-lookup-connection-timeout")
            .displayName("Connection Timeout")
            .description("Max wait time for connection to remote service.")
            .required(true)
            .defaultValue("5 secs")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    /**
     * Read Timeout property descriptor.
     */
    public static final PropertyDescriptor PROP_READ_TIMEOUT = new PropertyDescriptor.Builder()
            .name("http-lookup-read-timeout")
            .displayName("Read Timeout")
            .description("Max wait time for response from remote service.")
            .required(true)
            .defaultValue("15 secs")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    private static final List<PropertyDescriptor> PROPERTY_DESCRIPTORS;

    private volatile RecordReaderFactory readerFactory;
    private volatile OkHttpClient client;
    private volatile Map<String, PropertyValue> headers;
    private volatile PropertyValue urlTemplate;

    private static final Set<Integer> SUCCESS_CODES = Set.of(200, 201, 202, 203, 204, 205, 206, 207, 208, 226);

    static {
        PROPERTY_DESCRIPTORS = List.of(URL, RECORD_READER, PROP_CONNECT_TIMEOUT, PROP_READ_TIMEOUT);
    }

    /**
     * Returns a List of all PropertyDescriptors that this component supports.
     * @return PropertyDescriptor objects this component currently supports
     */
    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTY_DESCRIPTORS;
    }

    /**
     * Returns a dynamic PropertyDescriptors type that this component supports.
     * @param propertyDescriptorName name of property descriptor
     * @return PropertyDescriptor object this component currently supports
     */
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .name(propertyDescriptorName)
                .displayName(propertyDescriptorName)
                .addValidator(Validator.VALID)
                .dynamic(true)
                .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
                .build();
    }

    /**
     * Initializes controller service before it's started.
     * @param context configuration context
     */
    @OnEnabled
    public void onEnabled(final ConfigurationContext context) {
        readerFactory = context.getProperty(RECORD_READER).asControllerService(RecordReaderFactory.class);

        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        builder.connectTimeout((context.getProperty(PROP_CONNECT_TIMEOUT).
                asTimePeriod(TimeUnit.MILLISECONDS).intValue()), TimeUnit.MILLISECONDS);
        builder.readTimeout(context.getProperty(PROP_READ_TIMEOUT).
                asTimePeriod(TimeUnit.MILLISECONDS).intValue(), TimeUnit.MILLISECONDS);

        client = builder.build();

        buildHeaders(context);

        urlTemplate = context.getProperty(URL);
    }

    private void buildHeaders(ConfigurationContext context) {
        headers = new HashMap<>();
        for (PropertyDescriptor descriptor : context.getProperties().keySet()) {
            if (descriptor.isDynamic()) {
                headers.put(
                        descriptor.getDisplayName(),
                        context.getProperty(descriptor)
                );
            }
        }
    }

    /**
     * Looks up Record list using specified coordinates.
     * @param coordinates a map with coordinates that should be used for lookup
     * @return the list of Records generated as lookup result
     * @throws LookupFailureException
     */
    @Override
    public Optional<List<Record>> lookup(Map<String, Object> coordinates)
            throws LookupFailureException {
        return lookup(coordinates, null);
    }

    /**
     *
     * Looks up Record list using specified coordinates.
     * @param coordinates a map with coordinates that should be used for lookup
     * @param context a map with additional lookup context
     * @return the list of Records generated as lookup result
     * @throws LookupFailureException
     */
    @Override
    public Optional<List<org.apache.nifi.serialization.record.Record>> lookup(Map<String, Object> coordinates,
                                                                              Map<String, String> context)
            throws LookupFailureException {

        final Map<String, String> attributesAndCoordinates = mergeMaps(coordinates, context);

        final String endpoint = urlTemplate.evaluateAttributeExpressions(attributesAndCoordinates).getValue();

        Request request = buildRequest(endpoint, attributesAndCoordinates);
        try (Response response = executeRequest(request)) {

            if (!SUCCESS_CODES.contains(response.code())) {
                getLogger().error("Response code {} was returned. Response body: {}",
                        new Object[]{response.code(), Objects.requireNonNull(response.body()).string()});
                throw new LookupFailureException(
                        String.format("Response code %s was returned for coordinate %s", response.code(), coordinates)
                );
            }

            final ResponseBody responseBody = response.body();

            final List<org.apache.nifi.serialization.record.Record> records;
            try (InputStream is = responseBody.byteStream();
                InputStream bufferedIn = new BufferedInputStream(is)) {
                records = handleResponse(bufferedIn, responseBody.contentLength(), context);
            }

            return Optional.of(records);
        } catch (Exception e) {
            getLogger().error("Could not execute lookup.", e);
            throw new LookupFailureException(e);
        }
    }

    /**
     * Get value type for this lookup service.
     * @return Record class
     */
    @Override
    public Class<?> getValueType() {
        return org.apache.nifi.serialization.record.Record.class;
    }

    /**
     * Get set of required keys for this lookup service.
     * @return set of required keys
     */
    @Override
    public Set<String> getRequiredKeys() {
        return Collections.emptySet();
    }

    private List<org.apache.nifi.serialization.record.Record> handleResponse(InputStream is,
                                                                             long inputLength,
                                                                             Map<String, String> context)
            throws SchemaNotFoundException, MalformedRecordException, IOException {
        try (RecordReader reader = readerFactory.createRecordReader(context, is, inputLength, getLogger())) {
            List<org.apache.nifi.serialization.record.Record> records = new ArrayList<>();
            org.apache.nifi.serialization.record.Record record;

            while ((record = reader.nextRecord()) != null) {
                records.add(record);
            }
            return records;
        }
    }

    private Request buildRequest(final String endpoint, final Map<String, String> attributesAndCoordinates) {
        Request.Builder request = new Request.Builder()
                .url(endpoint);
        request = request.get();

        if (headers != null) {
            for (Map.Entry<String, PropertyValue> header : headers.entrySet()) {
                request = request.addHeader(header.getKey(), header.getValue().
                        evaluateAttributeExpressions(attributesAndCoordinates).getValue());
            }
        }

        return request.build();
    }

    /**
     * Executes HTTP request.
     * @param request HTTP request
     * @return HTTP response
     * @throws IOException
     */
    protected Response executeRequest(Request request) throws IOException {
        return client.newCall(request).execute();
    }

    /**
     * Merges maps with coordinates and context into single map.
     * @param coordinates lookup coordinates
     * @param context lookup context
     * @return map with both coordinates and lookup context
     */
    protected Map<String, String> mergeMaps(Map<String, Object> coordinates, Map<String, String> context) {
        Map<String, String> converted = coordinates.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().toString()
                ));

        Map<String, String> merged = new HashMap<>(converted);
        if (context != null) {
            merged.putAll(context);
        }
        return merged;
    }
}

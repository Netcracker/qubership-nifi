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

import org.qubership.nifi.service.recordSink.MetricCompositeKey;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.annotation.lifecycle.OnShutdown;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.components.PropertyDescriptor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Counter;
import org.apache.nifi.serialization.record.DataType;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.type.RecordDataType;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.apache.nifi.processor.exception.ProcessException;
import org.eclipse.jetty.server.Server;
import org.apache.nifi.serialization.WriteResult;
import org.apache.nifi.record.sink.RecordSinkService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.RecordSet;


import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.util.StandardValidators;
import org.eclipse.jetty.servlet.ServletHolder;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.apache.nifi.serialization.record.RecordFieldType.DOUBLE;

/**
 * Controller Services which allows to expose metrics to Prometheus.
 */
@Tags({"record", "send", "write", "prometheus"})
public class QubershipPrometheusRecordSink extends AbstractControllerService implements RecordSinkService {

    private Server prometheusServer;
    /**
     * Prometheus Meter Registry to use.
     */
    public PrometheusMeterRegistry meterRegistry;
    private static final List<PropertyDescriptor> PROPERTIES;
    private int metricsEndpointPort;
    private boolean clearMetrics;
    /**
     * K8s namespace.
     */
    protected String namespace;
    /**
     * NiFi hostname.
     */
    protected String hostname;
    private String instance;

    private Map<MetricCompositeKey, Number> metricSet = new ConcurrentHashMap<>();

    /**
     * Metrics endpoint port property descriptor.
     */
    public static final PropertyDescriptor METRICS_ENDPOINT_PORT = new PropertyDescriptor.Builder()
            .name("prometheus-sink-metrics-endpoint-port")
            .displayName("Prometheus Metrics Endpoint Port")
            .description("The Port where prometheus metrics can be accessed")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .defaultValue("9092")
            .addValidator(StandardValidators.INTEGER_VALIDATOR)
            .build();

    /**
     * Instance ID property descriptor.
     */
    public static final PropertyDescriptor INSTANCE_ID = new PropertyDescriptor.Builder()
            .name("prometheus-sink-instance-id")
            .displayName("Instance ID")
            .description("Id of this NiFi instance to be included in the metrics sent to Prometheus")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .defaultValue("${hostname(true)}_${NAMESPACE}")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /**
     * Clear metrics property descriptor.
     */
    public static final PropertyDescriptor CLEAR_METRICS = new PropertyDescriptor.Builder()
            .name("prometheus-sink-clear-metrics")
            .displayName("Clear Metrics on Disable")
            .description("If set to Yes, all metrics stored in the controller service are cleared, "
                    + "when the controller service is disabled. By default, metrics are not cleared.")
            .defaultValue("No")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .allowableValues("Yes", "No")
            .required(true)
            .build();

    static {
        List<PropertyDescriptor> props = new ArrayList<>();
        props.add(METRICS_ENDPOINT_PORT);
        props.add(INSTANCE_ID);
        props.add(CLEAR_METRICS);
        PROPERTIES = Collections.unmodifiableList(props);
    }

    /**
     * Returns a List of all PropertyDescriptors that this component supports.
     * Returns:
     * PropertyDescriptor objects this component currently supports
     *
     */
    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    /**
     * Start jetty server.
     */
    protected void startServer() {
        try {
            prometheusServer = new Server(metricsEndpointPort);
            ServletContextHandler servletContextHandler = new ServletContextHandler();
            servletContextHandler.setContextPath("/");
            servletContextHandler.addServlet(new ServletHolder(new PrometheusServlet()), "/metrics");
            prometheusServer.setHandler(servletContextHandler);
            prometheusServer.start();
        } catch (Exception e) {
            getLogger().error("Error while starting Jetty server {}", e);
            throw new ProcessException("Error while starting Jetty server {}", e);
        }
    }

    /**
     * Gets namespace used to run nifi service.
     * @return namespace
     */
    protected String getNamespace() {
        return System.getenv("NAMESPACE");
    }

    /**
     * Gets hostname used to run nifi service.
     * @return hostname
     */
    protected String getHostname() {
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException ex) {
            getLogger().warn("Error while getting host name {}", new Object[]{ex.getLocalizedMessage()}, ex);
            return "cloud-data-migration-nifi";
        }
    }

    /**
     * Initializes reporting task before it's started.
     * @param context reporting context
     */
    @OnEnabled
    public void onScheduled(final ConfigurationContext context) {
        meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metricsEndpointPort = context.getProperty(METRICS_ENDPOINT_PORT).asInteger();
        clearMetrics = context.getProperty(CLEAR_METRICS).getValue().equals("Yes");
        namespace = getNamespace();
        hostname = getHostname();
        instance = namespace + "_" + hostname;
        startServer();
    }

    /**
     * Stops jetty server and releases all resources.
     */
    @OnDisabled
    public void onStopped() throws Exception {
        if (clearMetrics) {
            meterRegistry.clear();
            metricSet.clear();
        }
        if (prometheusServer != null) {
            getLogger().info("Jetty server is shutting down");
            prometheusServer.stop();
        }
    }

    /**
     * Stops jetty server and releases all resources.
     */
    @OnShutdown
    public void onShutDown() throws Exception {
        if (prometheusServer != null) {
            getLogger().info("Jetty server is shutting down");
            prometheusServer.stop();
        }
    }

    /**
     * Sends the record set to the RecordSinkService.
     *
     * @param recordSet The RecordSet to transmit
     * @param map Attributes associated with the RecordSet
     * @param b Whether to transmit empty record sets
     * @return WriteResult object containing the number of records transmitted,
     * as well as any metadata in the form of attributes
     * @throws IOException
     */
    @Override
    public WriteResult sendData(RecordSet recordSet, Map<String, String> map, boolean b) throws IOException {
        String[] labelNames;
        String[] staticLabelNames = {"namespace", "hostname", "instance"};
        WriteResult writeResult = null;
        RecordSchema recordSchema = recordSet.getSchema();

        labelNames = Stream.concat(recordSchema.getFields().stream().filter(
                        (f) -> isLabel(f.getDataType().getFieldType())).map(RecordField::getFieldName)
                .sorted(), Arrays.stream(staticLabelNames)).toArray(String[]::new);

        int recordCount = 0;
        org.apache.nifi.serialization.record.Record r;
        while ((r = recordSet.next()) != null) {
            final org.apache.nifi.serialization.record.Record record = r;
            String[] labelValues;

            //labelValues = Arrays.stream(labelNames).map(label ->
            // record.getAsString(label) != null ? record.getAsString(label) : "" ).toArray(String[]::new);
            labelValues = Arrays.stream(labelNames).map(label -> {
                String value = record.getAsString(label);
                if (value != null) {
                    return value; // If value is not null, return it
                } else if ("namespace".equals(label)) {
                    return namespace;
                } else if ("hostname".equals(label)) {
                    return hostname;
                } else if ("instance".equals(label)) {
                    return instance;
                } else {
                    return ""; // Default fallback for all other cases
                }
            }).toArray(String[]::new);

            recordSchema.getFields().stream().filter((field) ->
                    isNumeric(field.getDataType().getFieldType())).forEach(recordField -> {
                MetricCompositeKey metricCompositeKey = new MetricCompositeKey(recordField.getFieldName(),
                        labelNames, labelValues);
                Number num = convertNum(record, recordField.getFieldName());
                if (num != null) {
                    metricSet.put(metricCompositeKey, convertNum(record, recordField.getFieldName()));
                    Optional<DataType> dataType = record.getSchema().getDataType(metricCompositeKey.getMetricName());
                    if (dataType.isPresent()) {
                        Gauge.builder(metricCompositeKey.getMetricName(), () -> {
                                    Number value = getMetricValue(metricCompositeKey);
                                    return value == null ? 0 : value;
                                })
                                .tags(IntStream.range(0, labelValues.length).filter(i ->
                                        labelValues[i] != null).mapToObj(i ->
                                        Tag.of(labelNames[i], labelValues[i])).collect(Collectors.toList()))
                                .register(meterRegistry);
                    }
                }
            });
            recordSchema.getFields().stream().filter((recordField -> isRecord(recordField))).forEach(recordField -> {
                String metricName = recordField.getFieldName();
                Record metricRecord = (Record) record.getValue(recordField);
                Number value = convertNum(metricRecord, "value");
                String type = metricRecord.getAsString("type");
                if (value != null) {
                    Optional<DataType> dataType = record.getSchema().getDataType(metricName);
                    if (dataType.isPresent()) {
                        List<Tag> tagsList = IntStream.range(0, labelValues.length)
                                .filter(i -> labelValues[i] != null)
                                .mapToObj(i -> Tag.of(labelNames[i], labelValues[i]))
                                .collect(Collectors.toList());
                        if ("Counter".equals(type)) {
                            Counter counter = createCounter(metricName, tagsList);
                            counter.increment(value.doubleValue());
                        } else if ("Summary".equals(type)) {
                            DistributionSummary distributionSummary = createSummary(metricName, metricRecord, tagsList);
                            distributionSummary.record(value.doubleValue());
                        }
                    }
                }
            });
            recordCount++;
        }
        map.put("record.count", Integer.toString(recordCount));
        writeResult = WriteResult.of(recordCount, map);
        return writeResult;
    }

    private synchronized Counter createCounter(String metricName, List<Tag> tagsList) {
        Counter counter = Counter.builder(metricName)
                    .tags(tagsList)
                    .register(meterRegistry);

        return counter;
    }

    private synchronized DistributionSummary createSummary(String metricName, Record metricRecord, List<Tag> tagsList) {
        Duration statisticExpiry = null;
        double[] publishPercentiles = null;
        Object[] publishPercentilesObject = metricRecord.getAsArray("publishPercentiles");
        if (publishPercentilesObject != null) {
            publishPercentiles = new double[publishPercentilesObject.length];

            for (int i = 0; i < publishPercentilesObject.length; i++) {
                if (publishPercentilesObject[i] instanceof Number) {
                    publishPercentiles[i] = ((Number) publishPercentilesObject[i]).doubleValue();
                }
            }
        }

        if (StringUtils.isNotEmpty(metricRecord.getAsString("statisticExpiry"))) {
            statisticExpiry = Duration.parse(metricRecord.getAsString("statisticExpiry"));
        }

        DistributionSummary distributionSummary = DistributionSummary.builder(metricName)
                    .tags(tagsList)
                    .distributionStatisticBufferLength(metricRecord.getAsInt("statisticBufferLength"))
                    .distributionStatisticExpiry(statisticExpiry)
                    .publishPercentiles(publishPercentiles)
                    .register(meterRegistry);

        return distributionSummary;
    }

    private Number getMetricValue(MetricCompositeKey metricCompositeKey) {
        return metricSet.get(metricCompositeKey);
    }

    private Number convertNum(org.apache.nifi.serialization.record.Record record, String name) {
        Number num = null;

        switch (record.getSchema().getDataType(name).get().getFieldType()) {
            case INT:
                num = record.getAsInt(name);
                break;
            case LONG:
                num = record.getAsLong(name);
                break;
            case FLOAT:
                num = record.getAsFloat(name);
                break;
            case DOUBLE:
                num = record.getAsDouble(name);
                break;
            case DECIMAL:
                if (record.getSchema().getDataType(name).get().getFieldType() == DOUBLE) {
                    num = record.getAsDouble(name);
                } else {
                    num = record.getAsFloat(name);
                }
                break;
            case CHOICE:
                num = NumberUtils.createNumber(record.getAsString(name));
                break;
            case BOOLEAN:
                if (record.getAsBoolean(name)) {
                    num = 1;
                } else {
                    num = 0;
                }
                break;
        }
        return num;
    }

    private boolean isLabel(RecordFieldType dataType) {
        return RecordFieldType.STRING.equals(dataType)
                || RecordFieldType.CHAR.equals(dataType);
    }

    private boolean isNumeric(RecordFieldType dataType) {
        // Numeric fields are metrics
        return RecordFieldType.INT.equals(dataType)
                || RecordFieldType.SHORT.equals(dataType)
                || RecordFieldType.LONG.equals(dataType)
                || RecordFieldType.BIGINT.equals(dataType)
                || RecordFieldType.FLOAT.equals(dataType)
                || RecordFieldType.DOUBLE.equals(dataType)
                || RecordFieldType.DECIMAL.equals(dataType)
                || RecordFieldType.BOOLEAN.equals(dataType)
                || RecordFieldType.CHOICE.equals(dataType);
    }

    private boolean isRecord(RecordField field) {
        if (RecordFieldType.RECORD.equals(field.getDataType().getFieldType())) {
            RecordSchema schema = ((RecordDataType) field.getDataType()).getChildSchema();
            return schema.getField("type").isPresent() && schema.getField("value").isPresent();
        }
        return false;
    }

    private final class PrometheusServlet extends HttpServlet {

        @Override
        protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType(TextFormat.CONTENT_TYPE_004);

            try (Writer writer = resp.getWriter()) {
                TextFormat.write004(writer, meterRegistry.getPrometheusRegistry().metricFamilySamples());
                writer.flush();
            } catch (IOException e) {
                getLogger().error("Error while scraping metrics {}", e);
                throw new ProcessException("Error while scraping metrics {}", e);
            }
        }
    }

}

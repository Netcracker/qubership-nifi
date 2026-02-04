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

package org.qubership.nifi.reporting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.nifi.annotation.configuration.DefaultSchedule;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.DeprecationNotice;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.status.ConnectionStatus;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.controller.status.ProcessorStatus;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.scheduling.SchedulingStrategy;

@Tags({"reporting", "influxdb", "metrics"})
@CapabilityDescription("Sends components (Processors, Connections) metrics to InfluxDB.")
@DefaultSchedule(strategy = SchedulingStrategy.TIMER_DRIVEN, period = "15 sec")
@Deprecated(since = "2.4.0", forRemoval = true)
@DeprecationNotice(alternatives = ComponentPrometheusReportingTask.class,
        reason = "This component is deprecated and may be removed in release 2.7.0")
public class ComponentMetricsReportingTask
        extends AbstractInfluxDbReportingTask {
    /**
     * Processor time threshold property descriptor.
     */
    public static final PropertyDescriptor PROCESSOR_TIME_THRESHOLD = new PropertyDescriptor.Builder()
            .name("processor-time-threshold")
            .displayName("Processor time threshold")
            .description("Minimal processing time for processor to be included in monitoring.\n"
                    + "Limits data volume collected in InfluxDB.")
            .defaultValue("150 sec")
            .required(true)
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    /**
     * Connection queue threshold property descriptor.
     */
    public static final PropertyDescriptor CONNECTION_QUEUE_THRESHOLD = new PropertyDescriptor.Builder()
            .name("connection-queue-threshold")
            .displayName("Connection queue threshold")
            .description("Minimal connection usage % relative to backPressureObjectThreshold.\n"
                    + "Limits data volume collected in InfluxDB.")
            .defaultValue("80")
            .required(true)
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();
    //
    private long processorTimeThreshold;

    /**
     * Sets processor run time threshold.
     * @param processorTimeThresholdValue new processor run time threshold.
     */
    public void setProcessorTimeThreshold(long processorTimeThresholdValue) {
        this.processorTimeThreshold = processorTimeThresholdValue;
    }

    /**
     * Sets connection queue size threshold.
     * @param connectionQueueThresholdValue new processor time threshold.
     */
    public void setConnectionQueueThreshold(double connectionQueueThresholdValue) {
        this.connectionQueueThreshold = connectionQueueThresholdValue;
    }
    private double connectionQueueThreshold;

    /**
     * Initializes list of property descriptors supported by this reporting task.
     * @return list of property descriptors
     */
    @Override
    protected List<PropertyDescriptor> initProperties() {
        List<PropertyDescriptor> allProps = super.initProperties();
        allProps.add(PROCESSOR_TIME_THRESHOLD);
        allProps.add(CONNECTION_QUEUE_THRESHOLD);
        return allProps;
    }

    /**
     * Initializes reporting task before it's started.
     * @param context reporting context
     */
    @Override
    public void onScheduled(ConfigurationContext context) {
        super.onScheduled(context);
        processorTimeThreshold = context.getProperty(PROCESSOR_TIME_THRESHOLD).asTimePeriod(TimeUnit.NANOSECONDS);
        connectionQueueThreshold = context.getProperty(CONNECTION_QUEUE_THRESHOLD).asDouble() / 100;
    }



    private void getAllEligibleStatuses(
            ProcessGroupStatus pgStatus,
            List<ConnectionStatus> connections,
            List<ProcessorStatus> processors
    ) {
        if (pgStatus == null) {
            return;
        }
        //add connections:
        Collection<ConnectionStatus> newConnections = pgStatus.getConnectionStatus();
        if (newConnections != null) {
            newConnections.stream().filter((s) ->
                    (s.getQueuedCount() > s.getBackPressureObjectThreshold() * connectionQueueThreshold)).
                forEach((s) -> {
                    connections.add(s);
            });
        }
        //add processors:
        Collection<ProcessorStatus> newProcessors = pgStatus.getProcessorStatus();
        if (newProcessors != null) {
            newProcessors.stream().filter((s) ->
                    (s.getProcessingNanos() > processorTimeThreshold)).
                forEach((s) -> {
                    processors.add(s);
            });
        }
        //process children Process Groups, if any:
        Collection<ProcessGroupStatus> childPg = pgStatus.getProcessGroupStatus();
        if (childPg != null) {
            childPg.forEach((pg) -> {
                getAllEligibleStatuses(pg, connections, processors);
            });
        }
    }

    private void reportConnectionMetrics(long reportTime, StringBuilder res, ConnectionStatus st) {
        res.append("nifi_connections_monitoring,namespace=").append(escapeTagValue(namespace))
           .append(",connection_uuid=").append(escapeTagValue(st.getId()))
           .append(",hostname=").append(hostname)
           .append(",sourceId=").append(escapeTagValue(st.getSourceId()))
           .append(",destinationId=").append(escapeTagValue(st.getDestinationId()))
           .append(" name=\"").append(escapeFieldValue(st.getName()))
           .append("\",sourceName=\"").append(escapeFieldValue(st.getSourceName()))
           .append("\",destinationName=\"").append(escapeFieldValue(st.getDestinationName()))
           .append("\",queuedCount=").append(st.getQueuedCount())
           .append(",queuedBytes=").append(st.getQueuedBytes())
           .append(",backPressureObjectThreshold=").append(st.getBackPressureObjectThreshold())
           .append(",backPressureBytesThreshold=").append(st.getBackPressureBytesThreshold())
           .append(" ").append(reportTime).append("\n");
    }

    private void reportProcessorMetrics(long reportTime, StringBuilder res, ProcessorStatus st) {
        res.append("nifi_processors_monitoring,namespace=").append(escapeTagValue(namespace))
           .append(",processor_uuid=").append(escapeTagValue(st.getId()))
           .append(",hostname=").append(hostname)
           .append(",full_name=").append(escapeTagValue(st.getName())).append("(").append(escapeTagValue(st.getId()))
           .append(") name=\"").append(escapeFieldValue(st.getName()))
           .append("\",processingNanos=").append(st.getProcessingNanos())
           .append(",invocations=").append(st.getInvocations())
           .append(",inputCount=").append(st.getInputCount())
           .append(",outputCount=").append(st.getOutputCount())
           .append(",bytesRead=").append(st.getBytesRead())
           .append(",bytesWritten=").append(st.getBytesWritten())
           .append(",bytesReceived=").append(st.getBytesReceived())
           .append(",bytesSent=").append(st.getBytesSent())
           .append(" ").append(reportTime).append("\n");
    }

    /**
     * Creating a message for Influx.
     *
     * @param context
     * @return
     */
    @Override
    public String createInfluxMessage(ReportingContext context) {
        ProcessGroupStatus controllerStatus = context.getEventAccess().getControllerStatus();
        List<ConnectionStatus> connections = new ArrayList<>();
        List<ProcessorStatus> processors = new ArrayList<>();
        //start with root process group:
        getAllEligibleStatuses(controllerStatus, connections, processors);

        StringBuilder result = new StringBuilder();
        Instant now = Instant.now();
        long reportTime = TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();
        //prepare connections data:
        connections.forEach((s) -> {
            reportConnectionMetrics(reportTime, result, s);
        });
        //prepare processors data:
        processors.forEach((s) -> {
            reportProcessorMetrics(reportTime, result, s);
        });

        return result.toString().trim();
    }
}

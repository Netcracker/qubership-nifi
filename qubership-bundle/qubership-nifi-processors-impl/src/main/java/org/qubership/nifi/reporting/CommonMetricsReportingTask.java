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

import org.apache.nifi.annotation.configuration.DefaultSchedule;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import com.yammer.metrics.core.VirtualMachineMetrics;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.scheduling.SchedulingStrategy;


import java.lang.Thread.State;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Tags({"reporting", "influxdb", "metrics"})
@CapabilityDescription("Sends Nifi metrics to InfluxDB.")
@DefaultSchedule(strategy = SchedulingStrategy.TIMER_DRIVEN, period = "15 sec")
public class CommonMetricsReportingTask extends AbstractInfluxDbReportingTask {

    private volatile VirtualMachineMetrics vmMetrics;

    /**
     * Initializes list of property descriptors supported by this reporting task.
     * @return list of property descriptors
     */
    @Override
    protected List<PropertyDescriptor> initProperties() {
        List<PropertyDescriptor> allProps = super.initProperties();
        return allProps;
    }

    /**
     * Initializes reporting task before it's started.
     * @param context reporting context
     */
    @Override
    public void onScheduled(ConfigurationContext context) {
        super.onScheduled(context);
    }


    /**
     * Collecting Nifi Common Metrics for sending to monitoring.
     *
     * @param reportTime
     * @param result
     * @param vmMetricsValue
     * @param controllerStatus
     */
    public void reportNifiCommonMetricks(
            long reportTime,
            StringBuilder result,
            VirtualMachineMetrics vmMetricsValue,
            ProcessGroupStatus controllerStatus
    ) {
        result.append("nifi_common_metrics,namespace=").append(escapeTagValue(namespace))
                .append(",hostname=").append(hostname)
                .append(" activeThreadCount=").append(controllerStatus.getActiveThreadCount())
                .append(",heapMax=").append(vmMetricsValue.heapMax() / 1024)
                .append(",heapUsed=").append(vmMetricsValue.heapUsage() * vmMetricsValue.heapMax() / 1024)
                .append(",heapCommited=").append(vmMetricsValue.heapCommitted() / 1024)
                .append(",uptime=").append(vmMetricsValue.uptime())
                .append(",jvmDaemonThreadCount=").append(vmMetricsValue.daemonThreadCount())
                .append(",jvmThreadTotalCount=").append(vmMetricsValue.threadCount())
                .append(" ").append(reportTime).append("\n");
    }

    /**
     * Collecting NiFi Threads Metrics for sending to monitoring.
     *
     * @param reportTime
     * @param result
     * @param state
     * @param threadCount
     */
    private void reportNiFiThreadsMetric(long reportTime, StringBuilder result, State state, double threadCount) {
        result.append("nifi_thread_metrics,namespace=").append(escapeTagValue(namespace))
            .append(",hostname=").append(hostname)
            .append(",threadState=").append(state.toString())
            .append(" threadCount=").append(threadCount)
            .append(" ").append(reportTime).append("\n");
    }

    /**
     * Collecting Nifi Thread Metrics for sending to monitoring.
     *
     * @param reportTime
     * @param result
     * @param vmMetricsValue
     */
    public void reportNifiThreadMetrics(long reportTime, StringBuilder result, VirtualMachineMetrics vmMetricsValue) {
        Map<State, Double> map = vmMetricsValue.threadStatePercentages();
        for (Map.Entry<State, Double> entry : map.entrySet()) {
            double normalizedValue = (entry.getValue() == null ? 0 : entry.getValue() * vmMetricsValue.threadCount());
            reportNiFiThreadsMetric(reportTime, result, entry.getKey(), normalizedValue);
        }
    }

    /**
     * Collecting Java Gc Metrics for sending to monitoring.
     *
     * @param reportTime
     * @param result
     * @param vmMetricsValue
     */
    public void reportGcMetrics(long reportTime, StringBuilder result, VirtualMachineMetrics vmMetricsValue) {

        Map<String, VirtualMachineMetrics.GarbageCollectorStats> map = vmMetricsValue.garbageCollectors();

        for (Map.Entry<String, VirtualMachineMetrics.GarbageCollectorStats> entry : map.entrySet()) {
            result.append("nifi_gc_metrics,namespace=").append(escapeTagValue(namespace))
                    .append(",hostname=").append(hostname)
                    .append(",gcStatType=").append(escapeTagValue(entry.getKey()))
                    .append("  gcRuns=").append(entry.getValue().getRuns())
                    .append(",gcTime=").append(entry.getValue().getTime(TimeUnit.MILLISECONDS))
                    .append(" ").append(reportTime).append("\n");
        }
    }

    /**
     * Creating a message for Influx.
     *
     * @param context
     * @return Influx message
     */
    @Override
    public String createInfluxMessage(ReportingContext context) {

        ProcessGroupStatus controllerStatus = context.getEventAccess().getControllerStatus();
        vmMetrics = VirtualMachineMetrics.getInstance();

        StringBuilder result = new StringBuilder();
        Instant now = Instant.now();
        long reportTime = TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();

        //prepare Nifi Common Metrics data
        reportNifiCommonMetricks(reportTime, result, vmMetrics, controllerStatus);
        //prepare Nifi Thread Metrics
        reportNifiThreadMetrics(reportTime, result, vmMetrics);
        //prepare Nifi Gc Metrics
        reportGcMetrics(reportTime, result, vmMetrics);

        return result.toString().trim();
    }
}

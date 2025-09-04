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
public class CommonMetricsReportingTask extends AbstractInfluxDbReportingTask{

    private volatile VirtualMachineMetrics vmMetrics;


    @Override
    protected List<PropertyDescriptor> initProperties() {
        List<PropertyDescriptor> allProps = super.initProperties();
        return allProps;
    }

    @Override
    public void onScheduled(ConfigurationContext context) {
        super.onScheduled(context);
    }



    public void reportNifiCommonMetricks(long reportTime,  StringBuilder result, VirtualMachineMetrics vmMetrics, ProcessGroupStatus controllerStatus){
        result.append("nifi_common_metrics,namespace=").append(escapeTagValue(namespace))
                .append(",hostname=").append(hostname)
                .append(" activeThreadCount=").append(controllerStatus.getActiveThreadCount())
                .append(",heapMax=").append(vmMetrics.heapMax() / 1024)
                .append(",heapUsed=").append(vmMetrics.heapUsage()*vmMetrics.heapMax() / 1024)
                .append(",heapCommited=").append(vmMetrics.heapCommitted() / 1024)
                .append(",uptime=").append(vmMetrics.uptime())
                .append(",jvmDaemonThreadCount=").append(vmMetrics.daemonThreadCount())
                .append(",jvmThreadTotalCount=").append(vmMetrics.threadCount())
                .append(" ").append(reportTime).append("\n");
    }

    private void reportNiFiThreadsMetric(long reportTime, StringBuilder result, State state, double threadCount) {
        result.append("nifi_thread_metrics,namespace=").append(escapeTagValue(namespace))
            .append(",hostname=").append(hostname)
            .append(",threadState=").append(state.toString())
            .append(" threadCount=").append(threadCount)
            .append(" ").append(reportTime).append("\n");
    }

    public void reportNifiThreadMetrics(long reportTime, StringBuilder result, VirtualMachineMetrics vmMetrics) {
        Map<State, Double> map = vmMetrics.threadStatePercentages();
        for (Map.Entry<State, Double> entry : map.entrySet()) {
            double normalizedValue = (entry.getValue() == null ? 0 : entry.getValue()*vmMetrics.threadCount());
            reportNiFiThreadsMetric(reportTime, result, entry.getKey(), normalizedValue);
        }
    }

    public void reportGcMetrics(long reportTime, StringBuilder result, VirtualMachineMetrics vmMetrics ){

        Map<String, VirtualMachineMetrics.GarbageCollectorStats> map = vmMetrics.garbageCollectors();

        for (Map.Entry<String, VirtualMachineMetrics.GarbageCollectorStats> entry : map.entrySet()) {
            result.append("nifi_gc_metrics,namespace=").append(escapeTagValue(namespace))
                    .append(",hostname=").append(hostname)
                    .append(",gcStatType=").append(escapeTagValue(entry.getKey()))
                    .append("  gcRuns=").append(entry.getValue().getRuns())
                    .append(",gcTime=").append(entry.getValue().getTime(TimeUnit.MILLISECONDS))
                    .append(" ").append(reportTime).append("\n");
        }
    }


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

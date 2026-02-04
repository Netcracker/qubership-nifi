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

import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.nifi.annotation.configuration.DefaultSchedule;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.scheduling.SchedulingStrategy;


import java.lang.Thread.State;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Tags({"reporting", "influxdb", "metrics"})
@CapabilityDescription("Sends Nifi metrics to InfluxDB.")
@DefaultSchedule(strategy = SchedulingStrategy.TIMER_DRIVEN, period = "15 sec")
@Deprecated(since = "2.4.0", forRemoval = true)
public class CommonMetricsReportingTask extends AbstractInfluxDbReportingTask {

    private MemoryMXBean memoryMxBean;
    private RuntimeMXBean runtimeMxBean;
    private ThreadMXBean threadMxBean;

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
        memoryMxBean = ManagementFactory.getMemoryMXBean();
        runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        threadMxBean = ManagementFactory.getThreadMXBean();
    }


    /**
     * Collecting Nifi Common Metrics for sending to monitoring.
     *
     * @param reportTime
     * @param result
     * @param memoryMXBean
     * @param runtimeMXBean
     * @param threadMXBean
     * @param controllerStatus
     */
    public void reportNifiCommonMetricks(
            long reportTime,
            StringBuilder result,
            MemoryMXBean memoryMXBean,
            RuntimeMXBean runtimeMXBean,
            ThreadMXBean threadMXBean,
            ProcessGroupStatus controllerStatus
    ) {
        result.append("nifi_common_metrics,namespace=").append(escapeTagValue(namespace))
                .append(",hostname=").append(hostname)
                .append(" activeThreadCount=").append(controllerStatus.getActiveThreadCount())
                .append(",heapMax=").append(memoryMXBean.getHeapMemoryUsage().getMax() / 1024.0)
                .append(",heapUsed=").append(
                        memoryMXBean.getHeapMemoryUsage().getUsed()
                        * memoryMXBean.getHeapMemoryUsage().getMax() / 1024.0)
                .append(",heapCommited=").append(memoryMXBean.getHeapMemoryUsage().getCommitted() / 1024.0)
                .append(",uptime=").append(runtimeMXBean.getUptime())
                .append(",jvmDaemonThreadCount=").append(threadMXBean.getDaemonThreadCount())
                .append(",jvmThreadTotalCount=").append(threadMXBean.getThreadCount())
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
     * @param threadMXBean
     */
    public void reportNifiThreadMetrics(long reportTime, StringBuilder result, ThreadMXBean threadMXBean) {
        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds(), 0);
        Map<State, MutableInt> threadStatesCount = new HashMap<>();
        for (ThreadInfo info : threadInfos) {
            MutableInt counter = threadStatesCount.get(info.getThreadState());
            if (counter == null) {
                //not found
                counter = new MutableInt(1);
                threadStatesCount.put(info.getThreadState(), counter);
            } else {
                counter.add(1);
            }
        }
        //emulate behavior of old version of VirtualMachineMetrics
        for (State state : State.values()) {
            if (!threadStatesCount.containsKey(state)) {
                threadStatesCount.put(state, null);
            }
        }
        for (Map.Entry<State, MutableInt> entry : threadStatesCount.entrySet()) {
            double normalizedValue = (entry.getValue() == null ? 0 : entry.getValue().doubleValue());
            reportNiFiThreadsMetric(reportTime, result, entry.getKey(), normalizedValue);
        }
    }

    /**
     * Collecting Java Gc Metrics for sending to monitoring.
     *
     * @param reportTime
     * @param result
     * @param gcMxBeans
     */
    public void reportGcMetrics(long reportTime, StringBuilder result, List<GarbageCollectorMXBean> gcMxBeans) {

        for (GarbageCollectorMXBean mxBean : gcMxBeans) {

            result.append("nifi_gc_metrics,namespace=").append(escapeTagValue(namespace))
                    .append(",hostname=").append(hostname)
                    .append(",gcStatType=").append(escapeTagValue(mxBean.getName()))
                    .append("  gcRuns=").append(mxBean.getCollectionCount())
                    .append(",gcTime=").append(mxBean.getCollectionTime())
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
        List<GarbageCollectorMXBean> gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans();

        StringBuilder result = new StringBuilder();
        Instant now = Instant.now();
        long reportTime = TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();

        //prepare Nifi Common Metrics data
        reportNifiCommonMetricks(reportTime, result, memoryMxBean,
                runtimeMxBean, threadMxBean, controllerStatus);
        //prepare Nifi Thread Metrics
        reportNifiThreadMetrics(reportTime, result, threadMxBean);
        //prepare Nifi Gc Metrics
        reportGcMetrics(reportTime, result, gcMxBeans);

        return result.toString().trim();
    }
}

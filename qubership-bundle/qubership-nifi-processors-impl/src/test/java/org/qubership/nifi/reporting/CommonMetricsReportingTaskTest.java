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

import com.yammer.metrics.core.VirtualMachineMetrics;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class CommonMetricsReportingTaskTest {

    private final String namespace = System.getenv("NAMESPACE");

    private CommonMetricsReportingTask commonMetricsReportingTask;

    private ProcessGroupStatus createTestPG() {
        ProcessGroupStatus pg = new ProcessGroupStatus();
        pg.setActiveThreadCount(1);
        return pg;
    }

    private CommonMetricsReportingTask createTask() {
        commonMetricsReportingTask = new CommonMetricsReportingTask();
        //task.setHostname(TEST_HOSTNAME);
        return commonMetricsReportingTask;
    }


    private VirtualMachineMetrics createTestVirtualMachineMetricsCommon() {
        VirtualMachineMetrics virtualMachineMetrics = mock(VirtualMachineMetrics.class);

        //preparing test data
        when(virtualMachineMetrics.heapMax()).thenReturn(5120.0);
        when(virtualMachineMetrics.heapUsage()).thenReturn(5120.0);
        when(virtualMachineMetrics.heapCommitted()).thenReturn(5120.0);
        when(virtualMachineMetrics.uptime()).thenReturn((long) 5);
        when(virtualMachineMetrics.daemonThreadCount()).thenReturn(5);
        when(virtualMachineMetrics.threadCount()).thenReturn(50);

        return  virtualMachineMetrics;
    }

    private VirtualMachineMetrics createTestVirtualMachineMetricsThread() {
        VirtualMachineMetrics virtualMachineMetrics = mock(VirtualMachineMetrics.class);

        //preparing test data
        Map<Thread.State, Double> map = new HashMap<>();
        map.put(Thread.State.WAITING, 4.0);

        when(virtualMachineMetrics.threadCount()).thenReturn(1);
        when(virtualMachineMetrics.threadStatePercentages()).thenReturn(map);

        return  virtualMachineMetrics;
    }

    private VirtualMachineMetrics createTestVirtualMachineMetricsGC() {
        VirtualMachineMetrics virtualMachineMetrics = mock(VirtualMachineMetrics.class);
        VirtualMachineMetrics.GarbageCollectorStats garbageCollectorStats =
                mock(VirtualMachineMetrics.GarbageCollectorStats.class);

        //preparing test data
        Map<String, VirtualMachineMetrics.GarbageCollectorStats> map = new HashMap<>();

        map.put("G1YoungGeneration", garbageCollectorStats);
        map.put("G1OldGeneration", garbageCollectorStats);

        when(virtualMachineMetrics.garbageCollectors()).thenReturn(map);

        return  virtualMachineMetrics;
    }

    private String getExpectedNifiCommonMetricks(long reportTime) {
        StringBuilder result = new StringBuilder();
        result.append("nifi_common_metrics,namespace=").append(namespace)
                .append(",hostname=").append(commonMetricsReportingTask.hostname)
                .append(" activeThreadCount=").append(1)
                .append(",heapMax=").append(5.0)
                .append(",heapUsed=").append(25600.0)
                .append(",heapCommited=").append(5.0)
                .append(",uptime=").append(5)
                .append(",jvmDaemonThreadCount=").append(5)
                .append(",jvmThreadTotalCount=").append(50)
                .append(" ").append(reportTime).append("\n");
        return result.toString();
    }

    private String getExpectedNiFiThreadsMetric(long reportTime) {
        StringBuilder result = new StringBuilder();
        result.append("nifi_thread_metrics,namespace=").append(namespace)
                .append(",hostname=").append(commonMetricsReportingTask.hostname)
                .append(",threadState=").append("WAITING")
                .append(" threadCount=").append(4.0)
                .append(" ").append(reportTime).append("\n");
        return result.toString();
    }

    private String getExpectedNifiGcMetrics(long reportTime) {
        StringBuilder result = new StringBuilder();
        result.append("nifi_gc_metrics,namespace=").append(namespace)
                .append(",hostname=").append(commonMetricsReportingTask.hostname)
                .append(",gcStatType=").append("G1YoungGeneration")
                .append("  gcRuns=").append(0)
                .append(",gcTime=").append(0)
                .append(" ").append(reportTime).append("\n")
                .append("nifi_gc_metrics,namespace=").append(namespace)
                .append(",hostname=").append(commonMetricsReportingTask.hostname)
                .append(",gcStatType=").append("G1OldGeneration")
                .append("  gcRuns=").append(0)
                .append(",gcTime=").append(0)
                .append(" ").append(reportTime).append("\n");
        return result.toString();
    }

    @Test
    public void testCreateInfluxMessageNifiCommonMetricks() {
        CommonMetricsReportingTask testTask = createTask();
        ProcessGroupStatus pg = createTestPG();
        VirtualMachineMetrics virtualMachineMetrics = createTestVirtualMachineMetricsCommon();
        StringBuilder result = new StringBuilder();
        Instant now = Instant.now();
        long reportTime = TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();

        testTask.reportNifiCommonMetricks(reportTime, result, virtualMachineMetrics, pg);

        Assertions.assertEquals(getExpectedNifiCommonMetricks(reportTime), result.toString());
    }

    @Test
    public void testCreateInfluxMessageNiFiThreadsMetric() {
        CommonMetricsReportingTask testTask = createTask();
        VirtualMachineMetrics virtualMachineMetrics = createTestVirtualMachineMetricsThread();
        StringBuilder result = new StringBuilder();
        Instant now = Instant.now();
        long reportTime = TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();

        testTask.reportNifiThreadMetrics(reportTime, result, virtualMachineMetrics);

        Assertions.assertEquals(getExpectedNiFiThreadsMetric(reportTime), result.toString());
    }

    @Test
    public void testCreateInfluxMessageNifiGcMetrics() {
        CommonMetricsReportingTask testTask = createTask();
        VirtualMachineMetrics virtualMachineMetrics = createTestVirtualMachineMetricsGC();
        StringBuilder result = new StringBuilder();
        Instant now = Instant.now();
        long reportTime = TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();

        testTask.reportGcMetrics(reportTime, result, virtualMachineMetrics);

        Assertions.assertEquals(getExpectedNifiGcMetrics(reportTime), result.toString());
    }
}

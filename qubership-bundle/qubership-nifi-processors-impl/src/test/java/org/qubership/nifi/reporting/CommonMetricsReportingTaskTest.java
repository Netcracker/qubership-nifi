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

import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.reporting.ReportingInitializationContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        ReportingInitializationContext initCtx = mock(ReportingInitializationContext.class);
        try {
            commonMetricsReportingTask.initialize(initCtx);
        } catch (InitializationException e) {
            throw new RuntimeException(e);
        }
        //task.setHostname(TEST_HOSTNAME);
        return commonMetricsReportingTask;
    }


    private MemoryMXBean createTestMemoryMXBeanCommon() {
        MemoryMXBean memoryMXBean = mock(MemoryMXBean.class);
        MemoryUsage heapMemoryUsage = mock(MemoryUsage.class);
        //preparing test data
        when(memoryMXBean.getHeapMemoryUsage()).thenReturn(heapMemoryUsage);
        when(heapMemoryUsage.getMax()).thenReturn(5120L);
        when(heapMemoryUsage.getUsed()).thenReturn(5120L);
        when(heapMemoryUsage.getCommitted()).thenReturn(5120L);
        return memoryMXBean;
    }

    private ThreadMXBean createTestThreadMXBeanCommon() {
        ThreadMXBean threadMXBean = mock(ThreadMXBean.class);
        //preparing test data
        when(threadMXBean.getDaemonThreadCount()).thenReturn(5);
        when(threadMXBean.getThreadCount()).thenReturn(50);

        return  threadMXBean;
    }

    private RuntimeMXBean createTestRuntimeMXBeanCommon() {
        RuntimeMXBean runtimeMXBean = mock(RuntimeMXBean.class);
        //preparing test data
        when(runtimeMXBean.getUptime()).thenReturn(5L);

        return runtimeMXBean;
    }

    private ThreadMXBean createTestThreadMxBean() {
        ThreadMXBean threadMXBean = mock(ThreadMXBean.class);

        //preparing test data
        long[] ids = new long[]{1, 2, 3, 4};
        ThreadInfo info = mock(ThreadInfo.class);
        ThreadInfo[] infos = new ThreadInfo[]{info, info, info, info};
        when(info.getThreadState()).thenReturn(Thread.State.WAITING);

        when(threadMXBean.getThreadCount()).thenReturn(1);
        when(threadMXBean.getAllThreadIds()).thenReturn(ids);
        when(threadMXBean.getThreadInfo(ids, 0)).thenReturn(infos);

        return threadMXBean;
    }

    private List<GarbageCollectorMXBean> createTestGCMxBeans() {
        List<GarbageCollectorMXBean> garbageCollectorMXBeans = new ArrayList<>();
        GarbageCollectorMXBean g1Young = mock(GarbageCollectorMXBean.class);
        when(g1Young.getName()).thenReturn("G1YoungGeneration");
        when(g1Young.getCollectionCount()).thenReturn(0L);
        when(g1Young.getCollectionTime()).thenReturn(0L);
        GarbageCollectorMXBean g1Old = mock(GarbageCollectorMXBean.class);
        when(g1Old.getName()).thenReturn("G1OldGeneration");
        when(g1Old.getCollectionCount()).thenReturn(0L);
        when(g1Old.getCollectionTime()).thenReturn(0L);
        garbageCollectorMXBeans.add(g1Young);
        garbageCollectorMXBeans.add(g1Old);

        return garbageCollectorMXBeans;
    }

    private String getExpectedNifiCommonMetrics(long reportTime) {
        return "nifi_common_metrics,namespace=" + namespace
                + ",hostname=" + commonMetricsReportingTask.hostname
                + " activeThreadCount=" + 1
                + ",heapMax=" + 5.0
                + ",heapUsed=" + 25600.0
                + ",heapCommited=" + 5.0
                + ",uptime=" + 5
                + ",jvmDaemonThreadCount=" + 5
                + ",jvmThreadTotalCount=" + 50
                + " " + reportTime + "\n";
    }

    private String getExpectedNiFiThreadsMetric(long reportTime) {
        return "nifi_thread_metrics,namespace=" + namespace
                + ",hostname=" + commonMetricsReportingTask.hostname
                + ",threadState=" + Thread.State.WAITING
                + " threadCount=" + 4.0
                + " " + reportTime + "\n";
    }

    private String getExpectedNiFiThreadsMetricEmpty(long reportTime, Thread.State state) {
        return "nifi_thread_metrics,namespace=" + namespace
                + ",hostname=" + commonMetricsReportingTask.hostname
                + ",threadState=" + state.toString()
                + " threadCount=" + 0.0
                + " " + reportTime + "\n";
    }

    private String getExpectedNifiGcMetrics(long reportTime) {
        return "nifi_gc_metrics,namespace=" + namespace
                + ",hostname=" + commonMetricsReportingTask.hostname
                + ",gcStatType=" + "G1YoungGeneration"
                + "  gcRuns=" + 0
                + ",gcTime=" + 0
                + " " + reportTime + "\n"
                + "nifi_gc_metrics,namespace=" + namespace
                + ",hostname=" + commonMetricsReportingTask.hostname
                + ",gcStatType=" + "G1OldGeneration"
                + "  gcRuns=" + 0
                + ",gcTime=" + 0
                + " " + reportTime + "\n";
    }

    @Test
    public void testCreateInfluxMessageNifiCommonMetrics() {
        CommonMetricsReportingTask testTask = createTask();
        ProcessGroupStatus pg = createTestPG();
        MemoryMXBean memoryMXBean = createTestMemoryMXBeanCommon();
        RuntimeMXBean runtimeMXBean = createTestRuntimeMXBeanCommon();
        ThreadMXBean threadMXBean = createTestThreadMXBeanCommon();
        StringBuilder result = new StringBuilder();
        Instant now = Instant.now();
        long reportTime = TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();

        testTask.reportNifiCommonMetricks(reportTime, result,
                memoryMXBean, runtimeMXBean, threadMXBean, pg);

        Assertions.assertEquals(getExpectedNifiCommonMetrics(reportTime), result.toString());
    }

    @Test
    public void testCreateInfluxMessageNiFiThreadsMetric() {
        CommonMetricsReportingTask testTask = createTask();
        ThreadMXBean threadMXBean = createTestThreadMxBean();
        StringBuilder result = new StringBuilder();
        Instant now = Instant.now();
        long reportTime = TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();

        testTask.reportNifiThreadMetrics(reportTime, result, threadMXBean);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.toString().contains(getExpectedNiFiThreadsMetric(reportTime)));
        Assertions.assertTrue(result.toString().contains(
                getExpectedNiFiThreadsMetricEmpty(reportTime, Thread.State.NEW)));
        Assertions.assertTrue(result.toString().contains(
                getExpectedNiFiThreadsMetricEmpty(reportTime, Thread.State.BLOCKED)));
        Assertions.assertTrue(result.toString().contains(
                getExpectedNiFiThreadsMetricEmpty(reportTime, Thread.State.TIMED_WAITING)));
        Assertions.assertTrue(result.toString().contains(
                getExpectedNiFiThreadsMetricEmpty(reportTime, Thread.State.RUNNABLE)));
        Assertions.assertTrue(result.toString().contains(
                getExpectedNiFiThreadsMetricEmpty(reportTime, Thread.State.TERMINATED)));
    }

    @Test
    public void testCreateInfluxMessageNifiGcMetrics() {
        CommonMetricsReportingTask testTask = createTask();
        List<GarbageCollectorMXBean> gcBeans = createTestGCMxBeans();
        StringBuilder result = new StringBuilder();
        Instant now = Instant.now();
        long reportTime = TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();

        testTask.reportGcMetrics(reportTime, result, gcBeans);

        Assertions.assertEquals(getExpectedNifiGcMetrics(reportTime), result.toString());
    }
}

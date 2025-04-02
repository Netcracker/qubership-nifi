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

import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmCompilationMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmHeapPressureMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmInfoMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import org.apache.nifi.reporting.Bulletin;
import org.apache.nifi.reporting.BulletinQuery;
import org.apache.nifi.reporting.BulletinRepository;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.reporting.ReportingInitializationContext;
import org.qubership.nifi.reporting.metrics.model.BulletinKey;
import org.qubership.nifi.reporting.metrics.model.BulletinSummary;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tag;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.status.ConnectionStatus;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.controller.status.ProcessorStatus;
import org.apache.nifi.controller.status.PortStatus;
import org.apache.nifi.controller.status.RunStatus;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.util.StandardValidators;
import org.qubership.nifi.reporting.metrics.component.ConnectionMetricName;
import org.qubership.nifi.reporting.metrics.component.ProcessorMetricName;
import org.springframework.util.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.COMPONENT_COUNT_METRIC_NAME;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.BULLETIN_COUNT_METRIC_NAME;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.QUEUED_BYTES_PG_METRIC_NAME;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.QUEUED_COUNT_PG_METRIC_NAME;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.ACTIVE_THREAD_COUNT_METRIC_NAME;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.BULLETIN_CNT_METRIC_NAME;
//import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.*;
//import static org.apache.nifi.reporting.ComponentType.*;

import static org.apache.nifi.reporting.ComponentType.REPORTING_TASK;
import static org.apache.nifi.reporting.ComponentType.CONTROLLER_SERVICE;
import static org.apache.nifi.reporting.ComponentType.FLOW_CONTROLLER;

@Tags({"reporting", "prometheus", "metrics"})
@CapabilityDescription("Sends components (Processors, Connections) metrics to Prometheus.")
public class ComponentPrometheusReportingTask extends AbstractPrometheusReportingTask {

    private static final String COMPONENT_ID_TAG = "component_id";

    private static final String PROCESS_GROUP_ID_TAG = "group_id";

    public static final PropertyDescriptor PROCESSOR_TIME_THRESHOLD = new PropertyDescriptor.Builder()
            .name("processor-time-threshold")
            .displayName("Processor time threshold")
            .description("Minimal processing time for processor to be included in monitoring.\n"
                    + "Limits data volume collected in Prometheus.")
            .defaultValue("150 sec")
            .required(true)
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    public static final PropertyDescriptor CONNECTION_QUEUE_THRESHOLD = new PropertyDescriptor.Builder()
            .name("connection-queue-threshold")
            .displayName("Connection queue threshold")
            .description("Minimal connection usage % relative to backPressureObjectThreshold.\n"
                    + "Limits data volume collected in Prometheus.")
            .defaultValue("80")
            .required(true)
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    public static final PropertyDescriptor PROCESS_GROUP_LEVEL_THRESHOLD = new PropertyDescriptor.Builder()
            .name("pg-level-threshold")
            .displayName("Process group level threshold")
            .description("Maximum depth of process group to report in monitoring.\n"
                    + "Limits data volume collected in Prometheus.")
            .defaultValue("2")
            .required(true)
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    private long processorTimeThreshold;
    private double connectionQueueThreshold;
    private int pgLevelThreshold;
    private volatile long lastSentBulletinId = -1L;
    private ComponentLog logger;


    private AtomicReference<Map<String, ConnectionStatus>> connectionMetrics = new AtomicReference<>();
    private AtomicReference<Map<String, ProcessorStatus>> processorMetrics = new AtomicReference<>();
    private AtomicReference<Map<BulletinKey, BulletinSummary>> bulletinMetrics = new AtomicReference<>();
    private AtomicReference<Map<String, ProcessGroupStatus>> processGroupMetrics = new AtomicReference<>();
    private AtomicReference<Map<String, Map<String, Integer>>> processGroupBulletinMetrics = new AtomicReference<>();
    private AtomicReference<Map<String, Map<String, Integer>>> processGroupComponentMetrics = new AtomicReference<>();

    private JvmThreadMetrics jvmThreadMetrics;
    private JvmMemoryMetrics jvmMemoryMetrics;
    private JvmGcMetrics jvmGcMetrics;
    private ClassLoaderMetrics classLoaderMetrics;
    private JvmCompilationMetrics jvmCompilationMetrics;
    private JvmHeapPressureMetrics jvmHeapPressureMetrics;
    private JvmInfoMetrics jvmInfoMetrics;

    /**
     * Initializes list of property descriptors supported by this reporting task.
     * @return list of property descriptors
     */
    @Override
    protected List<PropertyDescriptor> initProperties() {
        List<PropertyDescriptor> allProps = super.initProperties();
        allProps.add(PROCESSOR_TIME_THRESHOLD);
        allProps.add(CONNECTION_QUEUE_THRESHOLD);
        allProps.add(PROCESS_GROUP_LEVEL_THRESHOLD);
        return allProps;
    }

    /**
     * Initializes reporting task's property descriptors.
     */
    @Override
    public void init(ReportingInitializationContext config) {
        super.init(config);
        logger = config.getLogger();
    }

    private static final int PERCENT_MAX = 100;

    /**
     * Initializes reporting task before it's started.
     * @param context reporting context
     */
    @OnScheduled
    public void onScheduled(final ConfigurationContext context) {
        super.onScheduled(context);

        processorTimeThreshold = context.getProperty(PROCESSOR_TIME_THRESHOLD).asTimePeriod(TimeUnit.NANOSECONDS);
        connectionQueueThreshold = context.getProperty(CONNECTION_QUEUE_THRESHOLD).asDouble() / PERCENT_MAX;
        pgLevelThreshold = context.getProperty(PROCESS_GROUP_LEVEL_THRESHOLD).asInteger();
        //JVM Metrics should be bind only when meter registry is created/recreated:
        registerGaugesForJvmMetric();
    }

    /**
     * Registers metrics in meter registry and updates their values.
     * @param context reporting context
     */
    @Override
    public void registerMetrics(ReportingContext context) {
        try {
            ProcessGroupStatus controllerStatus = context.getEventAccess().getControllerStatus();
            Map<String, ConnectionStatus> connections = new HashMap<>();
            Map<String, ProcessorStatus> processors = new HashMap<>();
            Map<BulletinKey, BulletinSummary> bulletinSummaries = new HashMap<>();
            Map<String, ProcessGroupStatus> processGroupsStatus = new HashMap<>();
            Map<String, Map<String, Integer>> processGroupBulletin = new HashMap<>();
            Map<String, Map<String, Integer>> processGroupComponentCount = new HashMap<>();
            int currentLevel = 0;

            //start with root process group:
            getAllEligibleStatuses(controllerStatus, connections, processors);
            getAllEligibleProcessGroupStatuses(controllerStatus, processGroupsStatus, currentLevel);

            BulletinRepository bulletinRepository = context.getBulletinRepository();
            getAllEligibleBulletins(bulletinSummaries, bulletinRepository, processGroupBulletin);
            getAllEligibleProcessGroupComponentRunStatus(controllerStatus.getProcessGroupStatus(),
                    processGroupComponentCount);

            cleanMetricsForTopProcessGroups(processGroupBulletin, processGroupComponentCount);
            cleanMetricsForProcessGroups(processGroupsStatus);

            registerGaugesForConnections(connections);
            registerGaugesForProcessors(processors);
            registerGaugesForBulletins(bulletinSummaries);
            registerGaugesForTopProcessGroups(processGroupBulletin, processGroupComponentCount,
                    controllerStatus.getProcessGroupStatus());
            registerGaugesForProcessGroups(controllerStatus, processGroupsStatus);
        } catch (RuntimeException ex) {
            if (logger != null) {
                logger.error("Unexpected exception occurred : ", ex);
            }
        }
    }

    private void cleanMetricsForTopProcessGroups(Map<String, Map<String, Integer>> processGroupBulletin,
                                                 Map<String, Map<String, Integer>> processGroupComponentCount) {
        if (processGroupComponentMetrics.get() != null) {
            for (String pgId : processGroupComponentMetrics.get().keySet()) {
                if (!processGroupComponentCount.containsKey(pgId)) {
                    removeMetricFromRegistry(COMPONENT_COUNT_METRIC_NAME.getName(), PROCESS_GROUP_ID_TAG, pgId);
                }
            }
        }
        if (processGroupBulletinMetrics.get() != null) {
            for (String pgId : processGroupBulletinMetrics.get().keySet()) {
                if (!processGroupBulletin.containsKey(pgId)) {
                    removeMetricFromRegistry(BULLETIN_COUNT_METRIC_NAME.getName(), PROCESS_GROUP_ID_TAG, pgId);
                }
            }
        }
    }

    private void cleanMetricsForProcessGroups(Map<String, ProcessGroupStatus> processGroupsStatus) {
        if (processGroupMetrics.get() != null) {
            for (ProcessGroupStatus prGp : processGroupMetrics.get().values()) {
                if (!processGroupsStatus.containsKey(prGp.getId())) {
                    removeMetricFromRegistry(ACTIVE_THREAD_COUNT_METRIC_NAME.getName(),
                            PROCESS_GROUP_ID_TAG, prGp.getId());
                    removeMetricFromRegistry(QUEUED_COUNT_PG_METRIC_NAME.getName(), PROCESS_GROUP_ID_TAG, prGp.getId());
                    removeMetricFromRegistry(QUEUED_BYTES_PG_METRIC_NAME.getName(), PROCESS_GROUP_ID_TAG, prGp.getId());
                }
            }
        }
    }

    private ConnectionStatus getConnectionByKey(String key) {
        Map<String, ConnectionStatus> map = connectionMetrics.get();
        if (map == null) {
            return null;
        }
        return map.get(key);
    }

    private ProcessorStatus getProcessorByKey(String key) {
        Map<String, ProcessorStatus> map = processorMetrics.get();
        if (map == null) {
            return null;
        }
        return map.get(key);
    }

    private ProcessGroupStatus getProcessGroupStatusByKey(String key) {
        Map<String, ProcessGroupStatus> map = processGroupMetrics.get();
        if (map == null) {
            return null;
        }
        return  map.get(key);
    }

    private Map<String, Integer> getProcessGroupBulletinMetrics(String key) {
        Map<String, Map<String, Integer>> map = processGroupBulletinMetrics.get();
        if (map == null) {
            return null;
        }
        return map.get(key);
    }


    private Map<String, Integer> getProcessGroupComponentMetrics(String key) {
        Map<String, Map<String, Integer>> map = processGroupComponentMetrics.get();
        if (map == null) {
            return null;
        }
        return map.get(key);
    }

    private BulletinSummary getBulletinSummaryByBulletinKey(BulletinKey key) {
        Map<BulletinKey, BulletinSummary> map = bulletinMetrics.get();
        if (map == null) {
            return null;
        }
        return map.get(key);
    }

    private void registerGaugesForConnections(Map<String, ConnectionStatus> connectionStatuses) {
        if (CollectionUtils.isEmpty(connectionStatuses)) {
            return;
        }

        connectionMetrics.set(connectionStatuses);

        for (Map.Entry<String, ConnectionStatus> st : connectionStatuses.entrySet()) {
            final String key = st.getKey();
            Gauge.builder(ConnectionMetricName.QUEUED_COUNT_METRIC_NAME.getName(), () -> {
                        ConnectionStatus con = getConnectionByKey(key);
                        return con == null ? 0 : con.getQueuedCount();
                    })
                    .tag("namespace", getNamespace())
                    .tag("hostname", getHostname())
                    .tag("instance", getInstance())
                    .tag("component_id", st.getValue().getId())
                    .tag("component_name", st.getValue().getName())
                    .tag("component_type", "Connection")
                    .tag("parent_id", st.getValue().getGroupId())
                    .tag("source_id", st.getValue().getSourceId())
                    .tag("destination_id", st.getValue().getDestinationId())
                    .register(getMeterRegistry());

            Gauge.builder(ConnectionMetricName.QUEUED_BYTES_METRIC_NAME.getName(), () -> {
                        ConnectionStatus con = getConnectionByKey(key);
                        return con == null ? 0 : con.getQueuedBytes();
                    })
                    .tag("namespace", getNamespace())
                    .tag("hostname", getHostname())
                    .tag("instance", getInstance())
                    .tag("component_id", st.getValue().getId())
                    .tag("component_name", st.getValue().getName())
                    .tag("component_type", "Connection")
                    .tag("parent_id", st.getValue().getGroupId())
                    .tag("source_id", st.getValue().getSourceId())
                    .tag("destination_id", st.getValue().getDestinationId())
                    .register(getMeterRegistry());

            Gauge.builder(ConnectionMetricName.PERCENT_USED_COUNT_METRIC_NAME.getName(), () -> {
                        ConnectionStatus con = getConnectionByKey(key);
                        return con == null ? 0 : ((double) con.getQueuedCount()) / con.getBackPressureObjectThreshold();
                    })
                    .tag("namespace", getNamespace())
                    .tag("hostname", getHostname())
                    .tag("instance", getInstance())
                    .tag("component_id", st.getValue().getId())
                    .tag("component_name", st.getValue().getName())
                    .tag("component_type", "Connection")
                    .tag("parent_id", st.getValue().getGroupId())
                    .tag("source_id", st.getValue().getSourceId())
                    .tag("destination_id", st.getValue().getDestinationId())
                    .register(getMeterRegistry());

            Gauge.builder(ConnectionMetricName.PERCENT_USED_BYTES_METRIC_NAME.getName(), () -> {
                        ConnectionStatus con = getConnectionByKey(key);
                        return con == null ? 0 : ((double) con.getQueuedBytes()) / con.getBackPressureBytesThreshold();
                    })
                    .tag("namespace", getNamespace())
                    .tag("hostname", getHostname())
                    .tag("instance", getInstance())
                    .tag("component_id", st.getValue().getId())
                    .tag("component_name", st.getValue().getName())
                    .tag("component_type", "Connection")
                    .tag("parent_id", st.getValue().getGroupId())
                    .tag("source_id", st.getValue().getSourceId())
                    .tag("destination_id", st.getValue().getDestinationId())
                    .register(getMeterRegistry());
        }
    }

    private void registerGaugesForProcessors(Map<String, ProcessorStatus> processorStatuses) {
        if (CollectionUtils.isEmpty(processorStatuses)) {
            return;
        }

        processorMetrics.set(processorStatuses);

        for (Map.Entry<String, ProcessorStatus> st : processorStatuses.entrySet()) {
            final String key = st.getKey();
            Gauge.builder(ProcessorMetricName.TASKS_TIME_TOTAL_METRIC_NAME.getName(), () -> {
                        ProcessorStatus proc = getProcessorByKey(key);
                        return proc == null ? 0 : proc.getProcessingNanos();
                    })
                    .tag("namespace", getNamespace())
                    .tag("hostname", getHostname())
                    .tag("instance", getInstance())
                    .tag("component_id", st.getValue().getId())
                    .tag("component_name", st.getValue().getName())
                    .tag("component_type", "Processor")
                    .tag("parent_id", st.getValue().getGroupId())
                    .register(getMeterRegistry());

            Gauge.builder(ProcessorMetricName.TASKS_COUNT_METRIC_NAME.getName(), () -> {
                        ProcessorStatus proc = getProcessorByKey(key);
                        return proc == null ? 0 : proc.getInvocations();
                    })
                    .tag("namespace", getNamespace())
                    .tag("hostname", getHostname())
                    .tag("instance", getInstance())
                    .tag("component_id", st.getValue().getId())
                    .tag("component_name", st.getValue().getName())
                    .tag("component_type", "Processor")
                    .tag("parent_id", st.getValue().getGroupId())
                    .register(getMeterRegistry());
        }
    }

    private void registerGaugesForBulletins(Map<BulletinKey, BulletinSummary> bulletinSummaries) {
        if (CollectionUtils.isEmpty(bulletinSummaries)) {
            return;
        }
        if (logger != null) {
            logger.info("Bulletin Summaries : {}", bulletinSummaries);
        }
        bulletinMetrics.set(bulletinSummaries);

        for (Map.Entry<BulletinKey, BulletinSummary> st : bulletinSummaries.entrySet()) {

            String groupPath;
            String groupId;
            String groupName;
            if (st.getValue().getSourceType() == REPORTING_TASK || st.getValue().getSourceType() == CONTROLLER_SERVICE
                    || st.getValue().getSourceType() == FLOW_CONTROLLER) {
                if (StringUtils.isEmpty(st.getValue().getGroupPath())
                        || "null".equals(st.getValue().getGroupPath()) || "".equals(st.getValue().getGroupPath())) {
                    groupPath = "NiFi Flow";
                    groupId = "Root";
                    groupName = "Root Process Group";
                } else {
                    groupPath = st.getValue().getGroupPath();
                    groupId = st.getValue().getGroupId();
                    groupName = st.getValue().getGroupName();
                }
            } else {
                groupPath = st.getValue().getGroupPath();
                groupId = st.getValue().getGroupId();
                groupName = st.getValue().getGroupName();
            }
            final BulletinKey key = st.getKey();
            Gauge.builder("nc_nifi_bulletin_count", () -> {
                        BulletinSummary bull = getBulletinSummaryByBulletinKey(key);
                        return bull == null ? 0 : bull.getCount();
                    })
                    .tag("namespace", getNamespace())
                    .tag("hostname", getHostname())
                    .tag("instance", getInstance())
                    .tag("component_id", Optional.ofNullable(st.getValue().getSourceId()).orElse(""))
                    .tag("component_name", Optional.ofNullable(st.getValue().getSourceName()).orElse(""))
                    .tag("component_type", Optional.ofNullable(String.valueOf(st.getValue().getSourceType())).
                            orElse(""))
                    .tag("group_id", Optional.ofNullable(groupId).orElse(""))
                    .tag("group_name", Optional.ofNullable(groupName).orElse(""))
                    .tag("group_path", Optional.ofNullable(groupPath).orElse(""))
                    .tag("level", Optional.ofNullable(st.getValue().getLevel()).orElse(""))
                    .tag("category", Optional.ofNullable(st.getValue().getCategory()).orElse(""))
                    .register(getMeterRegistry());


            Counter counter = Counter.builder("nc_nifi_bulletin_cnt")
                    .tag("namespace", getNamespace())
                    .tag("hostname", getHostname())
                    .tag("instance", getInstance())
                    .tag("component_id", Optional.ofNullable(st.getValue().getSourceId()).orElse(""))
                    .tag("component_name", Optional.ofNullable(st.getValue().getSourceName()).orElse(""))
                    .tag("component_type", Optional.ofNullable(String.valueOf(st.getValue().getSourceType())).
                            orElse(""))
                    .tag("group_id", Optional.ofNullable(groupId).orElse(""))
                    .tag("group_name", Optional.ofNullable(groupName).orElse(""))
                    .tag("group_path", Optional.ofNullable(groupPath).orElse(""))
                    .tag("level", Optional.ofNullable(st.getValue().getLevel()).orElse(""))
                    .tag("category", Optional.ofNullable(st.getValue().getCategory()).orElse(""))
                    .register(getMeterRegistry());
            counter.increment(st.getValue().getCount());
        }
    }

    private void registerGaugesForTopProcessGroups(Map<String, Map<String, Integer>> processGroupBulletin,
                                                   Map<String, Map<String, Integer>> processGroupComponentCount,
                                                   Collection<ProcessGroupStatus> topProcessGroups) {

        processGroupComponentMetrics.set(processGroupComponentCount);
        processGroupBulletinMetrics.set(processGroupBulletin);

        Map<String, ProcessGroupStatus> topProcessGroupsMap = topProcessGroups.stream()
                .collect(Collectors.toMap(ProcessGroupStatus::getId, Function.identity()));


        for (Map.Entry<String, Map<String, Integer>> pgCount : processGroupComponentCount.entrySet()) {
            final String key = pgCount.getKey();
            ProcessGroupStatus pgSt = topProcessGroupsMap.get(key);
            final String groupName = pgSt.getName();
            pgCount.getValue().forEach((runStatus, count) -> {
                if (count != 0) {
                    Gauge.builder(COMPONENT_COUNT_METRIC_NAME.getName(), () -> {
                                Map<String, Integer> pgRunStatus = getProcessGroupComponentMetrics(key);
                                return pgRunStatus == null ? 0 : pgRunStatus.get(runStatus);
                            })
                            .tag("namespace", getNamespace())
                            .tag("hostname", getHostname())
                            .tag("instance", getInstance())
                            .tag("group_id", key)
                            .tag("group_name", groupName)
                            .tag("runningStatus", runStatus)
                            .register(getMeterRegistry());
                }
            });
        }

        for (Map.Entry<String, ProcessGroupStatus> pgStEntry : topProcessGroupsMap.entrySet()) {
            final String key = pgStEntry.getKey();
            Map<String, Integer> pgBulletinCount = processGroupBulletin.get(key);
            //if we don't have bulletins, we can skip
            if (pgBulletinCount != null) {
                ProcessGroupStatus pgSt = pgStEntry.getValue();
                final String groupName = pgSt.getName();
                pgBulletinCount.forEach((level, count) -> {
                    Gauge.builder(BULLETIN_COUNT_METRIC_NAME.getName(), () -> {
                                Map<String, Integer> pgBullLevel = getProcessGroupBulletinMetrics(key);
                                return pgBullLevel == null ? 0 : pgBullLevel.get(level);
                            })
                            .tag("namespace", getNamespace())
                            .tag("hostname", getHostname())
                            .tag("instance", getInstance())
                            .tag("group_id", key)
                            .tag("group_name", groupName)
                            .tag("runningStatus", level)
                            .register(getMeterRegistry());
                });
                pgBulletinCount.forEach((level, count) -> {
                    Counter counter = Counter.builder(BULLETIN_CNT_METRIC_NAME.getName())
                            .tag("namespace", getNamespace())
                            .tag("hostname", getHostname())
                            .tag("instance", getInstance())
                            .tag("group_id", key)
                            .tag("group_name", groupName)
                            .tag("runningStatus", level)
                            .register(getMeterRegistry());
                    counter.increment(count);
                });
            }
        }
    }


    private void registerGaugesForProcessGroups(ProcessGroupStatus topProcessGroup,
                                                Map<String, ProcessGroupStatus> processGroupsStatus) {
        if (CollectionUtils.isEmpty(processGroupsStatus)) {
            return;
        }

        Map<String, String> groupPath = new HashMap<>();
        groupPath = generateGroupPath(topProcessGroup, groupPath, "");

        processGroupMetrics.set(processGroupsStatus);

        for (Map.Entry<String, ProcessGroupStatus> pgSt : processGroupsStatus.entrySet()) {
            final String key = pgSt.getKey();
            Gauge.builder(ACTIVE_THREAD_COUNT_METRIC_NAME.getName(), () -> {
                        ProcessGroupStatus procGroup = getProcessGroupStatusByKey(key);
                        return procGroup == null ? 0 : procGroup.getActiveThreadCount();
                    })
                    .tag("namespace", getNamespace())
                    .tag("hostname", getHostname())
                    .tag("instance", getInstance())
                    .tag("group_id", pgSt.getValue().getId())
                    .tag("group_name", pgSt.getValue().getName())
                    .tag("group_path", groupPath.get(pgSt.getValue().getId()))
                    .register(getMeterRegistry());

            Gauge.builder(QUEUED_COUNT_PG_METRIC_NAME.getName(), () -> {
                        ProcessGroupStatus procGroup = getProcessGroupStatusByKey(key);
                        return procGroup == null ? 0 : procGroup.getQueuedCount();
                    })
                    .tag("namespace", getNamespace())
                    .tag("hostname", getHostname())
                    .tag("instance", getInstance())
                    .tag("group_id", pgSt.getValue().getId())
                    .tag("group_name", pgSt.getValue().getName())
                    .tag("group_path", groupPath.get(pgSt.getValue().getId()))
                    .register(getMeterRegistry());

            Gauge.builder(QUEUED_BYTES_PG_METRIC_NAME.getName(), () -> {
                        ProcessGroupStatus procGroup = getProcessGroupStatusByKey(key);
                        return procGroup == null ? 0 : procGroup.getQueuedContentSize();
                    })
                    .tag("namespace", getNamespace())
                    .tag("hostname", getHostname())
                    .tag("instance", getInstance())
                    .tag("group_id", pgSt.getValue().getId())
                    .tag("group_name", pgSt.getValue().getName())
                    .tag("group_path", groupPath.get(pgSt.getValue().getId()))
                    .register(getMeterRegistry());
        }
    }

    private static final int DEFAULT_LOOKBACK_DURATION = 5;

    private void registerGaugesForJvmMetric() {
        List<Tag> tagsList = List.of(Tag.of("instance", getInstance()),
                Tag.of("hostname", getHostname()), Tag.of("namespace", getNamespace()));
        //binders should be created only when on first access:
        if (jvmThreadMetrics == null) {
            jvmThreadMetrics = new JvmThreadMetrics(tagsList);
        }
        if (jvmMemoryMetrics == null) {
            jvmMemoryMetrics = new JvmMemoryMetrics(tagsList);
        }
        if (jvmGcMetrics == null) {
            jvmGcMetrics = new JvmGcMetrics(tagsList);
        }
        if (classLoaderMetrics == null) {
            classLoaderMetrics = new ClassLoaderMetrics(tagsList);
        }
        if (jvmCompilationMetrics == null) {
            jvmCompilationMetrics = new JvmCompilationMetrics(tagsList);
        }
        if (jvmHeapPressureMetrics == null) {
            jvmHeapPressureMetrics = new JvmHeapPressureMetrics(tagsList,
                    Duration.ofMinutes(DEFAULT_LOOKBACK_DURATION), Duration.ofMinutes(1));
        }
        if (jvmInfoMetrics == null) {
            jvmInfoMetrics = new JvmInfoMetrics();
        }
        jvmThreadMetrics.bindTo(getMeterRegistry());
        jvmMemoryMetrics.bindTo(getMeterRegistry());
        jvmGcMetrics.bindTo(getMeterRegistry());
        classLoaderMetrics.bindTo(getMeterRegistry());
        jvmCompilationMetrics.bindTo(getMeterRegistry());
        jvmHeapPressureMetrics.bindTo(getMeterRegistry());
        jvmInfoMetrics.bindTo(getMeterRegistry());
    }

    /**
     * Removes metric from meter registry identifier by metric name as well as tag name and value.
     * @param metricName metric name
     * @param tagName tag name
     * @param tagValue tag value
     */
    public void removeMetricFromRegistry(String metricName, String tagName, String tagValue) {
        Collection<Gauge> gauges = getMeterRegistry().find(metricName).tag(tagName, tagValue).gauges();

        if (CollectionUtils.isEmpty(gauges)) {
            return;
        }

        for (Gauge gauge : gauges) {
            getMeterRegistry().remove(gauge);
        }
    }

    /**
     * Removes metric from meter registry identified by metric name, source id, source type and level.
     * @param metricName metric name
     * @param sourceId source identifier
     * @param sourceType source type
     * @param level level
     */
    public void removeMetricFromRegistry(String metricName, String sourceId, String sourceType, String level) {
        Collection<Gauge> gauges = getMeterRegistry().find(metricName)
                .tag(COMPONENT_ID_TAG, sourceId)
                .tag("component_type", sourceType)
                .tag("level", level)
                .gauges();

        if (CollectionUtils.isEmpty(gauges)) {
            return;
        }

        for (Gauge gauge : gauges) {
            getMeterRegistry().remove(gauge);
        }
    }

    private void getAllEligibleProcessGroupStatuses(ProcessGroupStatus controllerStatus,
                                                    Map<String, ProcessGroupStatus> processGroupsStatus,
                                                    int currentLevel) {
        //0 = first level
        //1 = second level
        if (currentLevel + 1 > pgLevelThreshold) {
            return;
        }
        for (ProcessGroupStatus status : controllerStatus.getProcessGroupStatus()) {
            processGroupsStatus.put(status.getId(), status);
            getAllEligibleProcessGroupStatuses(status, processGroupsStatus, currentLevel + 1);
        }
    }

    private void getAllEligibleProcessGroupComponentRunStatus(Collection<ProcessGroupStatus> topProcessGroups,
                                                      Map<String, Map<String, Integer>> processGroupComponentCount) {
        Map<String, ProcessGroupStatus> topProcessGroupsMap = topProcessGroups.stream()
                .collect(Collectors.toMap(ProcessGroupStatus::getId, Function.identity()));
        for (Map.Entry<String, ProcessGroupStatus> pgSt : topProcessGroupsMap.entrySet()) {
            Map<String, Integer> runningStatus = new HashMap<>();
            runningStatus = countRunningStatus(pgSt.getValue(), runningStatus);
            for (RunStatus st : RunStatus.values()) {
                runningStatus.putIfAbsent(st.name(), 0);
            }
            processGroupComponentCount.put(pgSt.getKey(), runningStatus);
        }
    }


    private void getAllEligibleStatuses(ProcessGroupStatus pgStatus, Map<String, ConnectionStatus> connections,
                                        Map<String, ProcessorStatus> processors) {
        if (pgStatus == null) {
            return;
        }
        //add connections:
        Collection<ConnectionStatus> newConnections = pgStatus.getConnectionStatus();
        if (newConnections != null) {
            for (ConnectionStatus status : newConnections) {
                if (status.getQueuedCount() > status.getBackPressureObjectThreshold() * connectionQueueThreshold) {
                    connections.put(status.getId(), status);
                } else {
                    removeMetricFromRegistry(ConnectionMetricName.QUEUED_COUNT_METRIC_NAME.getName(),
                            COMPONENT_ID_TAG, status.getId());
                    removeMetricFromRegistry(ConnectionMetricName.QUEUED_BYTES_METRIC_NAME.getName(),
                            COMPONENT_ID_TAG, status.getId());
                    removeMetricFromRegistry(ConnectionMetricName.PERCENT_USED_COUNT_METRIC_NAME.getName(),
                            COMPONENT_ID_TAG, status.getId());
                    removeMetricFromRegistry(ConnectionMetricName.PERCENT_USED_BYTES_METRIC_NAME.getName(),
                            COMPONENT_ID_TAG, status.getId());
                }
            }
        }
        //add processors:
        Collection<ProcessorStatus> newProcessors = pgStatus.getProcessorStatus();
        if (newProcessors != null) {
            for (ProcessorStatus status : newProcessors) {
                if (status.getProcessingNanos() > processorTimeThreshold) {
                    processors.put(status.getId(), status);
                } else {
                    removeMetricFromRegistry(ProcessorMetricName.TASKS_TIME_TOTAL_METRIC_NAME.getName(),
                            COMPONENT_ID_TAG, status.getId());
                    removeMetricFromRegistry(ProcessorMetricName.TASKS_COUNT_METRIC_NAME.getName(),
                            COMPONENT_ID_TAG, status.getId());
                }
            }
        }
        //process children Process Groups, if any:
        Collection<ProcessGroupStatus> childPg = pgStatus.getProcessGroupStatus();
        if (childPg != null) {
            childPg.forEach((pg) -> {
                getAllEligibleStatuses(pg, connections, processors);
            });
        }
    }

    private void getAllEligibleBulletins(Map<BulletinKey, BulletinSummary> bulletinSummaries,
                                         BulletinRepository bulletinRepository,
                                         Map<String, Map<String, Integer>> processGroupBulletin) {
        BulletinQuery bulletinQuery = new BulletinQuery.Builder().after(lastSentBulletinId).build();
        List<Bulletin> bulletinsList = bulletinRepository.findBulletins(bulletinQuery);
        if (logger != null) {
            logger.info("Number of bulletins got from bulletin repository: {}", bulletinsList.size());
        }
        if (!bulletinsList.isEmpty()) {
            OptionalLong opMaxId = bulletinsList.stream().mapToLong(Bulletin::getId).max();
            long currMaxId = opMaxId.isPresent() ? opMaxId.getAsLong() : -1;
            if (currMaxId > lastSentBulletinId) {
                for (Bulletin bulletin : bulletinsList) {
                    if (logger != null) {
                        logger.info("Bulletin Details: {}", bulletinToString(bulletin));
                    }
                    if (bulletin.getId() > lastSentBulletinId) {
                        BulletinKey toPutKey = new BulletinKey(bulletin.getSourceId(),
                                bulletin.getSourceType(), bulletin.getLevel());
                        if (bulletinSummaries.containsKey(toPutKey)) {
                            BulletinSummary temp = bulletinSummaries.get(toPutKey);
                            temp.setCount(temp.getCount() + 1);
                            bulletinSummaries.put(toPutKey, temp);
                        } else {
                            BulletinSummary bulletinSummary = new BulletinSummary();
                            bulletinSummary.setSourceId(bulletin.getSourceId());
                            bulletinSummary.setSourceName(bulletin.getSourceName());
                            bulletinSummary.setSourceType(bulletin.getSourceType());
                            bulletinSummary.setLevel(bulletin.getLevel());
                            bulletinSummary.setGroupPath(bulletin.getGroupPath());
                            bulletinSummary.setGroupName(bulletin.getGroupName());
                            bulletinSummary.setGroupId(bulletin.getGroupId());
                            bulletinSummary.setCategory(bulletin.getCategory());
                            bulletinSummary.setCount(1);
                            bulletinSummaries.put(toPutKey, bulletinSummary);
                        }
                    }
                }

                if (bulletinMetrics.get() != null) {
                    if (logger != null) {
                        logger.info("Bulletin metrics: {}", bulletinMetrics.get());
                    }
                    for (BulletinSummary bul : bulletinMetrics.get().values()) {
                        if (!bulletinSummaries.containsKey(new BulletinKey(bul.getSourceId(),
                                bul.getSourceType(), bul.getLevel()))) {
                            removeMetricFromRegistry("nc_nifi_bulletin_count",
                                    Optional.ofNullable(bul.getSourceId()).orElse(""),
                                    Optional.of(bul.getSourceType().name()).orElse(""),
                                    Optional.ofNullable(bul.getLevel()).orElse(""));
                        }
                    }
                }
            }

            lastSentBulletinId = currMaxId;

            processGroupBulletin.putAll(bulletinsList.stream().filter(bulletin -> bulletin.getGroupId() != null)
                    .collect(Collectors.groupingBy(Bulletin::getGroupId,
                            Collectors.toMap(Bulletin::getLevel, e -> 1, Integer::sum))));

        } else {
            if (bulletinMetrics.get() != null) {
                for (BulletinSummary bul : bulletinMetrics.get().values()) {
                    removeMetricFromRegistry("nc_nifi_bulletin_count", COMPONENT_ID_TAG,
                            Optional.ofNullable(bul.getSourceId()).orElse(""));
                }
            }
        }
    }

    /**
     * Sets processor run time threshold.
     * @param newProcessorTimeThreshold new processor run time threshold.
     */
    public void setProcessorTimeThreshold(long newProcessorTimeThreshold) {
        this.processorTimeThreshold = newProcessorTimeThreshold;
    }

    /**
     * Sets connection queue size threshold.
     * @param newConnectionQueueThreshold new processor time threshold.
     */
    public void setConnectionQueueThreshold(double newConnectionQueueThreshold) {
        this.connectionQueueThreshold = newConnectionQueueThreshold;
    }

    /**
     * Converts Bulletin to string.
     * @param b bulletin
     * @return string representation of bulletin
     */
    public String bulletinToString(Bulletin b) {
        return "Bulletin{"
                + "timestamp=" + b.getTimestamp()
                + ", id=" + b.getId()
                + ", nodeAddress='" + b.getNodeAddress() + '\''
                + ", level='" + b.getLevel() + '\''
                + ", category='" + b.getCategory() + '\''
                + ", message='" + b.getMessage() + '\''
                + ", groupId='" + b.getGroupId() + '\''
                + ", groupName='" + b.getGroupName() + '\''
                + ", groupPath='" + b.getGroupPath() + '\''
                + ", sourceId='" + b.getSourceId() + '\''
                + ", sourceName='" + b.getSourceName() + '\''
                + ", sourceType=" + b.getSourceType()
                + ", flowFileUuid='" + b.getFlowFileUuid() + '\''
                + '}';
    }

    private Map<String, Integer> countRunningStatus(ProcessGroupStatus processGroupStatus,
                                                    Map<String, Integer> runningStatus) {
        if (processGroupStatus.getProcessorStatus() != null) {
            for (ProcessorStatus pcSt : processGroupStatus.getProcessorStatus()) {
                String pcStatus = pcSt.getRunStatus().name();
                if (runningStatus.containsKey(pcStatus)) {
                    runningStatus.merge(pcStatus, 1, Integer::sum);
                } else {
                    runningStatus.put(pcStatus, 1);
                }

            }
        }
        Collection<PortStatus> ports = returnListOfPorts(processGroupStatus);
        if (ports != null) {
            for (PortStatus ptSt : ports) {
                String ptStatus = ptSt.getRunStatus().name();
                if (runningStatus.containsKey(ptStatus)) {
                    runningStatus.merge(ptStatus, 1, Integer::sum);
                } else {
                    runningStatus.put(ptStatus, 1);
                }

            }
        }
        if (processGroupStatus.getProcessGroupStatus() != null) {
            processGroupStatus.getProcessGroupStatus().forEach((processGroupStatus1 -> {
                countRunningStatus(processGroupStatus1, runningStatus);
            }));
        }
        return runningStatus;
    }

    private Collection<PortStatus> returnListOfPorts(ProcessGroupStatus processGroupStatus) {
        List<PortStatus> ports = new ArrayList<>();

        if (processGroupStatus.getInputPortStatus() != null) {
            ports.addAll(processGroupStatus.getInputPortStatus());
        }

        if (processGroupStatus.getOutputPortStatus() != null) {
            ports.addAll(processGroupStatus.getOutputPortStatus());
        }

        return ports;
    }

    private Map<String, String> generateGroupPath(ProcessGroupStatus topProcessGroups,
                                                  Map<String, String> groupPath, String parentName) {
        topProcessGroups.getProcessGroupStatus().forEach((curProcessGroup) -> {
            String currentPath = curProcessGroup.getName();
            if (!"".equals(parentName)) {
                currentPath = new StringBuffer(parentName).append("/").append(curProcessGroup.getName()).toString();
            }
            groupPath.put(curProcessGroup.getId(), currentPath);
            if (curProcessGroup.getProcessGroupStatus() != null) {
                generateGroupPath(curProcessGroup, groupPath, currentPath);
            }
        });
        return groupPath;
    }
}

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

import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.status.ConnectionStatus;
import org.apache.nifi.controller.status.PortStatus;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.controller.status.ProcessorStatus;
import org.apache.nifi.controller.status.RunStatus;
import org.apache.nifi.mock.MockReportingInitializationContext;
import org.apache.nifi.reporting.Bulletin;
import org.apache.nifi.reporting.BulletinFactory;
import org.apache.nifi.reporting.ComponentType;
import org.apache.nifi.reporting.EventAccess;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.reporting.ReportingInitializationContext;
import org.apache.nifi.reporting.Severity;
import org.apache.nifi.util.MockBulletinRepository;
import org.apache.nifi.util.MockComponentLog;
import org.apache.nifi.util.MockConfigurationContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.reporting.metrics.component.ConnectionMetricName;
import org.qubership.nifi.reporting.metrics.component.ProcessorMetricName;

import static org.qubership.nifi.reporting.ComponentPrometheusReportingTask.CONNECTION_QUEUE_THRESHOLD;
import static org.qubership.nifi.reporting.ComponentPrometheusReportingTask.PROCESSOR_TIME_THRESHOLD;
import static org.qubership.nifi.reporting.ComponentPrometheusReportingTask.PROCESS_GROUP_LEVEL_THRESHOLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.QUEUED_COUNT_PG_METRIC_NAME;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.ACTIVE_THREAD_COUNT_METRIC_NAME;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.BULLETIN_COUNT_METRIC_NAME;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.QUEUED_BYTES_PG_METRIC_NAME;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.COMPONENT_COUNT_METRIC_NAME;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.ROOT_ACTIVE_THREAD_COUNT_METRIC_NAME;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.ROOT_QUEUED_COUNT_PG_METRIC_NAME;
import static org.qubership.nifi.reporting.metrics.component.ProcessGroupMetricName.ROOT_QUEUED_BYTES_PG_METRIC_NAME;

public class ComponentPrometheusReportingTaskTest {

    private ReportingInitializationContext initializationContext;
    private ReportingContext reportingContext;
    private MockComponentLog componentLogger;
    private ComponentPrometheusReportingTask task;
    private ConfigurationContext configurationContext;
    private MockBulletinRepository mockBulletinRepository;

    @BeforeEach
    public void setUp() throws Exception {
        task = new MockComponentPrometheusReportingTask();
        componentLogger = new MockComponentLog("reporting-task-id", task);
        configurationContext = new MockConfigurationContext(initReportingTaskProperties(), null, null);
        task.initProperties();
        initializationContext = new MockReportingInitializationContext();
        task.initialize(initializationContext);
        task.onScheduled(configurationContext);
    }

    @AfterEach
    public void tearDown() throws Exception {
        task.onShutDown();
    }

    @Test
    public void registerMetrics() throws InitializationException {
        mockBulletinRepository = mock(MockBulletinRepository.class);
        reportingContext = mock(ReportingContext.class);
        EventAccess eventAccess = mock(EventAccess.class);
        ProcessGroupStatus processGroupStatus = new ProcessGroupStatus();
        processGroupStatus.setId("6f6162fc-0182-1000-ffff-ffff9a480885");
        List<Bulletin> bulletinList = new ArrayList<>();
        bulletinList.add(BulletinFactory.createBulletin("1234", "test1",
                "6f6162fc-0182-1000-ffff-ffff9a480885", ComponentType.REPORTING_TASK,
                "ComponentPrometheusReportingTask", "Log Message", Severity.ERROR.name(),
                "msg1", "", ""));
        bulletinList.add(BulletinFactory.createBulletin("1235", "test2",
                "6f6162fc-0182-1000-ffff-ffff9a480886", ComponentType.REPORTING_TASK,
                "ComponentPrometheusReportingTask", "Log Message", Severity.ERROR.name(),
                "msg2", "", ""));
        bulletinList.add(BulletinFactory.createBulletin(null, null, null,
                ComponentType.FLOW_CONTROLLER, null, "Clustering", Severity.INFO.name(),
                "Event Reported for cloud-data-migration-nifi-0.cloud-data-migration-nifi-headless."
                        + "cloudbss-kube-migration-qa.svc.cluster.local:8080 -- Received first heartbeat "
                        + "from connecting node. Node connected.", null, null));
        bulletinList.add(BulletinFactory.createBulletin(null, null, null,
                ComponentType.FLOW_CONTROLLER, null, "Clustering", Severity.INFO.name(),
                "Event Reported for cloud-data-migration-nifi-0.cloud-data-migration-nifi-headless."
                        + "cloudbss-kube-migration-qa.svc.cluster.local:8080 -- Received first heartbeat "
                        + "from connecting node. Node connected.", null, null));
        bulletinList.add(BulletinFactory.createBulletin(null, null, null,
                ComponentType.REPORTING_TASK, null, "Clustering", Severity.WARNING.name(),
                "Event Reported for cloud-data-migration-nifi-0.cloud-data-migration-nifi-headless."
                        + "cloudbss-kube-migration-qa.svc.cluster.local:8080 -- Received first heartbeat "
                        + "from connecting node. Node connected.", null, null));
        bulletinList.add(BulletinFactory.createBulletin(null, null, null,
                ComponentType.REPORTING_TASK, null, "Clustering", Severity.WARNING.name(),
                "Event Reported for cloud-data-migration-nifi-0.cloud-data-migration-nifi-headless."
                        + "cloudbss-kube-migration-qa.svc.cluster.local:8080 -- Received first heartbeat "
                        + "from connecting node. Node connected.", null, null));
        bulletinList.add(BulletinFactory.createBulletin("1236", "test3",
                "6f6162fc-0182-1000-ffff-ffff9a480887", ComponentType.CONTROLLER_SERVICE,
                "ComponentPrometheusReportingTask", "Log Message", Severity.ERROR.name(),
                "msg3", "", ""));
        bulletinList.add(BulletinFactory.createBulletin("1236", "test3",
                "6f6162fc-0182-1000-ffff-ffff9a480887", ComponentType.CONTROLLER_SERVICE,
                "ComponentPrometheusReportingTask", "Log Message", Severity.ERROR.name(),
                "msg3", "", ""));
        bulletinList.add(BulletinFactory.createBulletin("1237", "test3",
                "6f6162fc-0182-1000-ffff-ffff9a480888", ComponentType.CONTROLLER_SERVICE,
                "ComponentPrometheusReportingTask", "Log Message", Severity.ERROR.name(),
                "msg3", "NiFi/PG1", ""));
        bulletinList.add(BulletinFactory.createBulletin("1238", "test3",
                "6f6162fc-0182-1000-ffff-ffff9a480889", ComponentType.PROCESSOR,
                "ComponentPrometheusReportingTask", "Log Message", Severity.ERROR.name(),
                "msg3", "NiFi/PG1", ""));
        //check that JVM metrics are present:
        assertEquals(1, task.getMeterRegistry().find("jvm.memory.max").
                tags("area", "nonheap", "id", "Metaspace").
                gauges().size());
        when(reportingContext.getBulletinRepository()).thenReturn(mockBulletinRepository);
        when(eventAccess.getControllerStatus()).thenReturn(processGroupStatus);
        when(reportingContext.getEventAccess()).thenReturn(eventAccess);
        when(mockBulletinRepository.findBulletins(any())).thenReturn(bulletinList);
        task.registerMetrics(reportingContext);
        assertEquals(7, task.getMeterRegistry().find("nc_nifi_bulletin_count").gauges().size());
        assertEquals(2, task.getMeterRegistry().find("nc_nifi_bulletin_count").
                tags("component_type", ComponentType.FLOW_CONTROLLER.name(),
                        "level", Severity.INFO.name()).
                gauge().measure().iterator().next().getValue());
        assertEquals(2, task.getMeterRegistry().find("nc_nifi_bulletin_count").
                tags("component_type", ComponentType.REPORTING_TASK.name(),
                        "level", Severity.WARNING.name()).
                gauge().measure().iterator().next().getValue());
        assertEquals(1, task.getMeterRegistry().find("nc_nifi_bulletin_count").
                tags("component_type", ComponentType.REPORTING_TASK.name(),
                        "component_id", "6f6162fc-0182-1000-ffff-ffff9a480885",
                        "level", Severity.ERROR.name()).
                gauge().measure().iterator().next().getValue());
        assertEquals(1, task.getMeterRegistry().find("nc_nifi_bulletin_count").
                tags("component_type", ComponentType.REPORTING_TASK.name(),
                        "component_id", "6f6162fc-0182-1000-ffff-ffff9a480886",
                        "level", Severity.ERROR.name()).
                gauge().measure().iterator().next().getValue());
        assertEquals(2, task.getMeterRegistry().find("nc_nifi_bulletin_count").
                tags("component_type", ComponentType.CONTROLLER_SERVICE.name(),
                        "component_id", "6f6162fc-0182-1000-ffff-ffff9a480887",
                        "level", Severity.ERROR.name()).
                gauge().measure().iterator().next().getValue());
        assertEquals(1, task.getMeterRegistry().find("nc_nifi_bulletin_count").
                tags("component_type", ComponentType.CONTROLLER_SERVICE.name(),
                        "component_id", "6f6162fc-0182-1000-ffff-ffff9a480888",
                        "level", Severity.ERROR.name()).
                gauge().measure().iterator().next().getValue());
        assertEquals(1, task.getMeterRegistry().find("nc_nifi_bulletin_count").
                tags("component_type", ComponentType.PROCESSOR.name(),
                        "component_id", "6f6162fc-0182-1000-ffff-ffff9a480889",
                        "level", Severity.ERROR.name()).
                gauge().measure().iterator().next().getValue());
        //recreate bulletin list with two new elements:
        bulletinList = new ArrayList<>();
        bulletinList.add(BulletinFactory.createBulletin("1239", "test1",
                "6f6162fc-0182-1000-ffff-ffff9a480885", ComponentType.REPORTING_TASK,
                "ComponentPrometheusReportingTask", "Log Message", Severity.ERROR.name(),
                "msg1", "", ""));
        bulletinList.add(BulletinFactory.createBulletin("1240", "test2",
                "6f6162fc-0182-1000-ffff-ffff9a480886", ComponentType.REPORTING_TASK,
                "ComponentPrometheusReportingTask", "Log Message", Severity.ERROR.name(),
                "msg2", "", ""));
        when(mockBulletinRepository.findBulletins(any())).thenReturn(bulletinList);
        task.registerMetrics(reportingContext);
        assertEquals(2, task.getMeterRegistry().find("nc_nifi_bulletin_count").gauges().size());
        //recreate empty bulletin list:
        bulletinList = new ArrayList<>();
        when(mockBulletinRepository.findBulletins(any())).thenReturn(bulletinList);
        task.registerMetrics(reportingContext);
        assertEquals(0, task.getMeterRegistry().find("nc_nifi_bulletin_count").gauges().size());
    }

    private ProcessorStatus createProcessor(String id, String name, RunStatus runStatus) {
        ProcessorStatus processor = new ProcessorStatus();
        processor.setId(id);
        processor.setName(name);
        processor.setRunStatus(runStatus);
        return processor;
    }

    private PortStatus createPortStatus(String id, String name, RunStatus runStatus) {
        PortStatus port = new PortStatus();
        port.setId(id);
        port.setName(name);
        port.setRunStatus(runStatus);
        return port;
    }

    private List<ProcessGroupStatus> createTestPG() {
        List<ProcessGroupStatus> testProcessGroups = new ArrayList<>();
        ProcessGroupStatus processGroupStatus1 = new ProcessGroupStatus();
        processGroupStatus1.setId("TestPGId#1");
        processGroupStatus1.setName("TestPGName#1");
        processGroupStatus1.setActiveThreadCount(3);
        processGroupStatus1.setQueuedCount(25);
        processGroupStatus1.setQueuedContentSize(345L);

        processGroupStatus1.setProcessorStatus(Arrays.asList(
                createProcessor("123", "processName1", RunStatus.Invalid),
                createProcessor("456", "processName2", RunStatus.Disabled)
        ));

        processGroupStatus1.setInputPortStatus(Arrays.asList(
                createPortStatus("1213", "portName1", RunStatus.Invalid),
                createPortStatus("1415", "portName2", RunStatus.Stopped)
        ));

        testProcessGroups.add(processGroupStatus1);

        ProcessGroupStatus processGroupStatus2 = new ProcessGroupStatus();
        processGroupStatus2.setId("TestPGId#2");
        processGroupStatus2.setName("TestPGName#2");
        processGroupStatus2.setActiveThreadCount(5);
        processGroupStatus2.setQueuedCount(50);
        processGroupStatus2.setQueuedContentSize(4235L);

        processGroupStatus2.setProcessorStatus(Arrays.asList(
                createProcessor("789", "processName3", RunStatus.Running),
                createProcessor("1011", "processName4", RunStatus.Stopped)
        ));

        testProcessGroups.add(processGroupStatus2);

        return testProcessGroups;
    }

    private ProcessGroupStatus createTopProcessGroup() {
        ProcessGroupStatus topProcessGroup = new ProcessGroupStatus();
        topProcessGroup.setId("rootId");
        topProcessGroup.setName("Nifi Flow");
        List<ProcessGroupStatus> childPg = createTestPG();
        topProcessGroup.setProcessGroupStatus(childPg);
        topProcessGroup.setActiveThreadCount(23);
        topProcessGroup.setQueuedCount(2000);
        topProcessGroup.setQueuedContentSize(5_000_000_000L);
        return topProcessGroup;
    }

    private ProcessorStatus createProcessor2(String id, String name, String groupId, RunStatus runStatus) {
        ProcessorStatus processor = new ProcessorStatus();
        processor.setId(id);
        processor.setName(name);
        processor.setRunStatus(runStatus);
        if (runStatus == RunStatus.Running) {
            processor.setInvocations(100);
            processor.setProcessingNanos(200_000_000_000L); //200 seconds
        } else {
            processor.setInvocations(0);
            processor.setProcessingNanos(0L); //0 seconds
        }
        processor.setGroupId(groupId);
        return processor;
    }

    private ConnectionStatus createConnectionStatus(String id, String name, String groupId,
                                                    String sourceId, String destId,
                                                    boolean belowThreshold) {
        ConnectionStatus con = new ConnectionStatus();
        con.setId(id);
        con.setName(name);
        if (belowThreshold) {
            con.setQueuedCount(8);
            con.setQueuedBytes(1_000_000L);
        } else {
            con.setQueuedCount(8000);
            con.setQueuedBytes(1073741824L);
        }
        con.setBackPressureObjectThreshold(10000);
        con.setBackPressureDataSizeThreshold("1 GB");
        con.setGroupId(groupId);
        con.setSourceId(sourceId);
        con.setSourceName("Name " + sourceId);
        con.setDestinationId(destId);
        con.setDestinationName("Name " + destId);
        return con;
    }

    private List<ProcessGroupStatus> createTestPG2() {
        List<ProcessGroupStatus> testProcessGroups = new ArrayList<>();
        ProcessGroupStatus processGroupStatus1 = new ProcessGroupStatus();
        processGroupStatus1.setId("TestPGId2#1");
        processGroupStatus1.setName("TestPGName#1");
        processGroupStatus1.setActiveThreadCount(4);
        processGroupStatus1.setQueuedCount(40000);
        processGroupStatus1.setQueuedContentSize(5_000_000_000L);
        testProcessGroups.add(processGroupStatus1);

        ProcessGroupStatus processGroupStatus2 = new ProcessGroupStatus();
        processGroupStatus2.setId("TestPGId2#2");
        processGroupStatus2.setName("TestPGName2#2");
        processGroupStatus2.setActiveThreadCount(4);
        processGroupStatus2.setQueuedCount(40000);
        processGroupStatus2.setQueuedContentSize(5_000_000_000L);

        processGroupStatus2.setProcessorStatus(Arrays.asList(
                createProcessor2("12345", "processor21", "TestPGId2#2", RunStatus.Running),
                createProcessor2("67890", "processor22", "TestPGId2#2", RunStatus.Running),
                createProcessor2("11111", "processor23", "TestPGId2#2", RunStatus.Stopped)
        ));

        processGroupStatus2.setConnectionStatus(Arrays.asList(
                createConnectionStatus("0-12345", "connection-in-21", "TestPGId2#2",
                        "0", "12345", false),
                createConnectionStatus("12345-67890", "connection-21-22", "TestPGId2#2",
                        "12345", "67890", false),
                createConnectionStatus("67890-1", "connection-22-out", "TestPGId2#2",
                        "67890", "1", false),
                createConnectionStatus("0-11111", "connection-in-23", "TestPGId2#2",
                        "0", "11111", true),
                createConnectionStatus("11111-1", "connection-23-out", "TestPGId2#2",
                        "11111", "1", true)
        ));

        testProcessGroups.add(processGroupStatus2);

        return testProcessGroups;
    }

    private ProcessGroupStatus createTopProcessGroup2() {
        ProcessGroupStatus topProcessGroup = new ProcessGroupStatus();
        topProcessGroup.setId("rootId");
        topProcessGroup.setName("Nifi Flow");
        List<ProcessGroupStatus> childPg = createTestPG2();
        topProcessGroup.setProcessGroupStatus(childPg);
        return topProcessGroup;
    }

    @Test
    public void registerMetricsForProcessGroup() throws InitializationException {
        mockBulletinRepository = mock(MockBulletinRepository.class);
        reportingContext = mock(ReportingContext.class);
        EventAccess eventAccess = mock(EventAccess.class);
        ProcessGroupStatus processGroupStatus = createTopProcessGroup();
        List<Bulletin> bulletinList = new ArrayList<>();
        bulletinList.add(BulletinFactory.createBulletin("TestPGId#1", "test1",
                "6f6162fc-0182-1000-ffff-ffff9a480885", ComponentType.REPORTING_TASK,
                "ComponentPrometheusReportingTask", "Log Message", Severity.ERROR.name(),
                "msg1", "", ""));
        bulletinList.add(BulletinFactory.createBulletin("NiFi Flow", "NiFi Flow",
                "6f6162fc-0182-1000-ffff-ffff9a480887", ComponentType.CONTROLLER_SERVICE,
                "TestCSName#01", "Log Message", Severity.ERROR.name(), "msg1",
                "", null));
        when(reportingContext.getBulletinRepository()).thenReturn(mockBulletinRepository);
        when(eventAccess.getControllerStatus()).thenReturn(processGroupStatus);
        when(reportingContext.getEventAccess()).thenReturn(eventAccess);
        when(mockBulletinRepository.findBulletins(any())).thenReturn(bulletinList);
        task.registerMetrics(reportingContext);
        assertEquals(2, task.getMeterRegistry().find(ACTIVE_THREAD_COUNT_METRIC_NAME.getName()).gauges().size());
        assertEquals(2, task.getMeterRegistry().find(QUEUED_COUNT_PG_METRIC_NAME.getName()).gauges().size());
        assertEquals(2, task.getMeterRegistry().find(QUEUED_BYTES_PG_METRIC_NAME.getName()).gauges().size());
        assertEquals(1, task.getMeterRegistry().find(BULLETIN_COUNT_METRIC_NAME.getName()).gauges().size());
        assertEquals(5, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).gauges().size());

        assertEquals(3, task.getMeterRegistry().find(ACTIVE_THREAD_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1").
                gauge().measure().iterator().next().getValue());
        assertEquals(25, task.getMeterRegistry().find(QUEUED_COUNT_PG_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1").
                gauge().measure().iterator().next().getValue());
        assertEquals(345, task.getMeterRegistry().find(QUEUED_BYTES_PG_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1").
                gauge().measure().iterator().next().getValue());
        assertEquals(1, task.getMeterRegistry().find(BULLETIN_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1").
                gauge().measure().iterator().next().getValue());
        assertEquals(2, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1",
                        "runningStatus", "Invalid").
                gauge().measure().iterator().next().getValue());
        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1",
                        "runningStatus", "Stopped").
                gauge().measure().iterator().next().getValue());
        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1",
                        "runningStatus", "Disabled").
                gauge().measure().iterator().next().getValue());
        assertEquals(5, task.getMeterRegistry().find(ACTIVE_THREAD_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#2").
                gauge().measure().iterator().next().getValue());
        assertEquals(50, task.getMeterRegistry().find(QUEUED_COUNT_PG_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#2").
                gauge().measure().iterator().next().getValue());
        assertEquals(4235L, task.getMeterRegistry().find(QUEUED_BYTES_PG_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#2").
                gauge().measure().iterator().next().getValue());
        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#2",
                        "runningStatus", "Stopped").
                gauge().measure().iterator().next().getValue());
        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#2",
                        "runningStatus", "Running").
                gauge().measure().iterator().next().getValue());
    }

    private Map<PropertyDescriptor, String> initReportingTaskProperties() {
        Map<PropertyDescriptor, String> properties = new HashMap<>();

        properties.put(PROCESSOR_TIME_THRESHOLD, "150 sec");
        properties.put(CONNECTION_QUEUE_THRESHOLD, "70");
        properties.put(PROCESS_GROUP_LEVEL_THRESHOLD, "2");
        return properties;
    }

    @Test
    public void registerMetricsForTopProcessGroupComponents() throws InitializationException, IOException {
        mockBulletinRepository = mock(MockBulletinRepository.class);
        reportingContext = mock(ReportingContext.class);
        EventAccess eventAccess = mock(EventAccess.class);
        ProcessGroupStatus topProcessGroupStatus = createTopProcessGroup();
        when(reportingContext.getBulletinRepository()).thenReturn(mockBulletinRepository);
        when(eventAccess.getControllerStatus()).thenReturn(topProcessGroupStatus);
        when(reportingContext.getEventAccess()).thenReturn(eventAccess);
        task.registerMetrics(reportingContext);
        assertEquals(5, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).gauges().size());

        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#2",
                        "runningStatus", "Stopped").
                gauge().measure().iterator().next().getValue());

        assertEquals(2, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1",
                        "runningStatus", "Invalid").
                gauge().measure().iterator().next().getValue());

        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1",
                        "runningStatus", "Stopped").
                gauge().measure().iterator().next().getValue());

        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#2",
                        "runningStatus", "Running").
                gauge().measure().iterator().next().getValue());

        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1",
                        "runningStatus", "Disabled").
                gauge().measure().iterator().next().getValue());

        for (ProcessGroupStatus processGroupStatus : topProcessGroupStatus.getProcessGroupStatus()) {
            if ("TestPGId#1".equals(processGroupStatus.getId())) {
                processGroupStatus.setProcessorStatus(Arrays.asList(
                        createProcessor("789", "processName3", RunStatus.Validating),
                        createProcessor("1011", "processName4", RunStatus.Stopped)
                ));
            }
        }

        when(eventAccess.getControllerStatus()).thenReturn(topProcessGroupStatus);
        task.registerMetrics(reportingContext);
        assertEquals(6, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).gauges().size());

        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#2",
                        "runningStatus", "Stopped").
                gauge().measure().iterator().next().getValue());

        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1",
                        "runningStatus", "Invalid").
                gauge().measure().iterator().next().getValue());

        assertEquals(2, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1",
                        "runningStatus", "Stopped").
                gauge().measure().iterator().next().getValue());

        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#2",
                        "runningStatus", "Running").
                gauge().measure().iterator().next().getValue());

        assertEquals(0, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1",
                        "runningStatus", "Disabled").
                gauge().measure().iterator().next().getValue());

        assertEquals(0, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#1",
                        "runningStatus", "Disabled").
                gauge().measure().iterator().next().getValue());


        topProcessGroupStatus.getProcessGroupStatus().removeIf(processGroupStatus -> "TestPGId#1".
                equals(processGroupStatus.getId()));
        when(eventAccess.getControllerStatus()).thenReturn(topProcessGroupStatus);
        task.registerMetrics(reportingContext);

        assertEquals(2, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).gauges().size());
        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#2",
                        "runningStatus", "Stopped").
                gauge().measure().iterator().next().getValue());
        assertEquals(1, task.getMeterRegistry().find(COMPONENT_COUNT_METRIC_NAME.getName()).
                tags("group_id", "TestPGId#2",
                        "runningStatus", "Running").
                gauge().measure().iterator().next().getValue());
    }

    @Test
    public void registerNiFiMetricsForTopProcessGroupComponents() throws InitializationException, IOException {
        mockBulletinRepository = mock(MockBulletinRepository.class);
        reportingContext = mock(ReportingContext.class);
        EventAccess eventAccess = mock(EventAccess.class);
        ProcessGroupStatus processGroupStatus = createTopProcessGroup();
        when(reportingContext.getBulletinRepository()).thenReturn(mockBulletinRepository);
        when(eventAccess.getControllerStatus()).thenReturn(processGroupStatus);
        when(reportingContext.getEventAccess()).thenReturn(eventAccess);
        task.registerMetrics(reportingContext);
        assertEquals(1, task.getMeterRegistry().find(ROOT_ACTIVE_THREAD_COUNT_METRIC_NAME.getName()).gauges().size());
        assertEquals(1, task.getMeterRegistry().find(ROOT_QUEUED_COUNT_PG_METRIC_NAME.getName()).gauges().size());
        assertEquals(1, task.getMeterRegistry().find(ROOT_QUEUED_BYTES_PG_METRIC_NAME.getName()).gauges().size());


        assertEquals(23, task.getMeterRegistry().find(ROOT_ACTIVE_THREAD_COUNT_METRIC_NAME.getName()).
                tags("component_id", "rootId").
                gauge().measure().iterator().next().getValue());
        assertEquals(2000, task.getMeterRegistry().find(ROOT_QUEUED_COUNT_PG_METRIC_NAME.getName()).
                tags("component_id", "rootId").
                gauge().measure().iterator().next().getValue());
        assertEquals(5_000_000_000L, task.getMeterRegistry().find(ROOT_QUEUED_BYTES_PG_METRIC_NAME.getName()).
                tags("component_id", "rootId").
                gauge().measure().iterator().next().getValue());
    }

    @Test
    public void registerJvmMetrics() throws Exception {
        //check that JVM metrics are present:
        assertEquals(1, task.getMeterRegistry().find("jvm.memory.max").
                tags("area", "nonheap", "id", "Metaspace").
                gauges().size());
        //stop task:
        task.onShutDown();
        //and start again:
        task.onScheduled(configurationContext);
        //check that JVM metrics are present after restart:
        assertEquals(1, task.getMeterRegistry().find("jvm.memory.max").
                tags("area", "nonheap", "id", "Metaspace").
                gauges().size());
    }


    @Test
    public void registerNiFiJvmMetrics() throws Exception {
        //check that NiFi JVM metrics are present:
        assertEquals(1, task.getMeterRegistry().find("nifi_jvm_threads_count").
                gauges().size());
        assertEquals(1, task.getMeterRegistry().find("nifi_jvm_uptime").
                gauges().size());
        //stop task:
        task.onShutDown();
        //and start again:
        task.onScheduled(configurationContext);
        //check that NiFi JVM metrics are present after restart:
        assertEquals(1, task.getMeterRegistry().find("nifi_jvm_threads_count").
                gauges().size());
        assertEquals(1, task.getMeterRegistry().find("nifi_jvm_uptime").
                gauges().size());
    }

    @Test
    public void registerMetricsForProcessors() {
        mockBulletinRepository = mock(MockBulletinRepository.class);
        reportingContext = mock(ReportingContext.class);
        EventAccess eventAccess = mock(EventAccess.class);
        ProcessGroupStatus topProcessGroupStatus = createTopProcessGroup2();
        when(reportingContext.getBulletinRepository()).thenReturn(mockBulletinRepository);
        when(eventAccess.getControllerStatus()).thenReturn(topProcessGroupStatus);
        when(reportingContext.getEventAccess()).thenReturn(eventAccess);
        task.registerMetrics(reportingContext);
        assertEquals(2, task.getMeterRegistry().
                find(ProcessorMetricName.TASKS_TIME_TOTAL_METRIC_NAME.getName()).gauges().size());
        assertEquals(2, task.getMeterRegistry().
                find(ProcessorMetricName.TASKS_COUNT_METRIC_NAME.getName()).gauges().size());

        assertEquals(200_000_000_000L, task.getMeterRegistry().
                find(ProcessorMetricName.TASKS_TIME_TOTAL_METRIC_NAME.getName()).
                tags("parent_id", "TestPGId2#2",
                     "component_id", "12345").
                gauge().measure().iterator().next().getValue());
        assertEquals(100, task.getMeterRegistry().
                find(ProcessorMetricName.TASKS_COUNT_METRIC_NAME.getName()).
                tags("parent_id", "TestPGId2#2",
                    "component_id", "12345").
                gauge().measure().iterator().next().getValue());


        assertEquals(3, task.getMeterRegistry().
                find(ConnectionMetricName.QUEUED_BYTES_METRIC_NAME.getName()).gauges().size());
        assertEquals(3, task.getMeterRegistry().
                find(ConnectionMetricName.QUEUED_COUNT_METRIC_NAME.getName()).gauges().size());
        assertEquals(3, task.getMeterRegistry().
                find(ConnectionMetricName.PERCENT_USED_BYTES_METRIC_NAME.getName()).gauges().size());
        assertEquals(3, task.getMeterRegistry().
                find(ConnectionMetricName.PERCENT_USED_COUNT_METRIC_NAME.getName()).gauges().size());

        assertEquals(1073741824L, task.getMeterRegistry().
                find(ConnectionMetricName.QUEUED_BYTES_METRIC_NAME.getName()).
                tags("parent_id", "TestPGId2#2",
                    "component_id", "12345-67890").
                gauge().measure().iterator().next().getValue());
        assertEquals(8000, task.getMeterRegistry().
                find(ConnectionMetricName.QUEUED_COUNT_METRIC_NAME.getName()).
                tags("parent_id", "TestPGId2#2",
                    "component_id", "12345-67890").
                gauge().measure().iterator().next().getValue());
        assertEquals(1.0, task.getMeterRegistry().
                find(ConnectionMetricName.PERCENT_USED_BYTES_METRIC_NAME.getName()).
                tags("parent_id", "TestPGId2#2",
                    "component_id", "12345-67890").
                gauge().measure().iterator().next().getValue());
        assertEquals(0.8, task.getMeterRegistry().
                find(ConnectionMetricName.PERCENT_USED_COUNT_METRIC_NAME.getName()).
                tags("parent_id", "TestPGId2#2",
                    "component_id", "12345-67890").
                gauge().measure().iterator().next().getValue());
    }
}

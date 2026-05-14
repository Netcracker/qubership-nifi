package org.qubership.nifi.reporting;

import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.status.ProcessGroupStatus;
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
import org.apache.nifi.util.MockConfigurationContext;
import org.apache.nifi.util.MockControllerServiceLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.service.QubershipPrometheusRecordSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.nifi.reporting.AbstractPrometheusReportingTask.METER_REGISTRY_PROVIDER;
import static org.qubership.nifi.reporting.ComponentPrometheusReportingTask.CONNECTION_QUEUE_THRESHOLD;
import static org.qubership.nifi.reporting.ComponentPrometheusReportingTask.PROCESSOR_TIME_THRESHOLD;
import static org.qubership.nifi.reporting.ComponentPrometheusReportingTask.PROCESS_GROUP_LEVEL_THRESHOLD;

public class ComponentPrometheusReportingTaskCSTest {

    private ConfigurationContext configurationContext;
    private ReportingInitializationContext initializationContext;
    private ReportingContext reportingContext;
    private ComponentPrometheusReportingTask task;
    private QubershipPrometheusRecordSink recordSink;
    private MockBulletinRepository mockBulletinRepository;

    private MockControllerServiceLookup serviceLookup;

    @BeforeEach
    public void setUp() throws Exception {
        task = new MockComponentPrometheusReportingTask();
        recordSink = new QubershipPrometheusRecordSink();
        serviceLookup = new SimpleMockControllerServiceLookup();
        serviceLookup.addControllerService(recordSink, "record-sink-id");
        configurationContext = new MockConfigurationContext(recordSink, initReportingTaskProperties(),
                serviceLookup, null);
        task.initProperties();
        initializationContext = new MockReportingInitializationContext();
        task.initialize(initializationContext);
        task.onScheduled(configurationContext);
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


    private Map<PropertyDescriptor, String> initReportingTaskProperties() {
        Map<PropertyDescriptor, String> properties = new HashMap<>();
        properties.put(PROCESSOR_TIME_THRESHOLD, "150 sec");
        properties.put(CONNECTION_QUEUE_THRESHOLD, "70");
        properties.put(PROCESS_GROUP_LEVEL_THRESHOLD, "2");
        properties.put(METER_REGISTRY_PROVIDER, "record-sink-id");
        return properties;
    }

    @AfterEach
    public void tearDown() throws Exception {
        task.onShutDown();
    }

}

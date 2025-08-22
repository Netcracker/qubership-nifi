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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.apache.nifi.diagnostics.StorageUsage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import org.apache.nifi.action.Action;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.components.state.StateManager;
import org.apache.nifi.controller.ControllerServiceLookup;
import org.apache.nifi.controller.status.ConnectionStatus;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.controller.status.ProcessorStatus;
import org.apache.nifi.provenance.ProvenanceEventRecord;
import org.apache.nifi.provenance.ProvenanceEventRepository;
import org.apache.nifi.reporting.Bulletin;
import org.apache.nifi.reporting.BulletinRepository;
import org.apache.nifi.reporting.EventAccess;
import org.apache.nifi.reporting.ReportingContext;
import org.apache.nifi.reporting.Severity;
import org.qubership.nifi.reporting.ComponentMetricsReportingTask;


public class ComponentMetricsReportingTaskTest {
    private final String namespace = System.getenv("NAMESPACE");
    private static final String TEST_HOSTNAME = "test";


    private ProcessGroupStatus createTestPG()
    {
        ProcessGroupStatus pg = new ProcessGroupStatus();
        pg.setId("TestPGId#1");
        pg.setName("TestPGName#1");

        //add processors:
        pg.setProcessorStatus(Arrays.asList(
            createTestProcessorStatus(1, "Root", true),
            createTestProcessorStatus(2, "Root", false),
            createTestProcessorStatus(3, "Root", true)
        ));
        //add connections:
        pg.setConnectionStatus(Arrays.asList(
            createTestConnectionStatus(1, "Root", true),
            createTestConnectionStatus(2, "Root", false),
            createTestConnectionStatus(3, "Root", true)
        ));

        //nested PG:
        ProcessGroupStatus pg2 = new ProcessGroupStatus();
        pg2.setId("TestPGId#2");
        pg2.setName("TestPGName#2");

        //add processors:
        pg2.setProcessorStatus(Arrays.asList(
            createTestProcessorStatus(1, "Nested", true),
            createTestProcessorStatus(2, "Nested", false),
            createTestProcessorStatus(3, "Nested", true),
            createTestProcessorStatus(4, "Nested = value1, value2", false)
        ));
        //add connections:
        pg2.setConnectionStatus(Arrays.asList(
            createTestConnectionStatus(1, "Nested", true),
            createTestConnectionStatus(2, "Nested", false),
            createTestConnectionStatus(3, "Nested", true),
            createTestConnectionStatus(4, "Nested = value1, value2", false)
        ));

        //add child PGs:
        pg.setProcessGroupStatus(Arrays.asList(pg2));
        return pg;
    }

    private ProcessorStatus createTestProcessorStatus(int num, String namePrefix, boolean exceedsThreshold)
    {
        ProcessorStatus st = new ProcessorStatus();
        st.setId("TestId#" + namePrefix + "#" + num);
        st.setName("TestName#" + namePrefix + "#" + num);
        if (exceedsThreshold) {
            st.setProcessingNanos(151_000_000_000l);
        }
        else {
            st.setProcessingNanos(1_000_000_000l);
        }
        st.setInvocations(10);
        st.setInputCount(1);
        st.setOutputCount(2);
        st.setBytesRead(200);
        st.setBytesWritten(300);
        st.setBytesSent(400);
        st.setBytesReceived(500);
        return st;
    }

    private ConnectionStatus createTestConnectionStatus(int num, String namePrefix, boolean exceedsThreshold)
    {
        ConnectionStatus st = new ConnectionStatus();
        st.setId("TestId#" + namePrefix + "#" + num);
        st.setName("TestName#" + namePrefix + "#" + num);
        if (exceedsThreshold) {
            st.setQueuedCount(9000);
        }
        else {
            st.setQueuedCount(100);
        }
        st.setQueuedBytes(100);
        st.setInputCount(1);
        st.setOutputCount(2);
        st.setBackPressureBytesThreshold(1000_000l);
        st.setBackPressureObjectThreshold(10000);
        st.setDestinationName("TestDestName");
        st.setSourceName("TestSrcName");
        st.setDestinationId("TestDestId");
        st.setSourceId("TestSrcId");
        return st;
    }

    protected String escapeKeysOrTagValue(String str) {
        if (str == null) {
            return null;
        }
        //In tag keys, tag values, and field keys, you must escape: space, comma, equal siqn:
        return str.replaceAll(" ", "\\\\ ").replaceAll("=", "\\\\=").replaceAll(",", "\\\\,");
    }

    protected String escapeFieldValue(String str) {
        if (str == null) {
            return null;
        }
        //In field values you must escape: backslash, double quotes:
        return str.replaceAll("\\\\", "\\\\\\\\").replaceAll("\"", "\\\\\"");
    }

    protected String escapeRegEx(String str) {
        if (str == null) {
            return null;
        }
        //replace backslash:
        return str.replaceAll("\\\\", "\\\\\\\\").replaceAll("\\(", "\\\\(").replaceAll("\\)", "\\\\)");
    }

    private ComponentMetricsReportingTask createTask(long processorThreshold, double connectionThreshold)
    {
        ComponentMetricsReportingTask task = new ComponentMetricsReportingTask();
        task.namespace = this.namespace;
        task.hostname = TEST_HOSTNAME;
        task.setProcessorTimeThreshold(processorThreshold);
        task.setConnectionQueueThreshold(connectionThreshold);
        return task;
    }

    private String getExpectedConnectionMetrics(String namePrefix, int num)
    {
        StringBuilder res = new StringBuilder();
        res.append("nifi_connections_monitoring,namespace=").append(namespace)
           .append(",connection_uuid=").append("TestId#").append(escapeKeysOrTagValue(namePrefix)).append("#").append(num)
           .append(",hostname=").append(TEST_HOSTNAME)
           .append(",sourceId=").append("TestSrcId")
           .append(",destinationId=").append("TestDestId")
           .append(" name=\"").append("TestName#").append(escapeFieldValue(namePrefix)).append("#").append(num)
           .append("\",sourceName=\"").append("TestSrcName")
           .append("\",destinationName=\"").append("TestDestName")
           .append("\",queuedCount=").append(num == 2 || num == 4 ? 100 : 9000)
           .append(",queuedBytes=").append(100)
           .append(",backPressureObjectThreshold=").append(10000)
           .append(",backPressureBytesThreshold=").append(1000_000l);

        //escape regex:
        res = new StringBuilder(escapeRegEx(res.toString()));
        res.append(" [0-9]+");
        return res.toString();
    }

    private String getExpectedProcessorMetrics(String namePrefix, int num)
    {
        StringBuilder res = new StringBuilder();
        res.append("nifi_processors_monitoring,namespace=").append(namespace)
           .append(",processor_uuid=").append("TestId#").append(escapeKeysOrTagValue(namePrefix)).append("#").append(num)
           .append(",hostname=").append(TEST_HOSTNAME)
           .append(",full_name=").append("TestName#").append(escapeKeysOrTagValue(namePrefix)).append("#").append(num).append("(").append("TestId#").append(escapeKeysOrTagValue(namePrefix)).append("#").append(num)
           .append(") name=\"").append("TestName#").append(escapeFieldValue(namePrefix)).append("#").append(num)
           .append("\",processingNanos=").append(num == 2 || num == 4 ? 1_000_000_000l : 151_000_000_000l)
           .append(",invocations=").append(10)
           .append(",inputCount=").append(1)
           .append(",outputCount=").append(2)
           .append(",bytesRead=").append(200)
           .append(",bytesWritten=").append(300)
           .append(",bytesReceived=").append(500)
           .append(",bytesSent=").append(400);

        //escape regex:
        res = new StringBuilder(escapeRegEx(res.toString()));
        res.append(" [0-9]+");
        return res.toString();
    }



    @Test
    public void testCreateInfluxMessage() {
        ComponentMetricsReportingTask task = createTask(150_000_000_000l, 0.8);
        ProcessGroupStatus pg = createTestPG();
        ReportingContext context = new TestReportingContext(pg);

        //get message:
        String message = task.createInfluxMessage(context);
        String[] lines = message.split("\n");
        Assertions.assertEquals(lines.length, 8);
        Assertions.assertLinesMatch(
            Arrays.asList(
                getExpectedConnectionMetrics("Root", 1),
                getExpectedConnectionMetrics("Root", 3),
                getExpectedConnectionMetrics("Nested", 1),
                getExpectedConnectionMetrics("Nested", 3),
                getExpectedProcessorMetrics("Root", 1),
                getExpectedProcessorMetrics("Root", 3),
                getExpectedProcessorMetrics("Nested", 1),
                getExpectedProcessorMetrics("Nested", 3)
            ),
            Arrays.asList(lines)
        );
    }

    @Test
    public void testCreateInfluxMessageLowThresholds() {
        ComponentMetricsReportingTask task = createTask(0l, 0);
        ProcessGroupStatus pg = createTestPG();
        ReportingContext context = new TestReportingContext(pg);

        //get message:
        String message = task.createInfluxMessage(context);
        String[] lines = message.split("\n");
        Assertions.assertEquals(lines.length, 14);
        Assertions.assertLinesMatch(
            Arrays.asList(
                getExpectedConnectionMetrics("Root", 1),
                getExpectedConnectionMetrics("Root", 2),
                getExpectedConnectionMetrics("Root", 3),
                getExpectedConnectionMetrics("Nested", 1),
                getExpectedConnectionMetrics("Nested", 2),
                getExpectedConnectionMetrics("Nested", 3),
                getExpectedConnectionMetrics("Nested = value1, value2", 4),
                getExpectedProcessorMetrics("Root", 1),
                getExpectedProcessorMetrics("Root", 2),
                getExpectedProcessorMetrics("Root", 3),
                getExpectedProcessorMetrics("Nested", 1),
                getExpectedProcessorMetrics("Nested", 2),
                getExpectedProcessorMetrics("Nested", 3),
                getExpectedProcessorMetrics("Nested = value1, value2", 4)
            ),
            Arrays.asList(lines)
        );
    }

    @Test
    public void testCreateInfluxMessageHighThresholds() {
        ComponentMetricsReportingTask task = createTask(300_000_000_000l, 1.0);
        ProcessGroupStatus pg = createTestPG();
        ReportingContext context = new TestReportingContext(pg);

        //get message:
        String message = task.createInfluxMessage(context);
        Assertions.assertTrue("".equals(message));
    }



    class TestEventAccess implements EventAccess
    {
        private ProcessGroupStatus rootSt;
        public TestEventAccess(ProcessGroupStatus rootSt) {
            this.rootSt = rootSt;
        }

        @Override
        public ProcessGroupStatus getControllerStatus() {
            return this.rootSt;
        }

        @Override
        public ProcessGroupStatus getGroupStatus(String string) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public List<ProvenanceEventRecord> getProvenanceEvents(long l, int i) throws IOException {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public ProvenanceEventRepository getProvenanceRepository() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public List<Action> getFlowChanges(int i, int i1) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public long getTotalBytesRead() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public long getTotalBytesWritten() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public long getTotalBytesSent() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public long getTotalBytesReceived() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public Map<String, StorageUsage> getProvenanceRepositoryStorageUsage() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public Map<String, StorageUsage> getContentRepositoryStorageUsage() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public StorageUsage getFlowFileRepositoryStorageUsage() {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

    class TestReportingContext implements ReportingContext
    {
        private EventAccess ea;
        public TestReportingContext(ProcessGroupStatus st) {
            this.ea = new TestEventAccess(st);
        }

        @Override
        public Map<PropertyDescriptor, String> getProperties() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public EventAccess getEventAccess() {
            return this.ea;
        }

        @Override
        public BulletinRepository getBulletinRepository() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public Bulletin createBulletin(String string, Severity svrt, String string1) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public Bulletin createBulletin(String string, String string1, Severity svrt, String string2) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public ControllerServiceLookup getControllerServiceLookup() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public StateManager getStateManager() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public boolean isClustered() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public String getClusterNodeIdentifier() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public PropertyValue getProperty(PropertyDescriptor pd) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public Map<String, String> getAllProperties() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }
    }
}

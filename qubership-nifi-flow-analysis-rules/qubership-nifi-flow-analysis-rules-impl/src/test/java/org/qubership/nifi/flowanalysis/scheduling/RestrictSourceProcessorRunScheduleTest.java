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

package org.qubership.nifi.flowanalysis.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.nifi.flowanalysis.Fixtures.GENERATE_FLOW_FILE_TYPE;
import static org.qubership.nifi.flowanalysis.Fixtures.connection;
import static org.qubership.nifi.flowanalysis.Fixtures.processGroup;
import static org.qubership.nifi.flowanalysis.Fixtures.processor;
import static org.qubership.nifi.flowanalysis.Fixtures.setOf;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flow.VersionedProcessor;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleInitializationContext;
import org.apache.nifi.flowanalysis.GroupAnalysisResult;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.util.FormatUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RestrictSourceProcessorRunScheduleTest {

    private final RestrictSourceProcessorRunSchedule rule = new RestrictSourceProcessorRunSchedule();

    @BeforeEach
    public void initializeRule() throws Exception {
        FlowAnalysisRuleInitializationContext initContext = mock(FlowAnalysisRuleInitializationContext.class);
        when(initContext.getIdentifier()).thenReturn("test-rule");
        when(initContext.getLogger()).thenReturn(mock(ComponentLog.class));
        rule.initialize(initContext);
    }

    @Test
    public void reportsSourceProcessorWithZeroRunSchedule() {
        GroupAnalysisResult result = single(analyze("0 sec", processor("p-1", "p-1", "TIMER_DRIVEN", "0 sec")));

        assertEquals("run-schedule-too-low-p-1", result.getIssueId());
        assertTrue(result.getMessage().contains("Run Schedule '0 sec'"), result.getMessage());
        assertTrue(result.getMessage().contains("flood the flow with FlowFiles"), result.getMessage());
    }

    @Test
    public void reportsPositiveRunScheduleBelowThreshold() {
        assertEquals(1, analyze("1 sec", processor("p-1", "p-1", "TIMER_DRIVEN", "500 millis")).size());
    }

    @Test
    public void reportsZeroRunScheduleEvenWhenThresholdIsZero() {
        assertEquals(1, analyze("0 sec", processor("p-1", "p-1", "TIMER_DRIVEN", "0 sec")).size());
    }

    @Test
    public void noViolationWhenPositiveRunScheduleAndZeroThreshold() {
        assertTrue(analyze("0 sec", processor("p-1", "p-1", "TIMER_DRIVEN", "100 millis")).isEmpty());
    }

    @Test
    public void reportsRunScheduleEqualToThreshold() {
        assertEquals(1, analyze("1 sec", processor("p-1", "p-1", "TIMER_DRIVEN", "1 sec")).size());
    }

    @Test
    public void noViolationWhenRunScheduleAboveThreshold() {
        assertTrue(analyze("1 sec", processor("p-1", "p-1", "TIMER_DRIVEN", "5 sec")).isEmpty());
    }

    @Test
    public void ignoresProcessorThatHasAnIncomingConnection() {
        VersionedProcessGroup group = processGroup("pg-1", "g");
        group.setProcessors(setOf(processor("p-1", "p-1", "TIMER_DRIVEN", "0 sec")));
        group.setConnections(setOf(connection("upstream", "p-1")));

        assertTrue(rule.analyzeProcessGroup(group, context("0 sec")).isEmpty());
    }

    @Test
    public void treatsProcessorWithOnlyASelfLoopAsSource() {
        VersionedProcessGroup group = processGroup("pg-1", "g");
        group.setProcessors(setOf(processor("p-1", "p-1", "TIMER_DRIVEN", "0 sec")));
        group.setConnections(setOf(connection("p-1", "p-1")));

        assertFalse(rule.analyzeProcessGroup(group, context("0 sec")).isEmpty());
    }

    @Test
    public void ignoresCronDrivenProcessor() {
        assertTrue(analyze("0 sec", processor("p-1", "p-1", "CRON_DRIVEN", "* * * * * ?")).isEmpty());
    }

    @Test
    public void reportsGenerateFlowFile() {
        VersionedProcessor generateFlowFile = processor("p-1", "p-1", "TIMER_DRIVEN", "0 sec");
        generateFlowFile.setType(GENERATE_FLOW_FILE_TYPE);

        assertEquals(1, analyze("0 sec", generateFlowFile).size());
    }

    @Test
    public void ignoresProcessorWithUnparseableRunSchedule() {
        assertTrue(analyze("0 sec", processor("p-1", "p-1", "TIMER_DRIVEN", "not-a-duration")).isEmpty());
    }

    @Test
    public void ignoresProcessorWhoseTypeIsInTheIgnoredList() {
        VersionedProcessor listener = processor("p-1", "p-1", "TIMER_DRIVEN", "0 sec");
        listener.setType("org.apache.nifi.processors.standard.ListenHTTP");

        assertTrue(analyze("0 sec", "org.apache.nifi.processors.standard.ListenHTTP", listener).isEmpty());
    }

    @Test
    public void ignoredListAcceptsSeveralTypesWithSurroundingWhitespace() {
        VersionedProcessor consumer = processor("p-1", "p-1", "TIMER_DRIVEN", "0 sec");
        consumer.setType("org.apache.nifi.processors.kafka.pubsub.ConsumeKafka");

        assertTrue(analyze("0 sec", " a.b.C , org.apache.nifi.processors.kafka.pubsub.ConsumeKafka ",
                consumer).isEmpty());
    }

    @Test
    public void stillReportsProcessorTypesNotInTheIgnoredList() {
        assertEquals(1, analyze("0 sec", "some.other.Type",
                processor("p-1", "p-1", "TIMER_DRIVEN", "0 sec")).size());
    }

    private Collection<GroupAnalysisResult> analyze(final String threshold,
                                                   final VersionedProcessor... processors) {
        return analyze(threshold, null, processors);
    }

    private Collection<GroupAnalysisResult> analyze(final String threshold, final String ignoredTypes,
                                                   final VersionedProcessor... processors) {
        VersionedProcessGroup group = processGroup("pg-1", "g");
        group.setProcessors(setOf(processors));
        return rule.analyzeProcessGroup(group, context(threshold, ignoredTypes));
    }

    private static GroupAnalysisResult single(final Collection<GroupAnalysisResult> results) {
        assertEquals(1, results.size(), () -> "expected exactly one violation, got " + results);
        return results.iterator().next();
    }

    private static FlowAnalysisRuleContext context(final String threshold) {
        return context(threshold, null);
    }

    private static FlowAnalysisRuleContext context(final String threshold, final String ignoredTypes) {
        PropertyValue thresholdValue = mock(PropertyValue.class);
        when(thresholdValue.asTimePeriod(TimeUnit.MILLISECONDS))
                .thenReturn(FormatUtils.getTimeDuration(threshold, TimeUnit.MILLISECONDS));
        PropertyValue ignoredValue = mock(PropertyValue.class);
        when(ignoredValue.getValue()).thenReturn(ignoredTypes);
        FlowAnalysisRuleContext context = mock(FlowAnalysisRuleContext.class);
        when(context.getProperty(RestrictSourceProcessorRunSchedule.RUN_SCHEDULE_THRESHOLD))
                .thenReturn(thresholdValue);
        when(context.getProperty(RestrictSourceProcessorRunSchedule.IGNORED_PROCESSOR_TYPES))
                .thenReturn(ignoredValue);
        return context;
    }
}

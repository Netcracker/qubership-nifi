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
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
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
        GroupAnalysisResult result = single(analyze("1 sec", processor("p-1", "p-1", "TIMER_DRIVEN", "0 sec")));

        assertEquals("run-schedule-below-minimum-p-1", result.getIssueId());
        assertTrue(result.getMessage().contains("Run Schedule '0 sec'"), result.getMessage());
        assertTrue(result.getMessage().contains("no delay between executions"), result.getMessage());
    }

    @Test
    public void reportsSourceProcessorBelowThreshold() {
        GroupAnalysisResult result = single(analyze("1 sec", processor("p-1", "p-1", "TIMER_DRIVEN", "500 millis")));

        assertTrue(result.getMessage().contains("below the configured minimum of '1 sec'"), result.getMessage());
    }

    @Test
    public void noViolationWhenRunScheduleEqualsThreshold() {
        assertTrue(analyze("1 sec", processor("p-1", "p-1", "TIMER_DRIVEN", "1 sec")).isEmpty());
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

        assertTrue(rule.analyzeProcessGroup(group, context("1 sec")).isEmpty());
    }

    @Test
    public void treatsProcessorWithOnlyASelfLoopAsSource() {
        VersionedProcessGroup group = processGroup("pg-1", "g");
        group.setProcessors(setOf(processor("p-1", "p-1", "TIMER_DRIVEN", "0 sec")));
        group.setConnections(setOf(connection("p-1", "p-1")));

        assertFalse(rule.analyzeProcessGroup(group, context("1 sec")).isEmpty());
    }

    @Test
    public void ignoresCronDrivenProcessor() {
        assertTrue(analyze("1 sec", processor("p-1", "p-1", "CRON_DRIVEN", "* * * * * ?")).isEmpty());
    }

    @Test
    public void ignoresConfiguredProcessorTypes() {
        VersionedProcessor generateFlowFile = processor("p-1", "p-1", "TIMER_DRIVEN", "0 sec");
        generateFlowFile.setType(GENERATE_FLOW_FILE_TYPE);

        assertTrue(analyze("1 sec", generateFlowFile).isEmpty());
    }

    @Test
    public void ignoresProcessorWithUnparseableRunSchedule() {
        assertTrue(analyze("1 sec", processor("p-1", "p-1", "TIMER_DRIVEN", "not-a-duration")).isEmpty());
    }

    @Test
    public void customValidateRejectsZeroMinimum() {
        Collection<ValidationResult> results = rule.customValidate(validationContext("0 sec"));

        assertEquals(1, results.size());
        assertFalse(results.iterator().next().isValid());
    }

    @Test
    public void customValidateAcceptsPositiveMinimum() {
        assertTrue(rule.customValidate(validationContext("1 sec")).isEmpty());
    }

    private Collection<GroupAnalysisResult> analyze(final String minimumRunSchedule,
                                                   final VersionedProcessor... processors) {
        VersionedProcessGroup group = processGroup("pg-1", "g");
        group.setProcessors(setOf(processors));
        return rule.analyzeProcessGroup(group, context(minimumRunSchedule));
    }

    private static GroupAnalysisResult single(final Collection<GroupAnalysisResult> results) {
        assertEquals(1, results.size(), () -> "expected exactly one violation, got " + results);
        return results.iterator().next();
    }

    private static FlowAnalysisRuleContext context(final String minimumRunSchedule) {
        PropertyValue value = timePeriod(minimumRunSchedule);
        FlowAnalysisRuleContext context = mock(FlowAnalysisRuleContext.class);
        when(context.getProperty(RestrictSourceProcessorRunSchedule.MINIMUM_RUN_SCHEDULE)).thenReturn(value);
        return context;
    }

    private static ValidationContext validationContext(final String minimumRunSchedule) {
        PropertyValue value = timePeriod(minimumRunSchedule);
        ValidationContext context = mock(ValidationContext.class);
        when(context.getProperty(RestrictSourceProcessorRunSchedule.MINIMUM_RUN_SCHEDULE)).thenReturn(value);
        return context;
    }

    private static PropertyValue timePeriod(final String value) {
        PropertyValue propertyValue = mock(PropertyValue.class);
        when(propertyValue.asTimePeriod(TimeUnit.MILLISECONDS))
                .thenReturn(FormatUtils.getTimeDuration(value, TimeUnit.MILLISECONDS));
        when(propertyValue.getValue()).thenReturn(value);
        return propertyValue;
    }
}

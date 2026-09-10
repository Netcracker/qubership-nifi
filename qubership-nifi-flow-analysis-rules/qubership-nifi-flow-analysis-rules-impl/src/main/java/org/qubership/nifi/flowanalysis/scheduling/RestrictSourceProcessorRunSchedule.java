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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flow.ConnectableComponent;
import org.apache.nifi.flow.ConnectableComponentType;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flow.VersionedProcessor;
import org.apache.nifi.flowanalysis.AbstractFlowAnalysisRule;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.apache.nifi.flowanalysis.GroupAnalysisResult;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.scheduling.SchedulingStrategy;
import org.apache.nifi.util.FormatUtils;

/**
 * Flow analysis rule that reports a source processor (a TIMER_DRIVEN processor with no incoming
 * connection) whose Run Schedule is at or below the configured threshold. A Run Schedule of 0 is
 * always reported; the threshold defaults to 0.
 */
@Tags({"processor", "source", "scheduling", "frequency"})
@CapabilityDescription("Reports a source processor - a TIMER_DRIVEN processor with no incoming "
        + "connection - whose Run Schedule is at or below the configured threshold. Such a processor "
        + "runs with little or no delay and can overload its source system or flood the flow with "
        + "FlowFiles.")
public final class RestrictSourceProcessorRunSchedule extends AbstractFlowAnalysisRule {

    /**
     * Run Schedule value at or below which a source processor is reported.
     */
    public static final PropertyDescriptor RUN_SCHEDULE_THRESHOLD = new PropertyDescriptor.Builder()
            .name("Run Schedule Threshold")
            .displayName("Run Schedule Threshold")
            .description("Source processors whose Run Schedule is at or below this value are reported. "
                    + "A Run Schedule of 0 is always reported.")
            .required(true)
            .defaultValue("0 sec")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    private static final List<PropertyDescriptor> PROPERTIES = List.of(RUN_SCHEDULE_THRESHOLD);

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    public Collection<GroupAnalysisResult> analyzeProcessGroup(
            final VersionedProcessGroup processGroup, final FlowAnalysisRuleContext context) {

        final long thresholdMillis = context.getProperty(RUN_SCHEDULE_THRESHOLD).asTimePeriod(TimeUnit.MILLISECONDS);

        final Set<String> processorsWithInput = processGroup.getConnections().stream()
                .filter(connection -> {
                    final ConnectableComponent destination = connection.getDestination();
                    // a self-loop (retry connection back to the same processor) does not make it a non-source
                    return destination.getType() == ConnectableComponentType.PROCESSOR
                            && !destination.getId().equals(connection.getSource().getId());
                })
                .map(connection -> connection.getDestination().getId())
                .collect(Collectors.toSet());

        final List<GroupAnalysisResult> results = new ArrayList<>();
        for (final VersionedProcessor processor : processGroup.getProcessors()) {
            if (processorsWithInput.contains(processor.getIdentifier())) {
                continue;
            }
            if (!SchedulingStrategy.TIMER_DRIVEN.name().equals(processor.getSchedulingStrategy())) {
                continue;
            }
            final Long periodMillis = parsePeriodMillis(processor.getSchedulingPeriod());
            if (periodMillis == null || (periodMillis > 0L && periodMillis >= thresholdMillis)) {
                continue;
            }
            results.add(GroupAnalysisResult
                    .forComponent(
                            processor,
                            "run-schedule-too-low-" + processor.getIdentifier(),
                            "Source processor has Run Schedule '" + processor.getSchedulingPeriod()
                                    + "'. Scheduling a source this frequently wastes CPU on empty runs "
                                    + "and can overload its source system or flood the flow with FlowFiles.")
                    .build());
        }
        return results;
    }

    private static Long parsePeriodMillis(final String schedulingPeriod) {
        if (schedulingPeriod == null || schedulingPeriod.isBlank()) {
            return null;
        }
        try {
            return FormatUtils.getTimeDuration(schedulingPeriod, TimeUnit.MILLISECONDS);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}

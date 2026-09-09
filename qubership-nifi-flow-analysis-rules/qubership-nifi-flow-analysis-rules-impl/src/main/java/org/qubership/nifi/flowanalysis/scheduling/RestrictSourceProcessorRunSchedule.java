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
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.flow.ConnectableComponent;
import org.apache.nifi.flow.ConnectableComponentType;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flow.VersionedProcessor;
import org.apache.nifi.flowanalysis.AbstractFlowAnalysisRule;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.apache.nifi.flowanalysis.GroupAnalysisResult;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.util.FormatUtils;

/**
 * Flow analysis rule that reports a source processor (a TIMER_DRIVEN processor with no incoming
 * connection) whose Run Schedule is below the configured minimum. The minimum is configurable, but
 * a Run Schedule of 0 is always reported since customValidate forbids a minimum of zero. Processor
 * types in IGNORED_PROCESSOR_TYPES are skipped because they generate FlowFiles internally rather
 * than polling an external system.
 */
@Tags({"processor", "source", "scheduling", "frequency"})
@CapabilityDescription("Produces a rule violation for each source processor - a TIMER_DRIVEN "
        + "processor with no incoming connection - whose Run Schedule is below the configured "
        + "minimum. A Run Schedule of 0 schedules the processor with no delay between executions "
        + "and can overload the system it pulls data from.")
public final class RestrictSourceProcessorRunSchedule extends AbstractFlowAnalysisRule {

    /**
     * Smallest Run Schedule allowed for a source processor.
     */
    public static final PropertyDescriptor MINIMUM_RUN_SCHEDULE = new PropertyDescriptor.Builder()
            .name("Minimum Run Schedule")
            .displayName("Minimum Run Schedule")
            .description("The smallest Run Schedule (scheduling period) allowed for a source processor, "
                    + "that is a processor with no incoming connection. Source processors with a shorter "
                    + "Run Schedule, 0 included, are reported. Only TIMER_DRIVEN processors are checked.")
            .required(true)
            .defaultValue("1 sec")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    private static final String TIMER_DRIVEN = "TIMER_DRIVEN";

    /**
     * Fully qualified processor types that are never reported by this rule, regardless of their
     * Run Schedule, because they produce FlowFiles internally instead of polling an external system.
     */
    private static final Set<String> IGNORED_PROCESSOR_TYPES = Set.of(
            "org.apache.nifi.processors.standard.GenerateFlowFile");

    private static final List<PropertyDescriptor> PROPERTIES = List.of(MINIMUM_RUN_SCHEDULE);

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    protected Collection<ValidationResult> customValidate(final ValidationContext validationContext) {
        final long minimumMillis = validationContext.getProperty(MINIMUM_RUN_SCHEDULE)
                .asTimePeriod(TimeUnit.MILLISECONDS);
        if (minimumMillis <= 0) {
            return List.of(new ValidationResult.Builder()
                    .subject(MINIMUM_RUN_SCHEDULE.getDisplayName())
                    .input(validationContext.getProperty(MINIMUM_RUN_SCHEDULE).getValue())
                    .valid(false)
                    .explanation("must be greater than 0")
                    .build());
        }
        return List.of();
    }

    @Override
    public Collection<GroupAnalysisResult> analyzeProcessGroup(
            final VersionedProcessGroup processGroup, final FlowAnalysisRuleContext context) {

        final long minimumMillis = context.getProperty(MINIMUM_RUN_SCHEDULE).asTimePeriod(TimeUnit.MILLISECONDS);
        final String configuredMinimum = context.getProperty(MINIMUM_RUN_SCHEDULE).getValue();

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
            if (IGNORED_PROCESSOR_TYPES.contains(processor.getType())) {
                continue;
            }
            if (processorsWithInput.contains(processor.getIdentifier())) {
                continue;
            }
            if (!TIMER_DRIVEN.equals(processor.getSchedulingStrategy())) {
                continue;
            }

            final Long periodMillis = parsePeriodMillis(processor.getSchedulingPeriod());
            if (periodMillis == null || periodMillis >= minimumMillis) {
                continue;
            }

            final String message;
            if (periodMillis <= 0) {
                message = "Source processor has Run Schedule '"
                        + processor.getSchedulingPeriod() + "', so it is scheduled with no delay between executions.";
            } else {
                message = "Source processor has Run Schedule '"
                        + processor.getSchedulingPeriod() + "', which is below the configured minimum of '"
                        + configuredMinimum + "'.";
            }

            results.add(GroupAnalysisResult
                    .forComponent(
                            processor,
                            "run-schedule-below-minimum-" + processor.getIdentifier(),
                            message)
                    .explanation("A source processor scheduled this frequently keeps asking the external "
                            + "system for data even when there is nothing to read, which wastes scheduler "
                            + "threads and adds load on that system. Increase the Run Schedule on the "
                            + "processor's Scheduling tab.")
                    .build());
        }
        return results;
    }

    private Long parsePeriodMillis(final String schedulingPeriod) {
        if (schedulingPeriod == null || schedulingPeriod.isBlank()) {
            return 0L;
        }
        try {
            return FormatUtils.getTimeDuration(schedulingPeriod, TimeUnit.MILLISECONDS);
        } catch (final IllegalArgumentException e) {
            getLogger().debug("Could not parse Run Schedule '{}', skipping processor", schedulingPeriod, e);
            return null;
        }
    }
}

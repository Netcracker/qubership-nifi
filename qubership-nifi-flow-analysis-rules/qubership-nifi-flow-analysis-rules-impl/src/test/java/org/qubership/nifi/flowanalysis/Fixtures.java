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

package org.qubership.nifi.flowanalysis;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.nifi.flow.ConnectableComponent;
import org.apache.nifi.flow.ConnectableComponentType;
import org.apache.nifi.flow.VersionedConnection;
import org.apache.nifi.flow.VersionedControllerService;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flow.VersionedProcessor;
import org.apache.nifi.flow.VersionedPropertyDescriptor;
import org.apache.nifi.flowanalysis.GroupAnalysisResult;

/**
 * Builders for the versioned flow model objects used by the flow analysis rule tests. Public so the
 * per-topic test packages (unique, scheduling, database) can share it.
 */
public final class Fixtures {

    public static final String UPDATE_ATTRIBUTE_TYPE = "org.apache.nifi.processors.standard.UpdateAttribute";
    public static final String GENERATE_FLOW_FILE_TYPE = "org.apache.nifi.processors.standard.GenerateFlowFile";

    private Fixtures() {
    }

    public static VersionedProcessGroup processGroup(final String id, final String name) {
        final VersionedProcessGroup group = new VersionedProcessGroup();
        group.setIdentifier(id);
        group.setName(name);
        return group;
    }

    public static VersionedProcessor processor(final String id, final String name) {
        return processor(id, name, "TIMER_DRIVEN", "0 sec");
    }

    public static VersionedProcessor processor(final String id, final String name,
                                               final String schedulingStrategy, final String schedulingPeriod) {
        final VersionedProcessor processor = new VersionedProcessor();
        processor.setIdentifier(id);
        processor.setName(name);
        processor.setType(UPDATE_ATTRIBUTE_TYPE);
        processor.setSchedulingStrategy(schedulingStrategy);
        processor.setSchedulingPeriod(schedulingPeriod);
        processor.setRunDurationMillis(0L);
        return processor;
    }

    public static VersionedProcessor processor(final String id, final String name,
                                               final Map<String, VersionedPropertyDescriptor> descriptors,
                                               final Map<String, String> properties) {
        final VersionedProcessor processor = processor(id, name);
        processor.setPropertyDescriptors(descriptors);
        processor.setProperties(properties);
        return processor;
    }

    public static VersionedPropertyDescriptor descriptor(final String name, final String displayName) {
        final VersionedPropertyDescriptor descriptor = new VersionedPropertyDescriptor();
        descriptor.setName(name);
        descriptor.setDisplayName(displayName);
        return descriptor;
    }

    public static VersionedControllerService controllerService(final String id, final String name) {
        final VersionedControllerService service = new VersionedControllerService();
        service.setIdentifier(id);
        service.setName(name);
        return service;
    }

    public static VersionedConnection connection(final String sourceId, final String destinationId) {
        final VersionedConnection connection = new VersionedConnection();
        connection.setSource(connectable(sourceId));
        connection.setDestination(connectable(destinationId));
        return connection;
    }

    private static ConnectableComponent connectable(final String id) {
        final ConnectableComponent component = new ConnectableComponent();
        component.setId(id);
        component.setType(ConnectableComponentType.PROCESSOR);
        return component;
    }

    @SafeVarargs
    public static <T> Set<T> setOf(final T... items) {
        return new LinkedHashSet<>(List.of(items));
    }

    public static Set<String> subjectIds(final Collection<GroupAnalysisResult> results) {
        return results.stream()
                .map(result -> result.getComponent().orElseThrow().getIdentifier())
                .collect(Collectors.toSet());
    }

    public static Set<String> issueIds(final Collection<GroupAnalysisResult> results) {
        return results.stream().map(GroupAnalysisResult::getIssueId).collect(Collectors.toSet());
    }

    public static GroupAnalysisResult resultFor(final Collection<GroupAnalysisResult> results, final String subjectId) {
        return results.stream()
                .filter(result -> subjectId.equals(result.getComponent().orElseThrow().getIdentifier()))
                .findFirst()
                .orElseThrow();
    }
}

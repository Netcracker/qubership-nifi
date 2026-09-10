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

package org.qubership.nifi.flowanalysis.unique;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qubership.nifi.flowanalysis.Fixtures.issueIds;
import static org.qubership.nifi.flowanalysis.Fixtures.processGroup;
import static org.qubership.nifi.flowanalysis.Fixtures.processor;
import static org.qubership.nifi.flowanalysis.Fixtures.resultFor;
import static org.qubership.nifi.flowanalysis.Fixtures.setOf;
import static org.qubership.nifi.flowanalysis.Fixtures.subjectIds;

import java.util.Collection;
import java.util.Set;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.apache.nifi.flowanalysis.GroupAnalysisResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class UniqueProcessorNamesTest {

    private final UniqueProcessorNames rule = new UniqueProcessorNames();
    private final FlowAnalysisRuleContext context = Mockito.mock(FlowAnalysisRuleContext.class);

    @Test
    public void reportsEveryProcessorThatSharesAName() {
        VersionedProcessGroup group = processGroup("pg-1", "etl");
        group.setProcessors(setOf(
                processor("p-1", "Route"),
                processor("p-2", "Route"),
                processor("p-3", "Enrich")));

        Collection<GroupAnalysisResult> results = rule.analyzeProcessGroup(group, context);

        assertEquals(Set.of("p-1", "p-2"), subjectIds(results));
        assertEquals(
                Set.of("duplicate-processor-name-p-1", "duplicate-processor-name-p-2"),
                issueIds(results));
    }

    @Test
    public void messageNamesCountScopeAndTheOtherProcessor() {
        VersionedProcessGroup group = processGroup("pg-1", "etl");
        group.setProcessors(setOf(processor("p-1", "Route"), processor("p-2", "Route")));

        String message = resultFor(rule.analyzeProcessGroup(group, context), "p-1").getMessage();

        assertTrue(message.contains("The processor 'Route' [p-1] is not unique"), message);
        assertTrue(message.contains("2 processors in the process group are named 'Route'"), message);
        assertTrue(message.contains("the other is [p-2]"), message);
    }

    @Test
    public void messageListsAllOtherProcessorsWhenMoreThanTwoShareAName() {
        VersionedProcessGroup group = processGroup("pg-1", "etl");
        group.setProcessors(setOf(
                processor("p-1", "Route"),
                processor("p-2", "Route"),
                processor("p-3", "Route")));

        String message = resultFor(rule.analyzeProcessGroup(group, context), "p-1").getMessage();

        assertTrue(message.contains("3 processors"), message);
        assertTrue(message.contains("the others are [p-2], [p-3]"), message);
    }

    @Test
    public void noViolationWhenAllNamesAreUnique() {
        VersionedProcessGroup group = processGroup("pg-1", "etl");
        group.setProcessors(setOf(processor("p-1", "Route"), processor("p-2", "Enrich")));

        assertTrue(rule.analyzeProcessGroup(group, context).isEmpty());
    }

    @Test
    public void ignoresProcessorsInNestedGroups() {
        VersionedProcessGroup child = processGroup("pg-2", "child");
        child.setProcessors(setOf(processor("p-2", "Route")));
        VersionedProcessGroup group = processGroup("pg-1", "etl");
        group.setProcessors(setOf(processor("p-1", "Route")));
        group.setProcessGroups(setOf(child));

        assertTrue(rule.analyzeProcessGroup(group, context).isEmpty());
    }
}

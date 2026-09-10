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
import static org.qubership.nifi.flowanalysis.Fixtures.controllerService;
import static org.qubership.nifi.flowanalysis.Fixtures.issueIds;
import static org.qubership.nifi.flowanalysis.Fixtures.processGroup;
import static org.qubership.nifi.flowanalysis.Fixtures.resultFor;
import static org.qubership.nifi.flowanalysis.Fixtures.setOf;
import static org.qubership.nifi.flowanalysis.Fixtures.subjectIds;

import java.util.Set;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class UniqueControllerServiceNamesTest {

    private final UniqueControllerServiceNames rule = new UniqueControllerServiceNames();
    private final FlowAnalysisRuleContext context = Mockito.mock(FlowAnalysisRuleContext.class);

    @Test
    public void reportsDuplicateWithinTheSameGroup() {
        VersionedProcessGroup group = processGroup("pg-1", "g");
        group.setControllerServices(setOf(
                controllerService("cs-1", "Pool"),
                controllerService("cs-2", "Pool"),
                controllerService("cs-3", "Cache")));

        assertEquals(Set.of("cs-1", "cs-2"), subjectIds(rule.analyzeProcessGroup(group, context)));
        assertEquals(
                Set.of("duplicate-controller-service-name-cs-1", "duplicate-controller-service-name-cs-2"),
                issueIds(rule.analyzeProcessGroup(group, context)));
    }

    @Test
    public void reportsDuplicateAcrossDescendantGroups() {
        VersionedProcessGroup grandchild = processGroup("pg-3", "grandchild");
        grandchild.setControllerServices(setOf(controllerService("cs-2", "Pool")));
        VersionedProcessGroup child = processGroup("pg-2", "child");
        child.setProcessGroups(setOf(grandchild));
        VersionedProcessGroup root = processGroup("pg-1", "root");
        root.setControllerServices(setOf(controllerService("cs-1", "Pool")));
        root.setProcessGroups(setOf(child));

        assertEquals(Set.of("cs-1", "cs-2"), subjectIds(rule.analyzeProcessGroup(root, context)));
    }

    @Test
    public void messageMentionsDescendantScope() {
        VersionedProcessGroup group = processGroup("pg-1", "g");
        group.setControllerServices(setOf(
                controllerService("cs-1", "Pool"),
                controllerService("cs-2", "Pool")));

        String message = resultFor(rule.analyzeProcessGroup(group, context), "cs-1").getMessage();

        assertTrue(message.contains("The controller service 'Pool' [cs-1] is not unique"), message);
        assertTrue(message.contains("in this process group or a descendant group"), message);
    }

    @Test
    public void messageForANestedServiceIsTheSameRegardlessOfWhichAncestorPassProducesIt() {
        VersionedProcessGroup child = processGroup("pg-2", "child");
        child.setControllerServices(setOf(
                controllerService("cs-2", "Pool"),
                controllerService("cs-3", "Pool")));
        VersionedProcessGroup root = processGroup("pg-1", "root");
        root.setControllerServices(setOf(controllerService("cs-1", "Pool")));
        root.setProcessGroups(setOf(child));

        String fromRootPass = resultFor(rule.analyzeProcessGroup(root, context), "cs-2").getMessage();
        String fromChildPass = resultFor(rule.analyzeProcessGroup(child, context), "cs-2").getMessage();

        assertEquals(fromRootPass, fromChildPass);
        assertTrue(fromRootPass.contains("another controller service"), fromRootPass);
    }

    @Test
    public void noViolationWhenNamesAreUniqueAcrossTree() {
        VersionedProcessGroup child = processGroup("pg-2", "child");
        child.setControllerServices(setOf(controllerService("cs-2", "Cache")));
        VersionedProcessGroup root = processGroup("pg-1", "root");
        root.setControllerServices(setOf(controllerService("cs-1", "Pool")));
        root.setProcessGroups(setOf(child));

        assertTrue(rule.analyzeProcessGroup(root, context).isEmpty());
    }
}

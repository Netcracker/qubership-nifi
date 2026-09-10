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
        assertEquals(Set.of("duplicate-controller-service-name"),
                issueIds(rule.analyzeProcessGroup(group, context)));
    }

    @Test
    public void reportsClashBetweenAGroupAndItsDescendant() {
        VersionedProcessGroup grandchild = processGroup("pg-3", "grandchild");
        grandchild.setControllerServices(setOf(controllerService("cs-2", "Pool")));
        VersionedProcessGroup child = processGroup("pg-2", "child");
        child.setProcessGroups(setOf(grandchild));
        VersionedProcessGroup root = processGroup("pg-1", "root");
        root.setControllerServices(setOf(controllerService("cs-1", "Pool")));
        root.setProcessGroups(setOf(child));

        // Only the ancestor's service is flagged: the descendant's own pass cannot see upward.
        assertEquals(Set.of("cs-1"), subjectIds(rule.analyzeProcessGroup(root, context)));
    }

    @Test
    public void descendantPassDoesNotFlagAServiceThatOnlyClashesWithAnAncestor() {
        VersionedProcessGroup child = processGroup("pg-2", "child");
        child.setControllerServices(setOf(controllerService("cs-2", "Pool")));
        VersionedProcessGroup root = processGroup("pg-1", "root");
        root.setControllerServices(setOf(controllerService("cs-1", "Pool")));
        root.setProcessGroups(setOf(child));

        assertTrue(rule.analyzeProcessGroup(child, context).isEmpty());
    }

    @Test
    public void noViolationForSiblingGroupsThatCarryTheSameServiceName() {
        // Two child groups imported from the same versioned flow, each with its own JsonTreeReader.
        VersionedProcessGroup orders = processGroup("pg-orders", "orders");
        orders.setControllerServices(setOf(controllerService("cs-orders", "JsonTreeReader")));
        VersionedProcessGroup invoices = processGroup("pg-invoices", "invoices");
        invoices.setControllerServices(setOf(controllerService("cs-invoices", "JsonTreeReader")));
        VersionedProcessGroup root = processGroup("pg-1", "root");
        root.setProcessGroups(setOf(orders, invoices));

        // The framework calls the rule once per group in the tree.
        assertTrue(rule.analyzeProcessGroup(root, context).isEmpty());
        assertTrue(rule.analyzeProcessGroup(orders, context).isEmpty());
        assertTrue(rule.analyzeProcessGroup(invoices, context).isEmpty());
    }

    @Test
    public void messageNamesTheDuplicatedNameAndAsksToRenameOne() {
        VersionedProcessGroup group = processGroup("pg-1", "g");
        group.setControllerServices(setOf(
                controllerService("cs-1", "Pool"),
                controllerService("cs-2", "Pool")));

        String message = resultFor(rule.analyzeProcessGroup(group, context), "cs-1").getMessage();

        assertTrue(message.contains("Controller service name 'Pool' is not unique"), message);
        assertTrue(message.contains("same process group or a descendant group"), message);
        assertTrue(message.contains("Rename one of them"), message);
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

    @Test
    public void noViolationWhenGroupHasNoServices() {
        assertTrue(rule.analyzeProcessGroup(processGroup("pg-1", "g"), context).isEmpty());
    }
}

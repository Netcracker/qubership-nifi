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
import static org.qubership.nifi.flowanalysis.Fixtures.resultFor;
import static org.qubership.nifi.flowanalysis.Fixtures.setOf;
import static org.qubership.nifi.flowanalysis.Fixtures.subjectIds;

import java.util.Set;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class UniqueProcessGroupNamesTest {

    private final UniqueProcessGroupNames rule = new UniqueProcessGroupNames();
    private final FlowAnalysisRuleContext context = Mockito.mock(FlowAnalysisRuleContext.class);

    @Test
    public void reportsEveryChildGroupThatSharesAName() {
        VersionedProcessGroup root = processGroup("root", "root");
        root.setProcessGroups(setOf(
                processGroup("g-1", "sub"),
                processGroup("g-2", "sub"),
                processGroup("g-3", "other")));

        assertEquals(Set.of("g-1", "g-2"), subjectIds(rule.analyzeProcessGroup(root, context)));
        assertEquals(
                Set.of("duplicate-process-group-name-g-1", "duplicate-process-group-name-g-2"),
                issueIds(rule.analyzeProcessGroup(root, context)));
    }

    @Test
    public void messageMentionsParentProcessGroupScope() {
        VersionedProcessGroup root = processGroup("root", "root");
        root.setProcessGroups(setOf(processGroup("g-1", "sub"), processGroup("g-2", "sub")));

        String message = resultFor(rule.analyzeProcessGroup(root, context), "g-1").getMessage();

        assertTrue(message.contains("The process group 'sub' [g-1] is not unique"), message);
        assertTrue(message.contains("2 process groups in the parent process group"), message);
    }

    @Test
    public void noViolationWhenChildGroupNamesAreUnique() {
        VersionedProcessGroup root = processGroup("root", "root");
        root.setProcessGroups(setOf(processGroup("g-1", "a"), processGroup("g-2", "b")));

        assertTrue(rule.analyzeProcessGroup(root, context).isEmpty());
    }

    @Test
    public void ignoresGrandchildGroups() {
        VersionedProcessGroup grandchild = processGroup("gc-1", "sub");
        VersionedProcessGroup child = processGroup("c-1", "child");
        child.setProcessGroups(setOf(grandchild));
        VersionedProcessGroup root = processGroup("root", "root");
        root.setProcessGroups(setOf(child, processGroup("c-2", "sub")));

        assertTrue(rule.analyzeProcessGroup(root, context).isEmpty());
    }
}

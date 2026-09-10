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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.flow.VersionedControllerService;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.apache.nifi.flowanalysis.GroupAnalysisResult;

/**
 * Flow analysis rule that reports a violation for every controller service whose name is not unique
 * within a process group or any of its descendant process groups.
 *
 */
@Tags({"unique", "name", "controller service"})
@CapabilityDescription("Produces a rule violation for each controller service whose name is not "
        + "unique within a process group or any of its descendant process groups.")
public final class UniqueControllerServiceNames extends AbstractUniqueNameFlowAnalysisRule {

    @Override
    public Collection<GroupAnalysisResult> analyzeProcessGroup(
            final VersionedProcessGroup processGroup, final FlowAnalysisRuleContext context) {
        final List<VersionedControllerService> allServices = new ArrayList<>();
        collectControllerServices(processGroup, allServices);
        return reportDuplicateNames(
                allServices,
                "controller service",
                "duplicate-controller-service-name",
                "this process group or a descendant group",
                false);
    }

    private static void collectControllerServices(
            final VersionedProcessGroup group, final List<VersionedControllerService> target) {
        target.addAll(group.getControllerServices());
        group.getProcessGroups().forEach(child -> collectControllerServices(child, target));
    }
}

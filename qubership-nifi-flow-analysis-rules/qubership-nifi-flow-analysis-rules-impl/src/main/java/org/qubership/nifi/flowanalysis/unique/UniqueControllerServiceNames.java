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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.flow.VersionedControllerService;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flowanalysis.AbstractFlowAnalysisRule;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.apache.nifi.flowanalysis.GroupAnalysisResult;

/**
 * Flow analysis rule that reports a controller service whose name clashes with another controller
 * service visible from the same process group.
 *
 * <p>NiFi resolves a controller service reference within the referencing component's own process
 * group and that group's ancestors, so two services can only be confused where one group sees both:
 * within a single group, or between a group and one of its descendant groups. Services in sibling
 * groups are never visible together and are not a clash.</p>
 *
 * <p>The framework calls this rule once per process group in the tree. Each call checks only the
 * group's own (direct) controller services, comparing each against the group's other direct services
 * and against every service in a descendant group. Every service is therefore judged once, by its
 * own group's pass, so the violation is stable regardless of analysis order. In a clash between a
 * group and its descendant only the ancestor group's service is reported, because the versioned
 * flow model has no parent reference and the descendant's own pass cannot see upward; renaming
 * either service clears the violation.</p>
 */
@Tags({"unique", "name", "controller service"})
@CapabilityDescription("Produces a rule violation for each controller service whose name clashes "
        + "with another controller service in the same process group or in a descendant process "
        + "group - the places where NiFi makes both services visible together.")
public final class UniqueControllerServiceNames extends AbstractFlowAnalysisRule {

    private static final String ISSUE_ID = "duplicate-controller-service-name";

    @Override
    public Collection<GroupAnalysisResult> analyzeProcessGroup(
            final VersionedProcessGroup processGroup, final FlowAnalysisRuleContext context) {

        final Collection<VersionedControllerService> directServices = processGroup.getControllerServices();
        if (directServices.isEmpty()) {
            return List.of();
        }

        // Count, per name, every controller service visible together with the group's direct
        // services: the direct services themselves plus every service in a descendant group.
        final Map<String, Integer> countByName = new HashMap<>();
        countVisibleServiceNames(processGroup, countByName);

        final List<GroupAnalysisResult> results = new ArrayList<>();
        for (final VersionedControllerService service : directServices) {
            if (countByName.getOrDefault(service.getName(), 0) > 1) {
                results.add(GroupAnalysisResult
                        .forComponent(service, ISSUE_ID, buildMessage(service.getName()))
                        .build());
            }
        }
        return results;
    }

    private static void countVisibleServiceNames(
            final VersionedProcessGroup group, final Map<String, Integer> countByName) {
        group.getControllerServices().forEach(service ->
                countByName.merge(service.getName(), 1, Integer::sum));
        group.getProcessGroups().forEach(child -> countVisibleServiceNames(child, countByName));
    }

    private static String buildMessage(final String name) {
        return "Controller service name '" + name + "' is not unique: another controller service in "
                + "the same process group or a descendant group has the same name. Rename one of them.";
    }
}

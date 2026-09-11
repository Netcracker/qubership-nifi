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

import java.util.Collection;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.apache.nifi.flowanalysis.GroupAnalysisResult;

/**
 * Flow analysis rule that reports a violation for every processor whose name is not unique among
 * the processors within the process group.
 */
@Tags({"unique", "name", "processor"})
@CapabilityDescription("Produces a rule violation for each processor whose name is not unique "
        + "among the processors of the process group.")
public final class UniqueProcessorNames extends AbstractUniqueNameFlowAnalysisRule {

    @Override
    public Collection<GroupAnalysisResult> analyzeProcessGroup(
            final VersionedProcessGroup processGroup, final FlowAnalysisRuleContext context) {
        return reportDuplicateNames(
                processGroup.getProcessors(),
                "processor",
                "duplicate-processor-name",
                "the process group");
    }
}

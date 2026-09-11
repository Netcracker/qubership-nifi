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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.nifi.flow.VersionedComponent;
import org.apache.nifi.flowanalysis.AbstractFlowAnalysisRule;
import org.apache.nifi.flowanalysis.GroupAnalysisResult;

/**
 * Shared logic for flow analysis rules that require component names to be unique within a scope.
 */
abstract class AbstractUniqueNameFlowAnalysisRule extends AbstractFlowAnalysisRule {

    /**
     * Groups the given components by name and produces one violation per component whose name is
     * shared with at least one other component in the collection. The message lists the count and
     * the identifiers of the other components with the same name.
     *
     * @param components    the components whose names must be unique
     * @param componentKind singular noun for the component type, for example "processor"
     * @param issueIdPrefix stable prefix for the generated issue id
     * @param scope         human-readable name of the uniqueness scope, for example
     *                      "the process group"
     * @return the collection of violations, empty when all names are unique
     */
    protected Collection<GroupAnalysisResult> reportDuplicateNames(
            final Collection<? extends VersionedComponent> components,
            final String componentKind,
            final String issueIdPrefix,
            final String scope) {

        final Map<String, List<VersionedComponent>> componentsByName = new LinkedHashMap<>();
        for (final VersionedComponent component : components) {
            componentsByName
                    .computeIfAbsent(component.getName(), key -> new ArrayList<>())
                    .add(component);
        }

        final List<GroupAnalysisResult> results = new ArrayList<>();
        componentsByName.forEach((name, duplicates) -> {
            if (duplicates.size() > 1) {
                for (final VersionedComponent component : duplicates) {
                    results.add(GroupAnalysisResult
                            .forComponent(
                                    component,
                                    issueIdPrefix + "-" + component.getIdentifier(),
                                    buildMessage(component, duplicates, name, componentKind, scope))
                            .build());
                }
            }
        });
        return results;
    }

    private static String buildMessage(
            final VersionedComponent component,
            final List<VersionedComponent> duplicates,
            final String name,
            final String componentKind,
            final String scope) {

        final List<String> otherIds = duplicates.stream()
                .filter(other -> other != component)
                .map(other -> "[" + other.getIdentifier() + "]")
                .sorted()
                .toList();
        final String others = otherIds.size() == 1
                ? "the other is " + otherIds.get(0)
                : "the others are " + String.join(", ", otherIds);

        return "The " + componentKind + " '" + name + "' [" + component.getIdentifier() + "] is not "
                + "unique: " + duplicates.size() + " " + componentKind + "s in " + scope + " are named '"
                + name + "' (" + others + "). Rename this or other components to resolve this.";
    }
}

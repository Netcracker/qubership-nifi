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

package org.qubership.nifi.tools.kb.model;

import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;

/**
 * Maps a {@link NiFiComponentKind} to its Knowledge Base output names and display label.
 */
public final class ComponentKindLayout {

    private static final String PROCESSORS = "processors";

    private ComponentKindLayout() {
        // utility class
    }

    /**
     * Returns the output sub-directory name under {@code components/} for the given kind.
     *
     * @param kind the component kind
     * @return the directory name
     */
    public static String directoryName(final NiFiComponentKind kind) {
        return switch (kind) {
            case PROCESSOR -> PROCESSORS;
            case CONTROLLER_SERVICE -> "controller-services";
            case REPORTING_TASK -> "reporting-tasks";
        };
    }

    /**
     * Returns a human-readable display label for the given kind.
     *
     * @param kind the component kind
     * @return the display label
     */
    public static String displayLabel(final NiFiComponentKind kind) {
        return switch (kind) {
            case PROCESSOR -> "Processors";
            case CONTROLLER_SERVICE -> "Controller Services";
            case REPORTING_TASK -> "Reporting Tasks";
        };
    }

    /**
     * Returns the component-count field name in {@code manifest.json} for the given kind.
     *
     * @param kind the component kind
     * @return the manifest field name
     */
    public static String manifestCountField(final NiFiComponentKind kind) {
        return switch (kind) {
            case PROCESSOR -> PROCESSORS;
            case CONTROLLER_SERVICE -> "controllerServices";
            case REPORTING_TASK -> "reportingTasks";
        };
    }
}

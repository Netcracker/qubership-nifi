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

package org.qubership.nifi.tools.export;

/**
 * Enum representing the three NiFi component kinds that are collected.
 */
public enum ComponentKind {

    /** Processors sub-directory. */
    PROCESSOR("processors",
            "/nifi-api/flow/processor-types",
            "processorTypes",
            "/nifi-api/flow/processor-definition"),

    /** Controller services sub-directory. */
    CONTROLLER_SERVICE("controllerService",
            "/nifi-api/flow/controller-service-types",
            "controllerServiceTypes",
            "/nifi-api/flow/controller-service-definition"),

    /** Reporting tasks sub-directory. */
    REPORTING_TASK("reportingTask",
            "/nifi-api/flow/reporting-task-types",
            "reportingTaskTypes",
            "/nifi-api/flow/reporting-task-definition");

    private final String outputDirName;
    private final String listPath;
    private final String listKey;
    private final String definitionPathPrefix;

    /**
     * Creates a new ComponentKind with the given output directory name and API paths.
     *
     * @param dirName        the name of the output sub-directory for this kind
     * @param path           the NiFi API path that returns all types of this kind
     * @param key            the JSON key in the list response that contains the type array
     * @param defPathPrefix  the NiFi 2.x API path prefix for component definitions
     */
    ComponentKind(final String dirName, final String path,
                  final String key, final String defPathPrefix) {
        this.outputDirName = dirName;
        this.listPath = path;
        this.listKey = key;
        this.definitionPathPrefix = defPathPrefix;
    }

    /**
     * Returns the name of the output sub-directory for this component kind.
     *
     * @return the output directory name
     */
    public String getOutputDirName() {
        return outputDirName;
    }

    /**
     * Returns the NiFi API path that lists all types for this component kind.
     *
     * @return the list API path
     */
    public String getListPath() {
        return listPath;
    }

    /**
     * Returns the JSON key in the list response that contains the type array.
     *
     * @return the list response key
     */
    public String getListKey() {
        return listKey;
    }

    /**
     * Returns the NiFi 2.x API path prefix used to fetch component definitions.
     *
     * @return the definition path prefix
     */
    public String getDefinitionPathPrefix() {
        return definitionPathPrefix;
    }
}

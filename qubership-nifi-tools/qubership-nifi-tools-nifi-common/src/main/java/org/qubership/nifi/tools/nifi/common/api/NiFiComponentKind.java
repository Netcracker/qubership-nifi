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

package org.qubership.nifi.tools.nifi.common.api;

/**
 * The three NiFi component kinds that expose type-list and definition endpoints. Each constant
 * carries the list path, the JSON key holding the type array in the list response, the definition
 * path prefix, and the additional-details path prefix.
 */
public enum NiFiComponentKind {

    /** Processor components. */
    PROCESSOR("/nifi-api/flow/processor-types",
            "processorTypes",
            "/nifi-api/flow/processor-definition"),

    /** Controller service components. */
    CONTROLLER_SERVICE("/nifi-api/flow/controller-service-types",
            "controllerServiceTypes",
            "/nifi-api/flow/controller-service-definition"),

    /** Reporting task components. */
    REPORTING_TASK("/nifi-api/flow/reporting-task-types",
            "reportingTaskTypes",
            "/nifi-api/flow/reporting-task-definition");

    /** Common prefix for the additional-details endpoint shared by all component kinds. */
    private static final String ADDITIONAL_DETAILS_PREFIX = "/nifi-api/flow/additional-details";

    private final String listPath;
    private final String listKey;
    private final String definitionPathPrefix;

    /**
     * Creates a new component kind.
     *
     * @param path           the API path that lists all types of this kind
     * @param key            the JSON key in the list response that holds the type array
     * @param defPathPrefix  the API path prefix for this kind's component definitions
     */
    NiFiComponentKind(final String path, final String key, final String defPathPrefix) {
        this.listPath = path;
        this.listKey = key;
        this.definitionPathPrefix = defPathPrefix;
    }

    /**
     * Returns the API path that lists all types for this component kind.
     *
     * @return the list API path
     */
    public String getListPath() {
        return listPath;
    }

    /**
     * Returns the JSON key in the list response that holds the type array.
     *
     * @return the list response key
     */
    public String getListKey() {
        return listKey;
    }

    /**
     * Returns the API path prefix used to fetch component definitions for this kind.
     *
     * @return the definition path prefix
     */
    public String getDefinitionPathPrefix() {
        return definitionPathPrefix;
    }

    /**
     * Returns the API path prefix used to fetch optional additional component documentation.
     * The prefix is shared by all component kinds.
     *
     * @return the additional-details path prefix
     */
    public String getAdditionalDetailsPathPrefix() {
        return ADDITIONAL_DETAILS_PREFIX;
    }
}

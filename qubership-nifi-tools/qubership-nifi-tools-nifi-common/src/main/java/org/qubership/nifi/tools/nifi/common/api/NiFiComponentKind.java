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
 * carries the endpoints that list and describe the types of its kind, and the endpoints that
 * create, list, and delete its instances.
 */
public enum NiFiComponentKind {

    /** Processor components. */
    PROCESSOR("/nifi-api/flow/processor-types",
            "processorTypes",
            "/nifi-api/flow/processor-definition",
            "processors",
            "processors",
            "/nifi-api/process-groups/{id}/processors",
            null),

    /** Controller service components. */
    CONTROLLER_SERVICE("/nifi-api/flow/controller-service-types",
            "controllerServiceTypes",
            "/nifi-api/flow/controller-service-definition",
            "controller-services",
            "controllerServices",
            "/nifi-api/flow/process-groups/{id}/controller-services",
            "/nifi-api/flow/controller/controller-services"),

    /** Reporting task components. */
    REPORTING_TASK("/nifi-api/flow/reporting-task-types",
            "reportingTaskTypes",
            "/nifi-api/flow/reporting-task-definition",
            "reporting-tasks",
            "reportingTasks",
            null,
            "/nifi-api/flow/reporting-tasks");

    /** Common prefix for the additional-details endpoint shared by all component kinds. */
    private static final String ADDITIONAL_DETAILS_PREFIX = "/nifi-api/flow/additional-details";

    private final String listPath;
    private final String listKey;
    private final String definitionPathPrefix;
    private final String instanceSegment;
    private final String instanceListKey;
    private final String groupInstanceListPath;
    private final String controllerInstanceListPath;

    /**
     * Creates a new component kind.
     *
     * @param path           the API path that lists all types of this kind
     * @param key            the JSON key in the list response that holds the type array
     * @param defPathPrefix  the API path prefix for this kind's component definitions
     * @param segment        the path segment that names instances of this kind, such as {@code processors}
     * @param instanceKey    the JSON key in an instance listing that holds the instance array
     * @param groupListPath  the API path that lists the instances in a process group, with {@code {id}}
     *                       standing for the group ID, or null when no instance lives in a process group
     * @param controllerListPath the API path that lists the controller-level instances, or null when no
     *                       instance lives at controller level
     */
    NiFiComponentKind(final String path, final String key, final String defPathPrefix, final String segment,
                      final String instanceKey, final String groupListPath, final String controllerListPath) {
        this.listPath = path;
        this.listKey = key;
        this.definitionPathPrefix = defPathPrefix;
        this.instanceSegment = segment;
        this.instanceListKey = instanceKey;
        this.groupInstanceListPath = groupListPath;
        this.controllerInstanceListPath = controllerListPath;
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

    /**
     * Returns the API path that creates an instance of this kind. A kind whose instances live at only
     * one level is created at that level. A kind whose instances live in a process group and at
     * controller level is created at controller level when {@code controllerScope} is set.
     *
     * @param groupId the ID of the process group that holds a group-level instance
     * @param controllerScope whether a kind that lives at both levels is created at controller level
     * @return the creation path
     */
    String getCreatePath(final String groupId, final boolean controllerScope) {
        return isControllerLevel(controllerScope) ? "/nifi-api/controller/" + instanceSegment
                : "/nifi-api/process-groups/" + groupId + "/" + instanceSegment;
    }

    /**
     * Returns the API path that lists the instances at the level where
     * {@link #getCreatePath(String, boolean)} creates one for the same arguments.
     *
     * @param groupId the ID of the process group that holds a group-level instance
     * @param controllerScope whether a kind that lives at both levels is created at controller level
     * @return the instance listing path
     */
    String getInstanceListPath(final String groupId, final boolean controllerScope) {
        return isControllerLevel(controllerScope) ? controllerInstanceListPath
                : groupInstanceListPath.replace("{id}", groupId);
    }

    /**
     * Returns the JSON key in an instance listing that holds the instance array.
     *
     * @return the instance listing key
     */
    String getInstanceListKey() {
        return instanceListKey;
    }

    /**
     * Returns the API path prefix that, followed by an instance ID, reads or deletes that instance.
     *
     * @return the instance path prefix, ending with a slash
     */
    String getInstancePathPrefix() {
        return "/nifi-api/" + instanceSegment + "/";
    }

    private boolean isControllerLevel(final boolean controllerScope) {
        return groupInstanceListPath == null || controllerScope && controllerInstanceListPath != null;
    }
}

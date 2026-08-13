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

package org.qubership.nifi.tools.kb.render;

/**
 * Names NiFi component fields shared by component renderers.
 */
final class ComponentFields {

    /** Field containing supported controller service APIs. */
    static final String CONTROLLER_SERVICE_APIS = "controllerServiceApis";

    /** Field containing the deprecated flag. */
    static final String DEPRECATED = "deprecated";

    /** Field containing the deprecation reason. */
    static final String DEPRECATION_REASON = "deprecationReason";

    /** Field containing a description. */
    static final String DESCRIPTION = "description";

    /** Field containing a display name. */
    static final String DISPLAY_NAME = "displayName";

    /** Field containing a name. */
    static final String NAME = "name";

    /** Field containing the restricted flag. */
    static final String RESTRICTED = "restricted";

    /** Field containing component tags. */
    static final String TAGS = "tags";

    /** Field containing a component or API type. */
    static final String TYPE = "type";

    /** Field wrapping an allowable value. */
    static final String ALLOWABLE_VALUE = "allowableValue";

    private ComponentFields() {
        // utility class
    }
}

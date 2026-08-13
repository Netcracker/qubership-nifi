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

/**
 * Defines file names and JSON fields in the Knowledge Base output contract.
 */
public final class KnowledgeBaseFormat {

    /** Root directory for component output. */
    public static final String COMPONENTS_DIRECTORY = "components";

    /** Root directory for guide output. */
    public static final String GUIDES_DIRECTORY = "guides";

    /** File name of the completion manifest. */
    public static final String MANIFEST_FILE = "manifest.json";

    /** File name of a JSON index. */
    public static final String INDEX_JSON_FILE = "index.json";

    /** File name of a Markdown index. */
    public static final String INDEX_MARKDOWN_FILE = "index.md";

    /** File name of a lossless component definition. */
    public static final String COMPONENT_JSON_FILE = "component.json";

    /** File name of a searchable component document. */
    public static final String COMPONENT_MARKDOWN_FILE = "component.md";

    /** File name of a component's verbatim additional documentation. */
    public static final String ADDITIONAL_DETAILS_FILE = "additionalDetails.md";

    /** Manifest field containing the schema version. */
    public static final String SCHEMA_VERSION_FIELD = "schemaVersion";

    /** Manifest field containing the aggregate fingerprint. */
    public static final String FINGERPRINT_FIELD = "fingerprint";

    /** Manifest field containing component counts. */
    public static final String COUNTS_FIELD = "counts";

    /** Manifest field containing guide state. */
    public static final String GUIDES_FIELD = GUIDES_DIRECTORY;

    /** Per-guide field containing collection status. */
    public static final String STATUS_FIELD = "status";

    /** Per-guide field containing the generated output path. */
    public static final String OUTPUT_PATH_FIELD = "outputPath";

    /** Component field containing the documented type. */
    public static final String DOCUMENTED_TYPE_FIELD = "documentedType";

    /** Component field containing the lossless definition. */
    public static final String DEFINITION_FIELD = "definition";

    /** Component field containing derived additional-documentation state. */
    public static final String ADDITIONAL_DOCUMENTATION_FIELD = "additionalDocumentation";

    /** Additional-documentation field containing the advertised state. */
    public static final String ADVERTISED_FIELD = "advertised";

    /** Additional-documentation field containing the requested state. */
    public static final String REQUESTED_FIELD = "requested";

    /** Additional-documentation field containing the available state. */
    public static final String AVAILABLE_FIELD = "available";

    private KnowledgeBaseFormat() {
        // utility class
    }
}

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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qubership.nifi.tools.kb.model.ComponentRecord;
import org.qubership.nifi.tools.kb.model.GuideMode;
import org.qubership.nifi.tools.kb.model.GuideType;
import org.qubership.nifi.tools.kb.model.KnowledgeBase;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;

import java.util.List;

/**
 * Renders {@code manifest.json}, the completion marker and machine-readable root of the Knowledge
 * Base. It records the schema version, provenance, component counts, guide statuses, and the
 * aggregate catalog fingerprint.
 *
 * <p>Its presence marks a complete Knowledge Base, so it must be written only after every other
 * output file.
 */
public final class ManifestRenderer {

    /** The Knowledge Base schema version. */
    public static final String SCHEMA_VERSION = "1";

    private final JsonOutput json;

    /**
     * Creates a new manifest renderer.
     *
     * @param jsonOutput the deterministic JSON output helper
     */
    public ManifestRenderer(final JsonOutput jsonOutput) {
        this.json = jsonOutput;
    }

    /**
     * Renders the manifest bytes.
     *
     * @param kb          the Knowledge Base
     * @param fingerprint the aggregate catalog fingerprint
     * @return the serialized bytes
     */
    public byte[] render(final KnowledgeBase kb, final String fingerprint) {
        final ObjectNode root = json.mapper().createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);

        final ObjectNode builder = root.putObject("builder");
        builder.put("name", kb.provenance().builderName());
        builder.put("version", kb.provenance().builderVersion());

        root.put("generatedAt", kb.provenance().generatedAt().toString());

        final ObjectNode nifi = root.putObject("nifi");
        nifi.put("version", kb.provenance().nifiVersion());
        nifi.put("minimumSupportedVersion", kb.provenance().minimumSupportedVersion());
        nifi.put("baseUrl", kb.provenance().baseUrl());

        root.put("fingerprint", fingerprint);

        appendCounts(root, kb.components());
        appendGuides(root, kb);

        return json.toBytes(root);
    }

    private void appendCounts(final ObjectNode root, final List<ComponentRecord> components) {
        final ObjectNode counts = root.putObject("counts");
        counts.put("processors", countKind(components, NiFiComponentKind.PROCESSOR));
        counts.put("controllerServices", countKind(components, NiFiComponentKind.CONTROLLER_SERVICE));
        counts.put("reportingTasks", countKind(components, NiFiComponentKind.REPORTING_TASK));
        counts.put("componentsWithAdditionalDocumentation",
                (int) components.stream().filter(c -> c.additionalDocumentation().isAvailable()).count());
    }

    private void appendGuides(final ObjectNode root, final KnowledgeBase kb) {
        final ObjectNode guides = root.putObject("guides");
        guides.put("mode", kb.guides().mode().manifestMode());
        for (final GuideType type : GuideType.values()) {
            final ObjectNode guideNode = guides.putObject(type.getManifestKey());
            guideNode.put("status", kb.guides().mode().manifestStatus());
            if (kb.guides().mode() == GuideMode.REQUIRED) {
                guideNode.put("sourcePath", type.getSourcePath());
                guideNode.put("outputPath", type.getOutputPath());
            }
        }
    }

    private static int countKind(final List<ComponentRecord> components, final NiFiComponentKind kind) {
        return (int) components.stream().filter(c -> c.identity().getKind() == kind).count();
    }
}

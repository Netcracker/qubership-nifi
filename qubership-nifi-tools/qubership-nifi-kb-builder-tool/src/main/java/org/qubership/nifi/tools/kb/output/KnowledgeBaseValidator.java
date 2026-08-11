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

package org.qubership.nifi.tools.kb.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.nifi.tools.kb.model.AdditionalDocumentationState;
import org.qubership.nifi.tools.kb.model.ComponentKindLayout;
import org.qubership.nifi.tools.kb.model.GuideMode;
import org.qubership.nifi.tools.kb.model.GuideType;
import org.qubership.nifi.tools.kb.model.KnowledgeBaseFormat;
import org.qubership.nifi.tools.kb.render.ManifestRenderer;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Validates a staged Knowledge Base before it replaces the destination, rejecting anything the
 * output contract does not allow a consumer to encounter: a file the contract requires but the
 * staging tree lacks, a recorded value that contradicts what was written, or a value in a format a
 * consumer cannot parse.
 */
public final class KnowledgeBaseValidator {

    private static final Pattern FINGERPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final int TOP_LEVEL_FIELD_COUNT = 3;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Validates the staged Knowledge Base rooted at the given directory.
     *
     * @param root the staging root
     * @throws OutputException when validation fails
     */
    public void validate(final Path root) {
        requireFile(root.resolve(KnowledgeBaseFormat.MANIFEST_FILE));
        final Path components = root.resolve(KnowledgeBaseFormat.COMPONENTS_DIRECTORY);
        requireFile(components.resolve(KnowledgeBaseFormat.INDEX_JSON_FILE));
        requireFile(components.resolve(KnowledgeBaseFormat.INDEX_MARKDOWN_FILE));

        final JsonNode manifest = read(root.resolve(KnowledgeBaseFormat.MANIFEST_FILE));
        if (!ManifestRenderer.SCHEMA_VERSION.equals(
                manifest.path(KnowledgeBaseFormat.SCHEMA_VERSION_FIELD).asText())) {
            throw new OutputException("Unsupported manifest schema version: "
                    + manifest.path(KnowledgeBaseFormat.SCHEMA_VERSION_FIELD).asText());
        }
        if (!FINGERPRINT.matcher(manifest.path(KnowledgeBaseFormat.FINGERPRINT_FIELD).asText("")).matches()) {
            throw new OutputException("Manifest fingerprint is not a valid sha256 value");
        }
        validateGuides(root, manifest);
        validateComponents(root, manifest);
    }

    private void validateGuides(final Path root, final JsonNode manifest) {
        final String mode = manifest.path(KnowledgeBaseFormat.GUIDES_FIELD).path("mode").asText();
        final Path guidesDir = root.resolve(KnowledgeBaseFormat.GUIDES_DIRECTORY);
        if (GuideMode.REQUIRED.manifestMode().equals(mode)) {
            requireFile(guidesDir.resolve(KnowledgeBaseFormat.INDEX_JSON_FILE));
            for (final GuideType type : GuideType.values()) {
                requireFile(root.resolve(type.getOutputPath()));
                requireStatus(manifest, type, GuideMode.REQUIRED.manifestStatus());
            }
        } else if (GuideMode.SKIP.manifestMode().equals(mode)) {
            if (Files.exists(guidesDir)) {
                throw new OutputException("guides/ directory must be absent in skip mode");
            }
            for (final GuideType type : GuideType.values()) {
                requireStatus(manifest, type, GuideMode.SKIP.manifestStatus());
            }
        } else {
            throw new OutputException("Manifest guide mode must be 'required' or 'skip', but was: " + mode);
        }
    }

    private void requireStatus(final JsonNode manifest, final GuideType type, final String expected) {
        final String status = manifest.path(KnowledgeBaseFormat.GUIDES_FIELD).path(type.getManifestKey())
                .path(KnowledgeBaseFormat.STATUS_FIELD).asText();
        if (!expected.equals(status)) {
            throw new OutputException("Guide " + type.getManifestKey() + " status must be '" + expected
                    + "' but was '" + status + "'");
        }
    }

    private void validateComponents(final Path root, final JsonNode manifest) {
        for (final NiFiComponentKind kind : NiFiComponentKind.values()) {
            final Path kindDir = root.resolve(KnowledgeBaseFormat.COMPONENTS_DIRECTORY)
                    .resolve(ComponentKindLayout.directoryName(kind));
            final long dirs = countComponentDirs(kindDir);
            final long expected = manifestCount(manifest, kind);
            if (dirs != expected) {
                throw new OutputException("Component count mismatch for " + kind + ": manifest " + expected
                        + " but found " + dirs + " directories");
            }
            if (Files.isDirectory(kindDir)) {
                validateComponentDirs(kindDir);
            }
        }
    }

    private void validateComponentDirs(final Path kindDir) {
        try (Stream<Path> dirs = Files.list(kindDir)) {
            dirs.filter(Files::isDirectory).forEach(this::validateComponentDir);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to list component directories: " + kindDir, e);
        }
    }

    private void validateComponentDir(final Path dir) {
        requireFile(dir.resolve(KnowledgeBaseFormat.COMPONENT_JSON_FILE));
        requireFile(dir.resolve(KnowledgeBaseFormat.COMPONENT_MARKDOWN_FILE));
        final JsonNode record = read(dir.resolve(KnowledgeBaseFormat.COMPONENT_JSON_FILE));
        if (record.size() != TOP_LEVEL_FIELD_COUNT || !record.has(KnowledgeBaseFormat.DOCUMENTED_TYPE_FIELD)
                || !record.has(KnowledgeBaseFormat.DEFINITION_FIELD)
                || !record.has(KnowledgeBaseFormat.ADDITIONAL_DOCUMENTATION_FIELD)) {
            throw new OutputException("component.json must contain exactly documentedType, definition, and "
                    + "additionalDocumentation: " + dir.getFileName());
        }
        final JsonNode state = record.path(KnowledgeBaseFormat.ADDITIONAL_DOCUMENTATION_FIELD);
        final boolean available = state.path(KnowledgeBaseFormat.AVAILABLE_FIELD).asBoolean();
        final Path detailsFile = dir.resolve(AdditionalDocumentationState.ADDITIONAL_DETAILS_FILE);
        if (available) {
            if (!state.path(KnowledgeBaseFormat.ADVERTISED_FIELD).asBoolean()
                    || !state.path(KnowledgeBaseFormat.REQUESTED_FIELD).asBoolean()) {
                throw new OutputException("available additional documentation must be advertised and requested: "
                        + dir.getFileName());
            }
            requireFile(detailsFile);
        } else if (Files.exists(detailsFile)) {
            throw new OutputException("additionalDetails.md must be absent when unavailable: " + dir.getFileName());
        }
    }

    private long manifestCount(final JsonNode manifest, final NiFiComponentKind kind) {
        return manifest.path(KnowledgeBaseFormat.COUNTS_FIELD)
                .path(ComponentKindLayout.manifestCountField(kind)).asLong();
    }

    private long countComponentDirs(final Path kindDir) {
        if (!Files.isDirectory(kindDir)) {
            return 0;
        }
        try (Stream<Path> dirs = Files.list(kindDir)) {
            return dirs.filter(Files::isDirectory).count();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to count component directories: " + kindDir, e);
        }
    }

    private JsonNode read(final Path file) {
        try {
            return mapper.readTree(Files.readAllBytes(file));
        } catch (final IOException e) {
            throw new OutputException("Failed to read staged file: " + file, e);
        }
    }

    private static void requireFile(final Path file) {
        if (!Files.isRegularFile(file)) {
            throw new OutputException("Required Knowledge Base file is missing: " + file);
        }
    }
}

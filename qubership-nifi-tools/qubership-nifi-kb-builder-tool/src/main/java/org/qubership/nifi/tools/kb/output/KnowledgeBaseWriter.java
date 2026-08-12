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

import org.qubership.nifi.tools.kb.model.ComponentKindLayout;
import org.qubership.nifi.tools.kb.model.ComponentRecord;
import org.qubership.nifi.tools.kb.model.GuideDocument;
import org.qubership.nifi.tools.kb.model.GuideMode;
import org.qubership.nifi.tools.kb.model.KnowledgeBase;
import org.qubership.nifi.tools.kb.model.KnowledgeBaseFormat;
import org.qubership.nifi.tools.kb.render.CatalogFingerprint;
import org.qubership.nifi.tools.kb.render.ComponentJsonRenderer;
import org.qubership.nifi.tools.kb.render.ComponentMarkdownRenderer;
import org.qubership.nifi.tools.kb.render.ComponentSorting;
import org.qubership.nifi.tools.kb.render.GuideIndexRenderer;
import org.qubership.nifi.tools.kb.render.IndexRenderer;
import org.qubership.nifi.tools.kb.render.JsonOutput;
import org.qubership.nifi.tools.kb.render.ManifestRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a complete Knowledge Base into a staging directory in the deterministic order required by
 * the output contract: component files and indexes first, then the aggregate catalog fingerprint,
 * then guides, and finally the manifest.
 */
public final class KnowledgeBaseWriter {

    private final ComponentJsonRenderer componentJson;
    private final ComponentMarkdownRenderer componentMarkdown = new ComponentMarkdownRenderer();
    private final IndexRenderer index;
    private final ManifestRenderer manifest;
    private final GuideIndexRenderer guideIndex;

    /**
     * Creates a new writer with a private JSON mapper. The mapper is deliberately not shared: the
     * output is byte-deterministic and the catalog fingerprint is computed over it, so nothing outside
     * this module may customize the serialization.
     */
    public KnowledgeBaseWriter() {
        this(new JsonOutput());
    }

    /**
     * Creates a new writer.
     *
     * @param jsonOutput the deterministic JSON output helper
     */
    public KnowledgeBaseWriter(final JsonOutput jsonOutput) {
        this.componentJson = new ComponentJsonRenderer(jsonOutput);
        this.index = new IndexRenderer(jsonOutput);
        this.manifest = new ManifestRenderer(jsonOutput);
        this.guideIndex = new GuideIndexRenderer(jsonOutput);
    }

    /**
     * Writes the complete Knowledge Base into the given staging root.
     *
     * @param root the staging root directory
     * @param kb   the Knowledge Base to render
     * @throws OutputException when any part of the tree cannot be written
     */
    public void writeTo(final Path root, final KnowledgeBase kb) {
        try {
            doWrite(root, kb);
        } catch (final IOException e) {
            throw new OutputException("Failed to write Knowledge Base to staging", e);
        }
    }

    private void doWrite(final Path root, final KnowledgeBase kb) throws IOException {
        final List<ComponentRecord> sorted = new ArrayList<>(kb.components());
        sorted.sort(ComponentSorting.BY_IDENTITY);

        final Path componentsDir = root.resolve(KnowledgeBaseFormat.COMPONENTS_DIRECTORY);
        Files.createDirectories(componentsDir);

        final List<String> catalogPaths = new ArrayList<>();

        for (final ComponentRecord componentRecord : sorted) {
            writeComponent(componentsDir, componentRecord, catalogPaths);
        }

        Files.write(componentsDir.resolve(KnowledgeBaseFormat.INDEX_JSON_FILE), index.renderJson(sorted));
        Files.writeString(componentsDir.resolve(KnowledgeBaseFormat.INDEX_MARKDOWN_FILE),
                index.renderMarkdown(sorted));
        catalogPaths.add(componentPath(KnowledgeBaseFormat.INDEX_JSON_FILE));
        catalogPaths.add(componentPath(KnowledgeBaseFormat.INDEX_MARKDOWN_FILE));

        final String fingerprint = CatalogFingerprint.compute(root, catalogPaths);

        if (kb.guides().mode() == GuideMode.REQUIRED) {
            writeGuides(root, kb);
        }

        Files.write(root.resolve(KnowledgeBaseFormat.MANIFEST_FILE), manifest.render(kb, fingerprint));
    }

    private void writeComponent(final Path componentsDir, final ComponentRecord componentRecord,
                                final List<String> catalogPaths) throws IOException {
        final String kindDir = ComponentKindLayout.directoryName(componentRecord.identity().getKind());
        final Path dir = componentsDir.resolve(kindDir).resolve(componentRecord.identity().directoryName());
        Files.createDirectories(dir);

        Files.write(dir.resolve(KnowledgeBaseFormat.COMPONENT_JSON_FILE), componentJson.render(componentRecord));
        Files.writeString(dir.resolve(KnowledgeBaseFormat.COMPONENT_MARKDOWN_FILE),
                componentMarkdown.render(componentRecord));
        catalogPaths.add(componentPath(ComponentSorting.relativePath(componentRecord.identity(),
                KnowledgeBaseFormat.COMPONENT_JSON_FILE)));
        catalogPaths.add(componentPath(ComponentSorting.relativePath(componentRecord.identity(),
                KnowledgeBaseFormat.COMPONENT_MARKDOWN_FILE)));

        if (componentRecord.additionalDocumentation().isAvailable()) {
            Files.writeString(dir.resolve(KnowledgeBaseFormat.ADDITIONAL_DETAILS_FILE),
                    componentRecord.additionalDetailsContent().orElse(""));
            catalogPaths.add(componentPath(ComponentSorting.relativePath(componentRecord.identity(),
                    KnowledgeBaseFormat.ADDITIONAL_DETAILS_FILE)));
        }
    }

    private void writeGuides(final Path root, final KnowledgeBase kb) throws IOException {
        final Path guidesDir = root.resolve(KnowledgeBaseFormat.GUIDES_DIRECTORY);
        Files.createDirectories(guidesDir);
        for (final GuideDocument document : kb.guides().documents()) {
            Files.writeString(guidesDir.resolve(document.type().getOutputFileName()), document.markdown());
        }
        Files.write(guidesDir.resolve(KnowledgeBaseFormat.INDEX_JSON_FILE), guideIndex.render(kb.guides()));
    }

    private static String componentPath(final String relativePath) {
        return KnowledgeBaseFormat.COMPONENTS_DIRECTORY + '/' + relativePath;
    }
}

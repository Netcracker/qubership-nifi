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
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a complete Knowledge Base into a staging directory in the deterministic order required by
 * the output contract: component files and indexes first, then guides, then the aggregate fingerprint
 * over everything written, and finally the manifest.
 *
 * <p>The fingerprint covers the guides as well as the catalog because consumers use it as a cache
 * key. A catalog-only build and a full build of the same NiFi hold different content, so they must
 * not share a fingerprint.</p>
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
     * <p>The renderers report I/O trouble as {@link UncheckedIOException} because they know nothing
     * about the output stage. Staging is that stage, so both forms surface as an
     * {@link OutputException} and carry the output exit code.</p>
     *
     * @param root the staging root directory
     * @param kb   the Knowledge Base to render
     * @throws OutputException when any part of the tree cannot be written
     */
    public void writeTo(final Path root, final KnowledgeBase kb) {
        try {
            doWrite(root, kb);
        } catch (final IOException | UncheckedIOException e) {
            throw new OutputException("Failed to write Knowledge Base to staging", e);
        }
    }

    private void doWrite(final Path root, final KnowledgeBase kb) throws IOException {
        final List<ComponentRecord> sorted = new ArrayList<>(kb.components());
        sorted.sort(ComponentSorting.BY_IDENTITY);

        final Path componentsDir = root.resolve(KnowledgeBaseFormat.COMPONENTS_DIRECTORY);
        createKindDirectories(componentsDir);

        final List<String> coveredPaths = new ArrayList<>();

        for (final ComponentRecord componentRecord : sorted) {
            writeComponent(componentsDir, componentRecord, coveredPaths);
        }

        Files.write(componentsDir.resolve(KnowledgeBaseFormat.INDEX_JSON_FILE), index.renderJson(sorted));
        Files.writeString(componentsDir.resolve(KnowledgeBaseFormat.INDEX_MARKDOWN_FILE),
                index.renderMarkdown(sorted));
        coveredPaths.add(componentPath(KnowledgeBaseFormat.INDEX_JSON_FILE));
        coveredPaths.add(componentPath(KnowledgeBaseFormat.INDEX_MARKDOWN_FILE));

        if (kb.guides().mode() == GuideMode.REQUIRED) {
            writeGuides(root, kb, coveredPaths);
        }

        final String fingerprint = CatalogFingerprint.compute(root, coveredPaths);

        Files.write(root.resolve(KnowledgeBaseFormat.MANIFEST_FILE), manifest.render(kb, fingerprint));
    }

    /**
     * Creates the components directory and one directory per component kind. A kind the target NiFi
     * exposes none of still gets its directory: the layout is part of the output contract, so a
     * consumer walking it finds an empty directory rather than a missing path.
     *
     * @param componentsDir the components directory
     * @throws IOException when a directory cannot be created
     */
    private void createKindDirectories(final Path componentsDir) throws IOException {
        Files.createDirectories(componentsDir);
        for (final NiFiComponentKind kind : NiFiComponentKind.values()) {
            Files.createDirectories(componentsDir.resolve(ComponentKindLayout.directoryName(kind)));
        }
    }

    private void writeComponent(final Path componentsDir, final ComponentRecord componentRecord,
                                final List<String> coveredPaths) throws IOException {
        // The same helper that index.json publishes as a component's "path" decides where the files
        // land, so the recorded location cannot drift away from the written one.
        final Path dir = componentsDir.resolve(ComponentSorting.directoryPath(componentRecord.identity()));
        Files.createDirectories(dir);

        Files.write(dir.resolve(KnowledgeBaseFormat.COMPONENT_JSON_FILE), componentJson.render(componentRecord));
        Files.writeString(dir.resolve(KnowledgeBaseFormat.COMPONENT_MARKDOWN_FILE),
                componentMarkdown.render(componentRecord));
        coveredPaths.add(componentPath(ComponentSorting.relativePath(componentRecord.identity(),
                KnowledgeBaseFormat.COMPONENT_JSON_FILE)));
        coveredPaths.add(componentPath(ComponentSorting.relativePath(componentRecord.identity(),
                KnowledgeBaseFormat.COMPONENT_MARKDOWN_FILE)));

        if (componentRecord.additionalDocumentation().isAvailable()) {
            Files.writeString(dir.resolve(KnowledgeBaseFormat.ADDITIONAL_DETAILS_FILE),
                    componentRecord.additionalDetailsContent().orElse(""));
            coveredPaths.add(componentPath(ComponentSorting.relativePath(componentRecord.identity(),
                    KnowledgeBaseFormat.ADDITIONAL_DETAILS_FILE)));
        }
    }

    private void writeGuides(final Path root, final KnowledgeBase kb, final List<String> coveredPaths)
            throws IOException {
        final Path guidesDir = root.resolve(KnowledgeBaseFormat.GUIDES_DIRECTORY);
        Files.createDirectories(guidesDir);
        for (final GuideDocument document : kb.guides().documents()) {
            Files.writeString(guidesDir.resolve(document.type().getOutputFileName()), document.markdown());
            coveredPaths.add(document.type().getOutputPath());
        }
        Files.write(guidesDir.resolve(KnowledgeBaseFormat.INDEX_JSON_FILE), guideIndex.render(kb.guides()));
        coveredPaths.add(KnowledgeBaseFormat.GUIDES_DIRECTORY + '/' + KnowledgeBaseFormat.INDEX_JSON_FILE);
    }

    private static String componentPath(final String relativePath) {
        return KnowledgeBaseFormat.COMPONENTS_DIRECTORY + '/' + relativePath;
    }
}

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
import org.qubership.nifi.tools.kb.render.CatalogFingerprint;
import org.qubership.nifi.tools.kb.render.ComponentJsonRenderer;
import org.qubership.nifi.tools.kb.render.ComponentMarkdownRenderer;
import org.qubership.nifi.tools.kb.render.ComponentSorting;
import org.qubership.nifi.tools.kb.render.GuideIndexRenderer;
import org.qubership.nifi.tools.kb.render.IndexRenderer;
import org.qubership.nifi.tools.kb.render.JsonOutput;
import org.qubership.nifi.tools.kb.render.ManifestRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private static final String COMPONENTS = "components";
    private static final String COMPONENT_JSON = "component.json";
    private static final String COMPONENT_MD = "component.md";
    private static final String ADDITIONAL_DETAILS = "additionalDetails.md";

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

        final Path componentsDir = root.resolve(COMPONENTS);
        Files.createDirectories(componentsDir);

        final List<String> catalogPaths = new ArrayList<>();

        for (final ComponentRecord record : sorted) {
            writeComponent(componentsDir, record, catalogPaths);
        }

        writeBytes(componentsDir.resolve("index.json"), index.renderJson(sorted));
        writeString(componentsDir.resolve("index.md"), index.renderMarkdown(sorted));
        catalogPaths.add(COMPONENTS + "/index.json");
        catalogPaths.add(COMPONENTS + "/index.md");

        final String fingerprint = CatalogFingerprint.compute(root, catalogPaths);

        if (kb.guides().mode() == GuideMode.REQUIRED) {
            writeGuides(root, kb);
        }

        writeBytes(root.resolve("manifest.json"), manifest.render(kb, fingerprint));
    }

    private void writeComponent(final Path componentsDir, final ComponentRecord record,
                                final List<String> catalogPaths) throws IOException {
        final String kindDir = ComponentKindLayout.directoryName(record.identity().getKind());
        final Path dir = componentsDir.resolve(kindDir).resolve(record.identity().directoryName());
        Files.createDirectories(dir);

        writeBytes(dir.resolve(COMPONENT_JSON), componentJson.render(record));
        writeString(dir.resolve(COMPONENT_MD), componentMarkdown.render(record));
        catalogPaths.add(COMPONENTS + "/" + ComponentSorting.relativePath(record.identity(), COMPONENT_JSON));
        catalogPaths.add(COMPONENTS + "/" + ComponentSorting.relativePath(record.identity(), COMPONENT_MD));

        if (record.additionalDocumentation().isAvailable()) {
            final byte[] verbatim = record.additionalDetailsContent().orElse("")
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(dir.resolve(ADDITIONAL_DETAILS), verbatim);
            catalogPaths.add(COMPONENTS + "/" + ComponentSorting.relativePath(record.identity(), ADDITIONAL_DETAILS));
        }
    }

    private void writeGuides(final Path root, final KnowledgeBase kb) throws IOException {
        final Path guidesDir = root.resolve("guides");
        Files.createDirectories(guidesDir);
        for (final GuideDocument document : kb.guides().documents()) {
            writeString(guidesDir.resolve(document.type().getOutputFileName()), document.markdown());
        }
        writeBytes(guidesDir.resolve("index.json"), guideIndex.render(kb.guides()));
    }

    private static void writeBytes(final Path file, final byte[] content) throws IOException {
        Files.write(file, content);
    }

    private static void writeString(final Path file, final String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}

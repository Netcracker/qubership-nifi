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

package org.qubership.nifi.flowanalysis.scheduling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.flow.VersionedComponent;
import org.apache.nifi.flow.VersionedProcessor;
import org.apache.nifi.flowanalysis.AbstractFlowAnalysisRule;
import org.apache.nifi.flowanalysis.ComponentAnalysisResult;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Flow analysis rule that reports a processor which supports batching but has Run Duration set to 0.
 * A Run Duration above 0 lets the framework batch repository commits and can raise throughput on
 * high-volume flows; leaving it at 0 is correct for low-volume or latency-sensitive processing, so
 * the rule is advisory and best used with the WARN enforcement policy.
 */
@Tags({"processor", "run duration", "batching", "scheduling"})
@CapabilityDescription("Produces a rule violation for each processor that supports batching (that is, "
        + "exposes a Run Duration control) but has Run Duration set to 0. A Run Duration above 0 lets "
        + "the framework batch repository commits and can raise throughput on high-volume flows. The "
        + "rule reads the NAR extension manifests under NIFI_HOME/work/nar/extensions when it is "
        + "enabled.")
public final class RequireRunDurationForBatchingProcessors extends AbstractFlowAnalysisRule {

    private static final String DEFAULT_NIFI_HOME = "/opt/nifi/nifi-current";
    private static final Path RELATIVE_MANIFEST_DIRECTORY = Path.of("work", "nar", "extensions");
    private static final String MANIFEST_ENTRY = "META-INF/docs/extension-manifest.xml";
    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String VIOLATION_MESSAGE =
            "Run Duration is 0; a value above 0 can raise throughput on high-volume flows.";
    private static final String VIOLATION_EXPLANATION =
            "A Run Duration above 0 lets the framework batch repository commits, which raises throughput "
            + "on high-volume flows with lightweight per-invocation work. Keep it at 0 for low-volume or "
            + "latency-sensitive processing, or where the processor deletes data from a remote source "
            + "after commit.";

    private final AtomicReference<Set<String>> batchingTypes = new AtomicReference<>(Set.of());

    /**
     * Scans the NAR extension manifests once and caches the processor types that support batching.
     */
    @OnEnabled
    public void onEnabled() {
        loadBatchingTypes(manifestDirectory());
    }

    void loadBatchingTypes(final Path directory) {
        if (!Files.isDirectory(directory)) {
            getLogger().warn("Directory {} does not exist; the rule reports nothing until the "
                    + "extension manifests can be read.", directory);
            batchingTypes.set(Set.of());
            return;
        }
        final Set<String> types = scanBatchingTypes(directory);
        batchingTypes.set(types);
        getLogger().info("Found {} batching-capable processor types in {}", types.size(), directory);
    }

    /**
     * Drops the cached set of batching-capable processor types.
     */
    @OnDisabled
    public void onDisabled() {
        batchingTypes.set(Set.of());
    }

    @Override
    public Collection<ComponentAnalysisResult> analyzeComponent(
            final VersionedComponent component, final FlowAnalysisRuleContext context) {

        if (!(component instanceof final VersionedProcessor processor)) {
            return List.of();
        }
        if (!batchingTypes.get().contains(processor.getType())) {
            return List.of();
        }
        final Long runDurationMillis = processor.getRunDurationMillis();
        if (runDurationMillis != null && runDurationMillis > 0L) {
            return List.of();
        }
        return List.of(new ComponentAnalysisResult("run-duration-zero", VIOLATION_MESSAGE, VIOLATION_EXPLANATION));
    }

    private static Path manifestDirectory() {
        final String nifiHome = System.getenv("NIFI_HOME");
        final String home = (nifiHome == null || nifiHome.isBlank()) ? DEFAULT_NIFI_HOME : nifiHome;
        return Path.of(home).resolve(RELATIVE_MANIFEST_DIRECTORY);
    }

    Set<String> scanBatchingTypes(final Path directory) {
        final DocumentBuilderFactory factory = secureDocumentBuilderFactory();
        if (factory == null) {
            return Set.of();
        }
        final Set<String> types = new HashSet<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.forEach(entry -> collectBatchingTypes(entry, factory, types));
        } catch (final IOException e) {
            getLogger().warn("Could not list the extension manifest directory {}", directory, e);
            return Set.of();
        }
        return Set.copyOf(types);
    }

    private void collectBatchingTypes(final Path entry, final DocumentBuilderFactory factory,
                                      final Set<String> types) {
        try {
            if (Files.isDirectory(entry)) {
                collectFromUnpackedNar(entry, factory, types);
            } else if (entry.getFileName().toString().endsWith(".nar")) {
                collectFromNarArchive(entry, factory, types);
            }
        } catch (final IOException | ParserConfigurationException | SAXException e) {
            getLogger().debug("Skipping {} while scanning for batching support", entry, e);
        }
    }

    private static void collectFromUnpackedNar(final Path unpackedNar, final DocumentBuilderFactory factory,
                                               final Set<String> types)
            throws IOException, ParserConfigurationException, SAXException {
        final Path manifest = unpackedNar.resolve(MANIFEST_ENTRY);
        if (!Files.isRegularFile(manifest)) {
            return;
        }
        try (InputStream in = Files.newInputStream(manifest)) {
            addBatchingTypes(factory.newDocumentBuilder().parse(in), types);
        }
    }

    private static void collectFromNarArchive(final Path nar, final DocumentBuilderFactory factory,
                                              final Set<String> types)
            throws IOException, ParserConfigurationException, SAXException {
        try (ZipFile zip = new ZipFile(nar.toFile())) {
            final ZipEntry entry = zip.getEntry(MANIFEST_ENTRY);
            if (entry == null) {
                return;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                addBatchingTypes(factory.newDocumentBuilder().parse(in), types);
            }
        }
    }

    private static void addBatchingTypes(final Document manifest, final Set<String> types) {
        final NodeList extensions = manifest.getElementsByTagName("extension");
        for (int i = 0; i < extensions.getLength(); i++) {
            final Element extension = (Element) extensions.item(i);
            if ("PROCESSOR".equals(directChildText(extension, "type"))
                    && "true".equals(directChildText(extension, "supportsBatching"))) {
                final String name = directChildText(extension, "name");
                if (name != null && !name.isBlank()) {
                    types.add(name.trim());
                }
            }
        }
    }

    private static String directChildText(final Element parent, final String tagName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return child.getTextContent();
            }
        }
        return null;
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(DISALLOW_DOCTYPE, true);
            factory.setExpandEntityReferences(false);
            return factory;
        } catch (final ParserConfigurationException e) {
            return null;
        }
    }
}

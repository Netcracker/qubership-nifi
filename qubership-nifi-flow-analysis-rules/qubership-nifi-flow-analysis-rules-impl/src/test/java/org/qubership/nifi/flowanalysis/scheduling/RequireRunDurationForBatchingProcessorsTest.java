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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.nifi.flowanalysis.Fixtures.controllerService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.nifi.flow.VersionedProcessor;
import org.apache.nifi.flowanalysis.ComponentAnalysisResult;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleContext;
import org.apache.nifi.flowanalysis.FlowAnalysisRuleInitializationContext;
import org.apache.nifi.logging.ComponentLog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.flowanalysis.Fixtures;

public class RequireRunDurationForBatchingProcessorsTest {

    private static final String BATCHING_TYPE = "com.example.BatchingProcessor";
    private static final String PLAIN_TYPE = "com.example.PlainProcessor";

    private final RequireRunDurationForBatchingProcessors rule = new RequireRunDurationForBatchingProcessors();
    private final FlowAnalysisRuleContext analysisContext = mock(FlowAnalysisRuleContext.class);

    @TempDir
    private Path manifestDirectory;

    @BeforeEach
    public void initializeRule() throws Exception {
        FlowAnalysisRuleInitializationContext initContext = mock(FlowAnalysisRuleInitializationContext.class);
        when(initContext.getIdentifier()).thenReturn("test-rule");
        when(initContext.getLogger()).thenReturn(mock(ComponentLog.class));
        rule.initialize(initContext);
    }

    // ---- scanBatchingTypes ----

    @Test
    public void collectsOnlyProcessorTypesThatSupportBatching() throws IOException {
        writeUnpackedNar("a", manifest(
                extension(BATCHING_TYPE, "PROCESSOR", "true"),
                extension(PLAIN_TYPE, "PROCESSOR", "false")));
        writeUnpackedNar("b", manifest(
                extension("com.example.NoFlag", "PROCESSOR", null)));

        assertEquals(Set.of(BATCHING_TYPE), rule.scanBatchingTypes(manifestDirectory));
    }

    @Test
    public void ignoresNonProcessorExtensions() throws IOException {
        writeUnpackedNar("a", manifest(
                extension("com.example.Service", "CONTROLLER_SERVICE", "true"),
                extension(BATCHING_TYPE, "PROCESSOR", "true")));

        assertEquals(Set.of(BATCHING_TYPE), rule.scanBatchingTypes(manifestDirectory));
    }

    @Test
    public void doesNotConfuseNestedPropertyNameWithExtensionName() throws IOException {
        writeUnpackedNar("a", manifest(
                "<extension><name>" + BATCHING_TYPE + "</name><type>PROCESSOR</type>"
                        + "<properties><property><name>Some Property</name></property></properties>"
                        + "<supportsBatching>true</supportsBatching></extension>"));

        assertEquals(Set.of(BATCHING_TYPE), rule.scanBatchingTypes(manifestDirectory));
    }

    @Test
    public void alsoScansNarArchives() throws IOException {
        writeNarArchive("archive.nar", manifest(extension(BATCHING_TYPE, "PROCESSOR", "true")));

        assertEquals(Set.of(BATCHING_TYPE), rule.scanBatchingTypes(manifestDirectory));
    }

    @Test
    public void skipsUnreadableEntriesWithoutFailing() throws IOException {
        writeUnpackedNar("good", manifest(extension(BATCHING_TYPE, "PROCESSOR", "true")));
        Files.createDirectories(manifestDirectory.resolve("empty.nar-unpacked/NAR-INF"));
        Files.writeString(manifestDirectory.resolve("broken.nar"), "not a zip");
        Files.writeString(manifestDirectory.resolve("notes.txt"), "ignored");

        assertEquals(Set.of(BATCHING_TYPE), rule.scanBatchingTypes(manifestDirectory));
    }

    @Test
    public void returnsEmptySetForMissingDirectory() {
        assertTrue(rule.scanBatchingTypes(manifestDirectory.resolve("does-not-exist")).isEmpty());
    }

    // ---- loadBatchingTypes + analyzeComponent ----

    @Test
    public void reportsBatchingProcessorWithZeroRunDuration() throws IOException {
        enableWith(BATCHING_TYPE);

        ComponentAnalysisResult result = single(analyze(processor("p-1", BATCHING_TYPE, 0L)));

        assertEquals("run-duration-zero", result.getIssueId());
        assertTrue(result.getMessage().contains("Run Duration is 0"), result.getMessage());
        assertTrue(result.getExplanation().contains("batch repository commits"), result.getExplanation());
    }

    @Test
    public void reportsBatchingProcessorWithNullRunDuration() throws IOException {
        enableWith(BATCHING_TYPE);

        assertEquals(1, analyze(processor("p-1", BATCHING_TYPE, null)).size());
    }

    @Test
    public void noViolationWhenRunDurationAboveZero() throws IOException {
        enableWith(BATCHING_TYPE);

        assertTrue(analyze(processor("p-1", BATCHING_TYPE, 100L)).isEmpty());
    }

    @Test
    public void noViolationWhenProcessorTypeDoesNotSupportBatching() throws IOException {
        enableWith(BATCHING_TYPE);

        assertTrue(analyze(processor("p-1", PLAIN_TYPE, 0L)).isEmpty());
    }

    @Test
    public void noViolationWhenRuleWasNeverEnabled() {
        assertTrue(analyze(processor("p-1", BATCHING_TYPE, 0L)).isEmpty());
    }

    @Test
    public void loadBatchingTypesIgnoresMissingDirectory() {
        rule.loadBatchingTypes(manifestDirectory.resolve("does-not-exist"));

        assertTrue(analyze(processor("p-1", BATCHING_TYPE, 0L)).isEmpty());
    }

    @Test
    public void onEnabledReportsNothingWhenTheManifestDirectoryIsAbsent() {
        // without NIFI_HOME the rule falls back to /opt/nifi/nifi-current, which is not present here
        Assumptions.assumeTrue(System.getenv("NIFI_HOME") == null, "NIFI_HOME is set in this environment");

        rule.onEnabled();

        assertTrue(analyze(processor("p-1", BATCHING_TYPE, 0L)).isEmpty());
    }

    @Test
    public void ignoresNonProcessorComponents() throws IOException {
        enableWith(BATCHING_TYPE);

        assertTrue(rule.analyzeComponent(controllerService("cs-1", "Pool"), analysisContext).isEmpty());
    }

    // ---- helpers ----

    private void enableWith(final String... batchingTypes) throws IOException {
        StringBuilder extensions = new StringBuilder();
        for (final String type : batchingTypes) {
            extensions.append(extension(type, "PROCESSOR", "true"));
        }
        writeUnpackedNar("bundle", manifest(extensions.toString()));
        rule.loadBatchingTypes(manifestDirectory);
    }

    private Collection<ComponentAnalysisResult> analyze(final VersionedProcessor processor) {
        return rule.analyzeComponent(processor, analysisContext);
    }

    private static ComponentAnalysisResult single(final Collection<ComponentAnalysisResult> results) {
        assertEquals(1, results.size(), () -> "expected exactly one violation, got " + results);
        return results.iterator().next();
    }

    private static VersionedProcessor processor(final String id, final String type, final Long runDurationMillis) {
        VersionedProcessor processor = Fixtures.processor(id, id);
        processor.setType(type);
        processor.setRunDurationMillis(runDurationMillis);
        return processor;
    }

    private void writeUnpackedNar(final String name, final String manifestXml) throws IOException {
        Path docs = Files.createDirectories(
                manifestDirectory.resolve(name + ".nar-unpacked/META-INF/docs"));
        Files.writeString(docs.resolve("extension-manifest.xml"), manifestXml);
    }

    private void writeNarArchive(final String narName, final String manifestXml) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(
                Files.newOutputStream(manifestDirectory.resolve(narName)))) {
            zos.putNextEntry(new ZipEntry("META-INF/docs/extension-manifest.xml"));
            zos.write(manifestXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private static String manifest(final String... extensionsXml) {
        return "<extensionManifest><extensions>" + String.join("", extensionsXml)
                + "</extensions></extensionManifest>";
    }

    private static String extension(final String name, final String type, final String supportsBatching) {
        StringBuilder xml = new StringBuilder("<extension><name>").append(name).append("</name>");
        if (type != null) {
            xml.append("<type>").append(type).append("</type>");
        }
        if (supportsBatching != null) {
            xml.append("<supportsBatching>").append(supportsBatching).append("</supportsBatching>");
        }
        return xml.append("</extension>").toString();
    }
}

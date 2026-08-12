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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.tools.kb.model.AdditionalDocumentationState;
import org.qubership.nifi.tools.kb.model.ComponentIdentity;
import org.qubership.nifi.tools.kb.model.ComponentRecord;
import org.qubership.nifi.tools.kb.model.GuideDocument;
import org.qubership.nifi.tools.kb.model.GuideMode;
import org.qubership.nifi.tools.kb.model.GuideType;
import org.qubership.nifi.tools.kb.model.GuidesResult;
import org.qubership.nifi.tools.kb.model.KnowledgeBase;
import org.qubership.nifi.tools.kb.model.KnowledgeBaseFormat;
import org.qubership.nifi.tools.kb.model.KnowledgeBaseProvenance;
import org.qubership.nifi.tools.kb.render.JsonOutput;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Fails when a written Knowledge Base no longer satisfies the validator that guards the output
 * contract.
 *
 * <p>The writer and the validator have to agree in both guide modes, and {@code component.json}
 * must carry exactly the three documented top-level fields: a consumer reads them by name, so an
 * added field is a silent contract change rather than a harmless extra. If this goes red because a
 * field was added deliberately, update the output contract in the module README and
 * {@link KnowledgeBaseValidator} together, not just this assertion.
 */
class KnowledgeBaseWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path temp;

    private ComponentRecord processor(final boolean withDetails) {
        final ObjectNode documented = MAPPER.createObjectNode();
        documented.put("type", "org.apache.nifi.GenerateFlowFile");
        documented.putObject("bundle").put("group", "org.apache.nifi")
                .put("artifact", "nifi-standard-nar").put("version", "2.5.0");
        documented.put("description", "Generates FlowFiles");
        documented.putArray("tags").add("test").add("generate");

        final ObjectNode definition = MAPPER.createObjectNode();
        definition.put("type", "org.apache.nifi.GenerateFlowFile");
        definition.put("additionalDetails", withDetails);
        definition.putObject("propertyDescriptors").putObject("Batch Size")
                .put("name", "Batch Size").put("required", true).put("sensitive", false)
                .put("expressionLanguageScope", "NONE").put("defaultValue", "1");

        final ComponentIdentity identity = new ComponentIdentity(NiFiComponentKind.PROCESSOR,
                "org.apache.nifi", "nifi-standard-nar", "2.5.0", "org.apache.nifi.GenerateFlowFile");
        final AdditionalDocumentationState state = withDetails
                ? AdditionalDocumentationState.advertisedAvailable()
                : AdditionalDocumentationState.notAdvertised();
        return new ComponentRecord(identity, documented, definition, state,
                withDetails ? "# GenerateFlowFile\nVerbatim details.\n" : null);
    }

    @Test
    void writesAndValidatesSkipModeKnowledgeBase() {
        final GuidesResult guides = new GuidesResult(GuideMode.SKIP, List.of());
        final KnowledgeBase kb = knowledgeBase(guides);

        new KnowledgeBaseWriter(new JsonOutput(MAPPER)).writeTo(temp, kb);
        assertThatCode(() -> new KnowledgeBaseValidator().validate(temp)).doesNotThrowAnyException();

        assertThat(Files.exists(temp.resolve(KnowledgeBaseFormat.MANIFEST_FILE))).isTrue();
        assertThat(Files.exists(temp.resolve(KnowledgeBaseFormat.GUIDES_DIRECTORY))).isFalse();
        assertThat(Files.exists(temp.resolve("README.md"))).isFalse();
    }

    @Test
    void componentJsonHasExactlyThreeTopLevelObjects() throws Exception {
        final KnowledgeBase kb = knowledgeBase(new GuidesResult(GuideMode.SKIP, List.of()));
        new KnowledgeBaseWriter(new JsonOutput(MAPPER)).writeTo(temp, kb);

        final Path componentJson;
        try (var stream = Files.walk(temp)) {
            componentJson = stream.filter(p -> p.getFileName().toString()
                            .equals(KnowledgeBaseFormat.COMPONENT_JSON_FILE))
                    .findFirst().orElseThrow();
        }
        final JsonNode componentNode = MAPPER.readTree(Files.readAllBytes(componentJson));
        assertThat(componentNode.fieldNames()).toIterable()
                .containsExactlyInAnyOrder(KnowledgeBaseFormat.DOCUMENTED_TYPE_FIELD,
                        KnowledgeBaseFormat.DEFINITION_FIELD, KnowledgeBaseFormat.ADDITIONAL_DOCUMENTATION_FIELD);
        assertThat(componentNode.path(KnowledgeBaseFormat.ADDITIONAL_DOCUMENTATION_FIELD)
                .path(KnowledgeBaseFormat.AVAILABLE_FIELD).asBoolean()).isTrue();
        assertThat(Files.exists(componentJson.resolveSibling(KnowledgeBaseFormat.ADDITIONAL_DETAILS_FILE))).isTrue();
    }

    @Test
    void writesAndValidatesRequiredGuideMode() {
        final GuidesResult guides = new GuidesResult(GuideMode.REQUIRED, List.of(
                new GuideDocument(GuideType.EXPRESSION_LANGUAGE, "# EL\n", "https://nifi.example.com/x",
                        "text/html", List.of()),
                new GuideDocument(GuideType.RECORD_PATH, "# RP\n", "https://nifi.example.com/y",
                        "text/html", List.of()),
                new GuideDocument(GuideType.DEVELOPER, "# Dev\n", "https://nifi.example.com/z",
                        "text/html", List.of("NiFi Components"))));
        final KnowledgeBase kb = knowledgeBase(guides);

        new KnowledgeBaseWriter(new JsonOutput(MAPPER)).writeTo(temp, kb);
        assertThatCode(() -> new KnowledgeBaseValidator().validate(temp)).doesNotThrowAnyException();
        final Path guidesDirectory = temp.resolve(KnowledgeBaseFormat.GUIDES_DIRECTORY);
        assertThat(Files.exists(guidesDirectory.resolve(KnowledgeBaseFormat.INDEX_JSON_FILE))).isTrue();
        assertThat(Files.exists(guidesDirectory.resolve("developer-guide.md"))).isTrue();
    }

    private KnowledgeBase knowledgeBase(final GuidesResult guides) {
        final KnowledgeBaseProvenance provenance = new KnowledgeBaseProvenance(
                "qubership-nifi-kb-builder-tool", "2.6.4", Instant.parse("2026-07-18T00:00:00Z"),
                "2.5.0", "2.5.0", "https://nifi.example.com");
        return new KnowledgeBase(provenance, List.of(processor(true)), guides);
    }
}

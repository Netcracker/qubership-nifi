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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.tools.kb.model.AdditionalDocumentationState;
import org.qubership.nifi.tools.kb.model.ComponentIdentity;
import org.qubership.nifi.tools.kb.model.ComponentRecord;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails when component indexes lose metadata, canonical grouping, or navigable paths.
 *
 * <p>The JSON index must retain searchable source metadata and derived availability state. The
 * Markdown index must group every component by kind and bundle while linking its component page.
 * Update these expectations only with an intentional index-contract change.</p>
 */
class IndexRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rendersSourceMetadataVariantsToJson() throws Exception {
        final ComponentRecord first = componentRecord(NiFiComponentKind.PROCESSOR, "First", MAPPER.readTree("""
                {
                  "tags": ["one", "two"],
                  "deprecated": true,
                  "controllerServiceApis": [{"type": "org.example.Service"}, "org.example.OtherService"]
                }
                """), true);
        final ComponentRecord second = componentRecord(NiFiComponentKind.PROCESSOR, "Second", MAPPER.readTree("""
                {"deprecationReason": "Use a replacement"}
                """), false);
        final ComponentRecord third = componentRecord(NiFiComponentKind.REPORTING_TASK, "Third",
                MAPPER.readTree("""
                {"tags": "not-an-array", "controllerServiceApis": "not-an-array"}
                """), false);
        final IndexRenderer renderer = new IndexRenderer(new JsonOutput(MAPPER));

        final JsonNode index = MAPPER.readTree(renderer.renderJson(List.of(first, second, third)));

        assertThat(index.get(0).path("tags").toString()).isEqualTo("[\"one\",\"two\"]");
        assertThat(index.get(0).path("deprecated").asBoolean()).isTrue();
        assertThat(index.get(0).path("controllerServiceApis").toString())
                .isEqualTo("[\"org.example.Service\",\"org.example.OtherService\"]");
        assertThat(index.get(0).path("additionalDetailsAvailable").asBoolean()).isTrue();
        assertThat(index.get(1).path("deprecated").asBoolean()).isTrue();
        assertThat(index.get(2).path("tags").isEmpty()).isTrue();
        assertThat(index.get(2).path("controllerServiceApis").isEmpty()).isTrue();
    }

    @Test
    void groupsMarkdownByKindAndBundle() throws Exception {
        final ComponentRecord first = componentRecord(
                NiFiComponentKind.PROCESSOR, "First", MAPPER.readTree("{}"), false);
        final ComponentRecord second = componentRecord(
                NiFiComponentKind.PROCESSOR, "Second", MAPPER.readTree("{}"), false);
        final ComponentRecord service = componentRecord(
                NiFiComponentKind.CONTROLLER_SERVICE, "Service", MAPPER.readTree("{}"), false);
        final ComponentRecord task = componentRecord(
                NiFiComponentKind.REPORTING_TASK, "Task", MAPPER.readTree("{}"), false);

        final String markdown = new IndexRenderer(new JsonOutput(MAPPER))
                .renderMarkdown(List.of(first, second, service, task));

        assertThat(markdown)
                .contains("## Processors")
                .contains("## Controller Services")
                .contains("## Reporting Tasks")
                .contains("[First]")
                .contains("[Second]")
                .contains("[Service]")
                .contains("[Task]");
        assertThat(markdown.split("### `org\\.example:example-nar:1\\.0`", -1)).hasSize(4);
    }

    @Test
    void rendersEmptyMarkdownIndex() {
        final String markdown = new IndexRenderer(new JsonOutput(MAPPER)).renderMarkdown(List.of());

        assertThat(markdown).isEqualTo("# Component index\n\n");
    }

    private static ComponentRecord componentRecord(final NiFiComponentKind kind, final String simpleName,
                                                   final JsonNode documented, final boolean withDetails)
            throws Exception {
        final ComponentIdentity identity = new ComponentIdentity(kind, "org.example", "example-nar", "1.0",
                "org.example." + simpleName);
        final AdditionalDocumentationState state = withDetails
                ? AdditionalDocumentationState.advertisedAvailable()
                : AdditionalDocumentationState.notAdvertised();
        return new ComponentRecord(identity, documented, MAPPER.readTree("{}"), state,
                withDetails ? "details" : null);
    }
}

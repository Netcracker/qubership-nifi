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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails when {@code component.json} loses source data or changes its published shape.
 *
 * <p>The file is the lossless authority behind every other rendered artifact, so it must carry the
 * source trees verbatim, expose exactly the three documented top-level fields, and state the
 * additional-documentation outcome. Update these expectations only with an intentional change to the
 * Knowledge Base output contract.</p>
 */
class ComponentJsonRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ComponentIdentity IDENTITY = new ComponentIdentity(NiFiComponentKind.PROCESSOR,
            "org.example", "example-nar", "1.0", "org.example.TestProcessor");

    @Test
    void writesBothSourceTreesVerbatim() throws Exception {
        final JsonNode documented = MAPPER.readTree("""
                {"type": "org.example.TestProcessor", "tags": ["one"], "unknownFutureField": 7}
                """);
        final JsonNode definition = MAPPER.readTree("""
                {"description": "Does something", "propertyDescriptors": {"Prop": {"name": "Prop"}}}
                """);

        final JsonNode rendered = render(new ComponentRecord(IDENTITY, documented, definition,
                AdditionalDocumentationState.notAdvertised(), null));

        assertThat(rendered.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("documentedType", "definition", "additionalDocumentation");
        assertThat(rendered.get("documentedType")).isEqualTo(documented);
        assertThat(rendered.get("definition")).isEqualTo(definition);
    }

    @Test
    void reportsTheAdditionalDocumentationOutcome() throws Exception {
        final JsonNode notAdvertised = additionalDocumentation(AdditionalDocumentationState.notAdvertised());
        assertThat(notAdvertised.path("advertised").asBoolean()).isFalse();
        assertThat(notAdvertised.path("requested").asBoolean()).isFalse();
        assertThat(notAdvertised.path("available").asBoolean()).isFalse();
        assertThat(notAdvertised.has("path")).isFalse();

        final JsonNode unavailable = additionalDocumentation(AdditionalDocumentationState.advertisedUnavailable());
        assertThat(unavailable.path("advertised").asBoolean()).isTrue();
        assertThat(unavailable.path("requested").asBoolean()).isTrue();
        assertThat(unavailable.path("available").asBoolean()).isFalse();
        assertThat(unavailable.has("path")).isFalse();

        final JsonNode available = additionalDocumentation(AdditionalDocumentationState.advertisedAvailable());
        assertThat(available.path("advertised").asBoolean()).isTrue();
        assertThat(available.path("requested").asBoolean()).isTrue();
        assertThat(available.path("available").asBoolean()).isTrue();
        assertThat(available.path("path").asText()).isEqualTo("additionalDetails.md");
    }

    private static JsonNode additionalDocumentation(final AdditionalDocumentationState state) throws Exception {
        final JsonNode tree = MAPPER.readTree("{}");
        return render(new ComponentRecord(IDENTITY, tree, tree, state, null))
                .path("additionalDocumentation");
    }

    private static JsonNode render(final ComponentRecord componentRecord) throws Exception {
        return MAPPER.readTree(new ComponentJsonRenderer(new JsonOutput(MAPPER)).render(componentRecord));
    }
}

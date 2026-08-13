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
 * Fails when component Markdown loses required content or exposes unsupported optional content.
 *
 * <p>The rendered document must always identify the component and link its lossless definition.
 * Optional sections must follow compatible source data, definition metadata must take precedence
 * over documented metadata, table content must remain escaped, and additional details must be
 * linked only when available. Update these cases with any intentional {@code component.md}
 * contract change.</p>
 */
class ComponentMarkdownRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ComponentIdentity IDENTITY = new ComponentIdentity(
            NiFiComponentKind.PROCESSOR, "org.example", "example-nar", "1.0", "org.example.TestProcessor");

    @Test
    void rendersAllAvailableSections() throws Exception {
        final JsonNode definition = MAPPER.readTree("""
                {
                  "description": "Processes  multiple\\nlines",
                  "tags": ["first", "second tag"],
                  "deprecationReason": "Use NewProcessor",
                  "restricted": true,
                  "inputRequirement": "INPUT_REQUIRED",
                  "supportedRelationships": [
                    {"name": "success", "description": "Completed | successfully"}
                  ],
                  "propertyDescriptors": {
                    "Batch Size": {
                      "name": "batch-size",
                      "displayName": "Batch | Size",
                      "required": true,
                      "sensitive": false,
                      "expressionLanguageScope": "FLOWFILE_ATTRIBUTES",
                      "defaultValue": "10",
                      "allowableValues": [
                        {"allowableValue": {"value": "one"}},
                        {"displayName": "Two"}
                      ]
                    }
                  },
                  "readsAttributes": [{"name": "input.name", "description": "Input name"}],
                  "writesAttributes": [{"name": "output.name", "description": "Output name"}]
                }
                """);
        final JsonNode documented = MAPPER.readTree("""
                {
                  "controllerServiceApis": [
                    {"type": "org.example.Service"},
                    "org.example.OtherService"
                  ]
                }
                """);

        final String markdown = new ComponentMarkdownRenderer().render(componentRecord(
                documented, definition, AdditionalDocumentationState.advertisedAvailable()));

        assertThat(markdown)
                .contains("# TestProcessor")
                .contains("## Description\n\nProcesses multiple lines")
                .contains("Tags: `first`, `second tag`")
                .contains("- Deprecated: Use NewProcessor")
                .contains("- Restricted component: requires appropriate policies.")
                .contains("- Input requirement: `INPUT_REQUIRED`")
                .contains("| success | Completed \\| successfully |")
                .contains("| batch-size | Batch \\| Size | yes | no | FLOWFILE_ATTRIBUTES | 10 | one, Two |")
                .contains("- `org.example.Service`")
                .contains("- `org.example.OtherService`")
                .contains("## FlowFile attributes read")
                .contains("## FlowFile attributes written")
                .contains("[additionalDetails.md](additionalDetails.md)");
    }

    @Test
    void usesDocumentedFallbacksAndHandlesEmptyCollections() throws Exception {
        final JsonNode definition = MAPPER.readTree("""
                {
                  "supportedRelationships": [],
                  "propertyDescriptors": {
                    "Fallback Name": {
                      "name": "",
                      "required": false,
                      "sensitive": true,
                      "allowableValues": []
                    }
                  },
                  "providedApiImplementations": ["org.example.FallbackService"],
                  "readsAttributes": [],
                  "writesAttributes": "not-an-array"
                }
                """);
        final JsonNode documented = MAPPER.readTree("""
                {
                  "description": "Documented description",
                  "tags": ["fallback"],
                  "deprecationReason": "Documented deprecation",
                  "restricted": true
                }
                """);

        final String markdown = new ComponentMarkdownRenderer().render(componentRecord(
                documented, definition, AdditionalDocumentationState.advertisedUnavailable()));

        assertThat(markdown)
                .contains("Documented description")
                .contains("Tags: `fallback`")
                .contains("- Deprecated: Documented deprecation")
                .contains("| Fallback Name |  | no | yes |  |  |  |")
                .contains("- `org.example.FallbackService`")
                .doesNotContain("additionalDetails.md")
                .doesNotContain("FlowFile attributes");
    }

    @Test
    void omitsOptionalSectionsWhenSourceDataIsMissing() throws Exception {
        final String markdown = new ComponentMarkdownRenderer().render(componentRecord(
                MAPPER.readTree("{}"), MAPPER.readTree("{}"), AdditionalDocumentationState.notAdvertised()));

        assertThat(markdown)
                .contains("# TestProcessor")
                .contains("## Identity")
                .contains("## References")
                .doesNotContain("## Description")
                .doesNotContain("## Deprecation and restrictions")
                .doesNotContain("## Input and relationships")
                .doesNotContain("## Properties")
                .doesNotContain("## Controller service APIs")
                .doesNotContain("FlowFile attributes");
    }

    private static ComponentRecord componentRecord(final JsonNode documented, final JsonNode definition,
                                                   final AdditionalDocumentationState state) {
        return new ComponentRecord(IDENTITY, documented, definition, state, null);
    }
}

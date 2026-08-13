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

package org.qubership.nifi.tools.kb.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.tools.nifi.common.api.NiFiComponentKind;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fails when model helpers no longer expose complete component and guide mappings.
 *
 * <p>Every component kind must have its defined directory and display label, additional
 * documentation must expose a path only when available and must report itself as requested exactly
 * when it is advertised, a component record must reject a missing source tree, and guide lookup must
 * match the requested type. Update the model mappings and these expectations together when either
 * contract changes.</p>
 */
class ComponentModelTest {

    @Test
    void mapsEveryComponentKindToItsLayout() {
        assertThat(ComponentKindLayout.directoryName(NiFiComponentKind.PROCESSOR)).isEqualTo("processors");
        assertThat(ComponentKindLayout.directoryName(NiFiComponentKind.CONTROLLER_SERVICE))
                .isEqualTo("controller-services");
        assertThat(ComponentKindLayout.directoryName(NiFiComponentKind.REPORTING_TASK)).isEqualTo("reporting-tasks");
        assertThat(ComponentKindLayout.displayLabel(NiFiComponentKind.PROCESSOR)).isEqualTo("Processors");
        assertThat(ComponentKindLayout.displayLabel(NiFiComponentKind.CONTROLLER_SERVICE))
                .isEqualTo("Controller Services");
        assertThat(ComponentKindLayout.displayLabel(NiFiComponentKind.REPORTING_TASK)).isEqualTo("Reporting Tasks");
    }

    @Test
    void exposesAdditionalDocumentationPathsOnlyWhenAvailable() {
        assertThat(AdditionalDocumentationState.notAdvertised().path()).isEmpty();
        assertThat(AdditionalDocumentationState.advertisedUnavailable().path()).isEmpty();
        assertThat(AdditionalDocumentationState.advertisedAvailable().path())
                .contains(AdditionalDocumentationState.ADDITIONAL_DETAILS_FILE);
    }

    @Test
    void requestsAdditionalDocumentationExactlyWhenItIsAdvertised() {
        assertThat(AdditionalDocumentationState.notAdvertised().isRequested()).isFalse();
        assertThat(AdditionalDocumentationState.advertisedUnavailable().isRequested()).isTrue();
        assertThat(AdditionalDocumentationState.advertisedAvailable().isRequested()).isTrue();
    }

    @Test
    void rejectsAComponentRecordWithoutItsSourceTrees() throws Exception {
        final JsonNode tree = new ObjectMapper().readTree("{}");
        final ComponentIdentity identity = new ComponentIdentity(NiFiComponentKind.PROCESSOR,
                "org.example", "example-nar", "1.0", "org.example.TestProcessor");
        final AdditionalDocumentationState state = AdditionalDocumentationState.notAdvertised();

        // A missing tree must fail here: every renderer reads the same two trees, and one reaching
        // component.json as a literal null while the index shows an entry with no metadata would
        // publish a Knowledge Base that disagrees with itself.
        assertThatThrownBy(() -> new ComponentRecord(identity, null, tree, state, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ComponentRecord(identity, tree, null, state, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ComponentRecord(identity, tree, tree, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ComponentRecord(null, tree, tree, state, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void findsGuideDocumentsByType() {
        final GuideDocument developer = new GuideDocument(
                GuideType.DEVELOPER, "content", "source", "text/html", List.of("Components"));
        final GuidesResult guides = new GuidesResult(GuideMode.REQUIRED, List.of(developer));

        assertThat(guides.find(GuideType.DEVELOPER)).contains(developer);
        assertThat(guides.find(GuideType.RECORD_PATH)).isEmpty();
    }
}

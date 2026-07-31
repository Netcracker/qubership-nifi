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

package org.qubership.nifi.tools.kb.docs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeveloperGuideExtractorTest {

    private String fullGuide() {
        final StringBuilder sb = new StringBuilder();
        sb.append("# Developer's Guide\n\nIntro that must be excluded.\n\n");
        for (final String section : DeveloperGuideExtractor.REQUIRED_SECTIONS) {
            sb.append("## ").append(section).append("\n\nContent of ").append(section).append(".\n\n");
            sb.append("### ").append(section).append(" detail\n\nNested content.\n\n");
        }
        sb.append("## Unrelated Section\n\nShould be excluded.\n\n");
        return sb.toString();
    }

    @Test
    void extractsRequiredSectionsWithDescendants() {
        final DeveloperGuideExtractor.ExtractResult result = new DeveloperGuideExtractor().extract(fullGuide());
        assertThat(result.selectedHeadings()).containsExactlyElementsOf(DeveloperGuideExtractor.REQUIRED_SECTIONS);
        assertThat(result.markdown()).contains("## NiFi Components");
        assertThat(result.markdown()).contains("### FlowFile detail");
        assertThat(result.markdown()).doesNotContain("Intro that must be excluded");
        assertThat(result.markdown()).doesNotContain("Unrelated Section");
    }

    @Test
    void failsWhenRequiredSectionMissing() {
        final String guide = "# Developer's Guide\n\n## FlowFile\n\nOnly one section.\n";
        assertThatThrownBy(() -> new DeveloperGuideExtractor().extract(guide))
                .isInstanceOf(GuideException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void failsWhenRequiredSectionAmbiguous() {
        final String guide = fullGuide() + "\n## FlowFile\n\nDuplicate heading.\n";
        assertThatThrownBy(() -> new DeveloperGuideExtractor().extract(guide))
                .isInstanceOf(GuideException.class)
                .hasMessageContaining("ambiguous");
    }
}

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

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeveloperGuideExtractorTest {

    private static final List<String> NESTED_SECTIONS =
            List.of("FlowFile", "PropertyDescriptor", "PropertyValue", "Relationship");

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

    /**
     * Reproduces the published guide layout, where the four component sections are subsections of
     * NiFi Components rather than siblings of it.
     *
     * @return the guide Markdown
     */
    private String nestedGuide() {
        final StringBuilder sb = new StringBuilder();
        sb.append("# Developer's Guide\n\nIntro that must be excluded.\n\n");
        sb.append("## NiFi Components\n\nHow the pieces fit together.\n\n");
        for (final String nested : NESTED_SECTIONS) {
            sb.append("### ").append(nested).append("\n\nWhat this type is for.\n\n");
        }
        sb.append("## Common Processor Patterns\n\nPatterns worth copying.\n\n");
        sb.append("## Unrelated Section\n\nShould be excluded.\n\n");
        return sb.toString();
    }

    @Test
    void emitsARequiredSectionNestedInAnotherOnlyOnce() {
        final DeveloperGuideExtractor.ExtractResult result = new DeveloperGuideExtractor().extract(nestedGuide());

        assertThat(result.selectedHeadings()).containsExactlyElementsOf(DeveloperGuideExtractor.REQUIRED_SECTIONS);
        for (final String section : DeveloperGuideExtractor.REQUIRED_SECTIONS) {
            assertThat(headingCount(result.markdown(), section))
                    .as("headings named %s", section)
                    .isEqualTo(1);
        }
        assertThat(result.markdown()).contains("## NiFi Components").contains("### FlowFile");
        assertThat(result.markdown()).doesNotContain("Intro that must be excluded");
        assertThat(result.markdown()).doesNotContain("Unrelated Section");
    }

    private static long headingCount(final String markdown, final String title) {
        final Pattern heading = Pattern.compile("#{1,6} " + Pattern.quote(title));
        return markdown.lines().filter(line -> heading.matcher(line).matches()).count();
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

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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlToMarkdownTest {

    private String convert(final String html) {
        final Document doc = Jsoup.parse(html, "https://nifi.example.com/nifi-api/html/developer-guide.html");
        return new HtmlToMarkdown().convert(doc.body());
    }

    @Test
    void convertsHeadingsParagraphsAndLists() {
        final String md = convert("<h2>Title</h2><p>Some text.</p><ul><li>One</li><li>Two</li></ul>");
        assertThat(md).contains("## Title");
        assertThat(md).contains("Some text.");
        assertThat(md).contains("- One");
        assertThat(md).contains("- Two");
    }

    @Test
    void convertsTable() {
        final String md = convert("<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>");
        assertThat(md).contains("| A | B |");
        assertThat(md).contains("| --- | --- |");
        assertThat(md).contains("| 1 | 2 |");
    }

    @Test
    void omitsImagesAndFlattensLinksToText() {
        final String md = convert("<p><img src=\"logo.png\"/><a href=\"page.html\">Page111</a></p>");
        assertThat(md).doesNotContain("logo.png");
        assertThat(md).doesNotContain("[Page111]");
        assertThat(md).doesNotContain("(https://nifi.example.com/nifi-api/html/page.html)");
        assertThat(md).doesNotContain("https://");
        assertThat(md).doesNotContain("http://");
        assertThat(md).contains("Page111");
    }

    @Test
    void dropsUnsafeLinkSchemeToPlainText() {
        final String md = convert("<p><a href=\"javascript:alert(1)\">Click</a></p>");
        assertThat(md).contains("Click");
        assertThat(md).doesNotContain("javascript:");
    }

    @Test
    void rendersInlineCode() {
        final String md = convert("<p>Use <code>${now()}</code> here.</p>");
        assertThat(md).contains("`${now()}`");
    }
}

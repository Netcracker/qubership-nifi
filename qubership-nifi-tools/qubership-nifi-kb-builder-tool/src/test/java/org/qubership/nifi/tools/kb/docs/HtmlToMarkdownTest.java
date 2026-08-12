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
    void sizesTheSeparatorRowFromTheWidestRow() {
        final String md = convert("<table><thead><tr><th colspan=\"3\">Spanning header</th></tr></thead>"
                + "<tbody><tr><td>1</td><td>2</td><td>3</td></tr></tbody></table>");

        assertThat(md).contains("| --- | --- | --- |");
        assertThat(md).contains("| 1 | 2 | 3 |");
    }

    @Test
    void keepsNestedListItemBoundaries() {
        final String md = convert("<ul><li>Parent<ul><li>Child one</li><li>Child two</li></ul></li>"
                + "<li>Sibling</li></ul>");

        assertThat(md).contains("- Parent\n  - Child one\n  - Child two\n- Sibling");
    }

    @Test
    void keepsNestedListItemBoundariesThroughAWrapperDiv() {
        // Asciidoctor wraps a nested list in a div rather than making it a child of the item.
        final String md = convert("<ol><li><p>Step</p><div class=\"ulist\"><ul><li>Detail</li></ul></div></li></ol>");

        assertThat(md).contains("1. Step\n  - Detail");
    }

    @Test
    void doesNotEmitAnEmptyBulletForAnItemThatOnlyWrapsAList() {
        final String md = convert("<ul><li><ul><li>Only child</li></ul></li></ul>");

        assertThat(md).contains("- Only child");
        assertThat(md).doesNotContain("- \n");
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

    @Test
    void convertsRemainingBlockAndInlineElements() {
        final String md = convert("""
                Text before blocks
                <h1>One</h1><h3>Three</h3><h4>Four</h4><h5>Five</h5><h6>Six</h6>
                <ol><div>ignored</div><li>First</li><li>Second</li></ol>
                <pre>line 1\nline 2\n</pre>
                <blockquote>quoted<br>line</blockquote>
                <div><section><article><main><span>Nested</span></main></article></section></div>
                <custom><strong>Bold</strong> <em>emphasis</em> <b>B</b> <i>I</i></custom>
                """);

        assertThat(md)
                .contains("Text before blocks")
                .contains("# One")
                .contains("### Three")
                .contains("#### Four")
                .contains("##### Five")
                .contains("###### Six")
                .contains("1. First")
                .contains("2. Second")
                .contains("```\nline 1\nline 2\n```")
                .contains("> quoted\n> line")
                .contains("Nested")
                .contains("**Bold** *emphasis* **B** *I*");
    }

    @Test
    void omitsEmptyBlocksTablesRowsAndAnchors() {
        final String md = convert("""
                <p> </p><blockquote> </blockquote><img src="ignored.png">
                <table></table><table><tr></tr><tr><td>A|B</td><td>Value</td></tr></table>
                <p>before<a href="#self"></a>after<img src="ignored-inline.png"></p>
                """);

        assertThat(md)
                .contains("| A\\|B | Value |")
                .contains("beforeafter")
                .doesNotContain("ignored.png")
                .doesNotContain("ignored-inline.png");
    }
}

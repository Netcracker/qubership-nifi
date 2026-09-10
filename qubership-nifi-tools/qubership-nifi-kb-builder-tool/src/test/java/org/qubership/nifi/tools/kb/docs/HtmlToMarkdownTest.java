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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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

    @Test
    void keepsBlockStructureInsideAnUnrecognizedWrapper() {
        final String md = convert("""
                <details><h3>Advanced</h3><p>First paragraph.</p><p>Second paragraph.</p>
                <ul><li>One</li><li>Two</li></ul></details>
                """);

        assertThat(md)
                .contains("### Advanced")
                .contains("First paragraph.\n\nSecond paragraph.")
                .contains("- One")
                .contains("- Two");
    }

    @Test
    void keepsInlineMarkupInsideAnUnrecognizedInlineOnlyElement() {
        final String md = convert("<figcaption><strong>Term</strong> and its caption</figcaption>");

        assertThat(md).isEqualTo("**Term** and its caption\n");
    }

    @Test
    void rendersAThematicBreak() {
        assertThat(convert("<p>Above</p><hr><p>Below</p>")).contains("Above\n\n---\n\nBelow");
    }

    @Test
    void joinsTextAndInlineElementsOutsideAParagraphIntoOneParagraph() {
        final String md = convert("<h3>Wrapper</h3>The record has two fields: <code>original</code> and "
                + "<code>enrichment</code>. Each holds the <i>n</i>th record of its FlowFile.");

        assertThat(md).isEqualTo("### Wrapper\n\nThe record has two fields: `original` and `enrichment`. "
                + "Each holds the *n*th record of its FlowFile.\n");
    }

    @Test
    void keepsTheRestOfAParagraphThatThePreElementClosedAsOneParagraph() {
        // The parser closes the paragraph at the pre element, which leaves the note in the body.
        final String md = convert("<p>Example:<pre>KafkaClient {}</pre><b>NOTE:</b> The service name must match."
                + "</p>");

        assertThat(md).isEqualTo("Example:\n\n```\nKafkaClient {}\n```\n\n**NOTE:** The service name must match.\n");
    }

    @Test
    void rendersACodeElementWithLineBreaksAsACodeBlock() {
        final String md = convert("<code>\nid, name, balance<br />\n1, John, 48.23<br />\n2, Jane, 1245.89<br />\n"
                + "</code>");

        assertThat(md).isEqualTo("```\nid, name, balance\n1, John, 48.23\n2, Jane, 1245.89\n```\n");
    }

    @Test
    void rendersACodeElementWrappingAPreElementAsOneCodeBlock() {
        final String md = convert("<code>\n<pre>name, age\nJohn, 8</pre>\n</code>");

        assertThat(md).isEqualTo("```\nname, age\nJohn, 8\n```\n");
    }

    @Test
    void rendersATextareaAsACodeBlockThatKeepsItsIndentation() {
        final String md = convert("<textarea rows=\"10\">\n<mime-info>\n    <glob pattern=\"*.abcd\" />\n"
                + "</mime-info>\n</textarea>");

        assertThat(md).isEqualTo("```\n<mime-info>\n    <glob pattern=\"*.abcd\" />\n</mime-info>\n```\n");
    }

    @Test
    void rendersEachDefinitionTermAsABoldParagraphAboveItsDefinition() {
        final String md = convert("<dl><dt>Text-only message</dt><dd>A simple text message.</dd>"
                + "<dt>Text message with attachment</dt><dd>The text and one or more attachments.</dd></dl>");

        assertThat(md).isEqualTo("**Text-only message**\n\nA simple text message.\n\n"
                + "**Text message with attachment**\n\nThe text and one or more attachments.\n");
    }

    @Test
    void keepsTheParagraphsAndCodeBlocksOfADefinition() {
        final String md = convert("<dl><dt>Record Path:</dt><dd><p>Evaluated as a record path.</p>"
                + "<p>Example:</p><pre>%{/record/id}</pre></dd></dl>");

        assertThat(md).isEqualTo("**Record Path:**\n\nEvaluated as a record path.\n\nExample:\n\n"
                + "```\n%{/record/id}\n```\n");
    }

    @Test
    void doesNotBoldADefinitionTermTwice() {
        final String md = convert("<dl><dt><b>Constant:</b></dt><dd>Used as is.</dd></dl>");

        assertThat(md).isEqualTo("**Constant:**\n\nUsed as is.\n");
    }

    @Test
    void rendersListItemsOutsideAnyListAsBulletedItems() {
        // The parser closes the paragraph at the first li element and leaves each item in the body.
        final String md = convert("<p>Set the permission in the console.<li>Open the app.</li>"
                + "<li>Click Submit.</li></p><p>Then generate a token.</p>");

        assertThat(md).isEqualTo("Set the permission in the console.\n\n- Open the app.\n\n- Click Submit.\n\n"
                + "Then generate a token.\n");
    }

    @Test
    void joinsLinksEmphasisAndLineBreaksOutsideAParagraph() {
        final String md = convert("Create a <a href=\"https://docs.example.com/index.html\">Primary index</a> "
                + "first.<br>It is <em>required</em>.");

        assertThat(md).isEqualTo("Create a Primary index first.\nIt is *required*.\n");
    }

    @Test
    void rendersACodeElementWithLineBreaksInsideAParagraphAsACodeBlock() {
        final String md = convert("<p>Given the files:<code>\n/dir/app.log.1<br />\n/dir/app.log\n</code></p>");

        assertThat(md).isEqualTo("Given the files:\n\n```\n/dir/app.log.1\n/dir/app.log\n```\n");
    }

    @Test
    void convertsCrlfLineEndingsInACodeBlockToLf() {
        assertThat(convert("<pre>line 1\r\nline 2\r\n</pre>")).isEqualTo("```\nline 1\nline 2\n```\n");
    }

    @Test
    void rendersADefinitionListInsideAnInlineWrapper() {
        final String md = convert("<span><dl><dt>Term</dt><dd>The definition.</dd></dl></span>");

        assertThat(md).isEqualTo("**Term**\n\nThe definition.\n");
    }

    @Test
    void omitsAnEmptyDefinitionTerm() {
        assertThat(convert("<dl><dt> </dt><dd>The definition.</dd></dl>")).isEqualTo("The definition.\n");
    }

    @Test
    void namesThePathOfThePageInTheUnrecognizedTagWarning() {
        final String log = convertCapturingLog("<details><p>Advanced</p></details>",
                "https://nifi.example.com/nifi-docs/components/org.example/PutThing/additionalDetails.html");

        assertThat(log)
                .contains("Unrecognized HTML tag <details> in "
                        + "/nifi-docs/components/org.example/PutThing/additionalDetails.html;")
                .doesNotContain("nifi.example.com");
    }

    @Test
    void namesAPlaceholderInTheWarningForAPageWithNoBaseUri() {
        final String log = convertCapturingLog("<details><p>Advanced</p></details>", "");

        assertThat(log).contains("Unrecognized HTML tag <details> in a page with no base URI;");
    }

    @Test
    void logsATagOutsideTheInlineSetThatSitsInRunningText() {
        final String log = convertCapturingLog("The price was <strike>10</strike> 8.",
                "https://nifi.example.com/nifi-docs/components/org.example/PutThing/additionalDetails.html");

        assertThat(log).contains("Unrecognized HTML tag <strike> in ");
    }

    /**
     * Converts the body of the given page and returns what the converter logged. slf4j-simple writes
     * to {@code System.err}, which it resolves on each call.
     *
     * @param html    the page HTML
     * @param baseUri the base URI the page is parsed with, which the warning takes its path from
     * @return the text written to {@code System.err} during the conversion
     */
    private static String convertCapturingLog(final String html, final String baseUri) {
        final Document doc = Jsoup.parse(html, baseUri);
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final PrintStream original = System.err;
        try (PrintStream replacement = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setErr(replacement);
            new HtmlToMarkdown().convert(doc.body());
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}

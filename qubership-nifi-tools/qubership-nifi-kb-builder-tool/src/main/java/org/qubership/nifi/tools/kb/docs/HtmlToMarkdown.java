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

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Converts a sanitized HTML content element to Markdown, preserving headings, paragraphs, lists,
 * tables, block quotes, inline code, and code blocks in source order. Output uses LF line endings.
 *
 * <p>A nested list keeps its structure: each level is indented two spaces and keeps its own markers,
 * so the rules and examples the guides express as nested lists reach the reader with their item
 * boundaries intact.</p>
 *
 * <p>Text and inline elements that sit directly in a block container, outside any paragraph, are
 * joined into one paragraph, the way a browser lays them out. Hand-written NiFi 1.x component pages
 * often omit the {@code p} element, and the HTML parser closes a paragraph early at a {@code pre}
 * element or a list, which leaves the rest of its sentence in the container. A {@code textarea}, and
 * a {@code code} element that holds line breaks, become code blocks, whether they sit in a container
 * or directly in a paragraph. A definition list becomes a bold paragraph per term followed by its
 * definition, and an {@code li} outside any list becomes a bulleted item.</p>
 *
 * <p>Images are omitted and their sources are never requested. Anchors are flattened to their link
 * text, whatever the scheme, so no URL from the source document reaches the output. An anchor with
 * no text is dropped entirely.
 *
 * <p>A tag outside the recognized set is treated as a wrapper when it holds block content, so that
 * content keeps its own structure. Each such tag is logged once per converter, with the path of the
 * first page it appears in: a page that degrades because NiFi started emitting markup this
 * converter does not model must leave a trace.
 */
public final class HtmlToMarkdown {

    private static final Logger LOG = LoggerFactory.getLogger(HtmlToMarkdown.class);

    private static final int MAX_HEADING_LEVEL = 6;
    private static final String LIST_INDENT = "  ";

    /** The tags {@code convertBlock} renders as blocks, used to decide whether a wrapper holds any. */
    private static final String BLOCK_CONTENT_SELECTOR =
            "h1,h2,h3,h4,h5,h6,p,ul,ol,li,pre,textarea,blockquote,table,hr,div,section,article,main,dl,dt,dd";

    /**
     * The phrasing tags that stay in the paragraph the surrounding text forms when they sit directly
     * in a block container. Any other element there is rendered as a block, and one that
     * {@code convertBlock} does not model is logged as unrecognized.
     */
    private static final Set<String> INLINE_TAGS = Set.of("a", "code", "strong", "b", "em", "i", "br", "img",
            "span", "u", "s", "sub", "sup", "small", "tt", "kbd", "samp", "var", "abbr", "cite", "q", "mark",
            "font", "label");

    private static final String UNKNOWN_SOURCE = "a page with no base URI";

    private final Set<String> reportedUnknownTags = new HashSet<>();

    /**
     * Converts the given content element to Markdown.
     *
     * @param root the content root element
     * @return the Markdown text
     */
    public String convert(final Element root) {
        final StringBuilder out = new StringBuilder();
        convertBlockChildren(root, out);
        return out.toString().replaceAll("\n{3,}", "\n\n").strip() + "\n";
    }

    /**
     * Renders the children of a block container in source order. Consecutive text nodes and inline
     * elements form one paragraph, which ends at the next block element or at the end of the
     * container.
     *
     * @param parent the block container
     * @param out    the output being built
     */
    private void convertBlockChildren(final Element parent, final StringBuilder out) {
        final StringBuilder run = new StringBuilder();
        for (final Node node : parent.childNodes()) {
            if (node instanceof TextNode textNode) {
                run.append(textNode.text());
            } else if (node instanceof Element element) {
                if (isInlineRunContent(element)) {
                    appendInlineNode(element, run);
                } else {
                    appendParagraph(run, out);
                    convertBlock(element, out);
                }
            }
        }
        appendParagraph(run, out);
    }

    /**
     * Returns whether the element belongs to the paragraph the text around it forms. An inline
     * element is rendered as a block instead when it holds block content, which makes it a wrapper,
     * or when it is a {@code code} element holding a line break, which makes it a code block.
     *
     * @param element the element found in a block container
     * @return {@code true} if the element is inline content of the surrounding paragraph
     */
    private static boolean isInlineRunContent(final Element element) {
        return INLINE_TAGS.contains(element.tagName().toLowerCase(Locale.ROOT))
                && element.select(BLOCK_CONTENT_SELECTOR).isEmpty()
                && !isMultiLineCodeSample(element);
    }

    private static boolean isMultiLineCodeSample(final Element element) {
        final String tag = element.tagName().toLowerCase(Locale.ROOT);
        return "textarea".equals(tag) || ("code".equals(tag) && element.selectFirst("br") != null);
    }

    private static void appendParagraph(final StringBuilder run, final StringBuilder out) {
        final String text = run.toString().strip();
        if (!text.isEmpty()) {
            out.append(text).append("\n\n");
        }
        run.setLength(0);
    }

    private void convertBlock(final Element element, final StringBuilder out) {
        final String tag = element.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                final int level = Math.min(tag.charAt(1) - '0', MAX_HEADING_LEVEL);
                out.append("#".repeat(level)).append(' ').append(inline(element).strip()).append("\n\n");
            }
            case "p" -> {
                // A paragraph holding a multi-line code sample is rendered as a container, so the
                // sample becomes a code block rather than one line of inline code.
                if (element.children().stream().anyMatch(HtmlToMarkdown::isMultiLineCodeSample)) {
                    convertBlockChildren(element, out);
                } else {
                    appendInlineBlock(element, out, UnaryOperator.identity());
                }
            }
            case "ul" -> appendList(element, out, false);
            case "ol" -> appendList(element, out, true);
            case "pre", "textarea" -> appendCodeBlock(element.wholeText(), out);
            case "blockquote" -> appendInlineBlock(element, out,
                    text -> "> " + text.replace("\n", "\n> "));
            case "table" -> appendTable(element, out);
            case "hr" -> out.append("---\n\n");
            case "div", "section", "article", "main", "dl", "dd" -> convertBlockChildren(element, out);
            case "dt" -> appendDefinitionTerm(element, out);
            case "li" -> {
                // An item outside any list; the blank line after it keeps the next paragraph from
                // being read as a continuation of the item.
                appendListItem(element, out, "- ", 0);
                out.append('\n');
            }
            default -> {
                if ("code".equals(tag) && element.select(BLOCK_CONTENT_SELECTOR).isEmpty()) {
                    appendLineBrokenCode(element, out);
                } else if (INLINE_TAGS.contains(tag)) {
                    convertBlockChildren(element, out);
                } else {
                    convertUnknownBlock(element, out, tag);
                }
            }
        }
    }

    /**
     * Renders a {@code code} element whose lines are separated by {@code br} elements as a code
     * block. Only the {@code br} elements break lines: the source text is whitespace-normalized,
     * because the newline that usually follows each {@code br} would otherwise double every line
     * break, and each line is stripped, so indentation is not kept.
     *
     * @param code the code element
     * @param out  the output being built
     */
    private void appendLineBrokenCode(final Element code, final StringBuilder out) {
        appendCodeBlock(inline(code).lines().map(String::strip).collect(Collectors.joining("\n")), out);
    }

    /**
     * Appends a fenced code block with LF line endings. Blank lines before the first line of code
     * and whitespace after the last one are dropped, and each remaining line keeps its indentation.
     *
     * @param text the code text
     * @param out  the output being built
     */
    private static void appendCodeBlock(final String text, final StringBuilder out) {
        final String code = text.lines().dropWhile(String::isBlank)
                .collect(Collectors.joining("\n")).stripTrailing();
        out.append("```\n").append(code).append("\n```\n\n");
    }

    /**
     * Renders a definition term as a bold paragraph. The term's plain text is used, so a term that is
     * already bold in the source is not wrapped in a second pair of markers.
     *
     * @param term the {@code dt} element
     * @param out  the output being built
     */
    private static void appendDefinitionTerm(final Element term, final StringBuilder out) {
        final String text = term.text().strip();
        if (!text.isEmpty()) {
            out.append("**").append(text).append("**\n\n");
        }
    }

    /**
     * Renders a tag this converter does not model. An element holding block content is treated as a
     * wrapper so that content keeps its own structure: flattening it to inline text would merge its
     * paragraphs into one run-on line, strip the markers off its headings, and drop any list inside
     * it outright, since the inline pass deliberately skips lists. An element holding only inline
     * content is still flattened, which keeps its emphasis and code spans.
     *
     * @param element the unrecognized element
     * @param out     the output being built
     * @param tag     the lower-case tag name
     */
    private void convertUnknownBlock(final Element element, final StringBuilder out, final String tag) {
        if (reportedUnknownTags.add(tag)) {
            LOG.warn("Unrecognized HTML tag <{}> in {}; rendering its content generically", tag,
                    sourcePath(element));
        }
        if (element.select(BLOCK_CONTENT_SELECTOR).isEmpty()) {
            appendInlineBlock(element, out, UnaryOperator.identity());
        } else {
            convertBlockChildren(element, out);
        }
    }

    /**
     * Returns the path of the page the element was parsed from, taken from its base URI. Only the
     * path is returned, so the host and any user info stay out of the log.
     *
     * @param element the element
     * @return the raw path of the page, or a placeholder if the element has no usable base URI
     */
    private static String sourcePath(final Element element) {
        try {
            final String path = new URI(element.baseUri()).getRawPath();
            return path == null || path.isEmpty() ? UNKNOWN_SOURCE : path;
        } catch (URISyntaxException e) {
            return UNKNOWN_SOURCE;
        }
    }

    private void appendInlineBlock(final Element element, final StringBuilder out,
                                   final UnaryOperator<String> formatter) {
        final String text = inline(element).strip();
        if (!text.isEmpty()) {
            out.append(formatter.apply(text)).append("\n\n");
        }
    }

    private void appendList(final Element list, final StringBuilder out, final boolean ordered) {
        appendList(list, out, ordered, 0);
    }

    private void appendList(final Element list, final StringBuilder out, final boolean ordered,
                            final int depth) {
        int index = 1;
        for (final Element item : list.children()) {
            if (!"li".equalsIgnoreCase(item.tagName())) {
                continue;
            }
            appendListItem(item, out, ordered ? (index + ". ") : "- ", depth);
            index++;
        }
        if (depth == 0) {
            out.append('\n');
        }
    }

    private void appendListItem(final Element item, final StringBuilder out, final String marker,
                                final int depth) {
        final String text = inline(item).strip();
        if (text.isEmpty()) {
            // An item with no text of its own only wraps a nested list; rendering an empty bullet
            // above it would add a level of nesting the source document does not have.
            appendNestedLists(item, out, depth);
        } else {
            out.append(LIST_INDENT.repeat(depth)).append(marker).append(text).append('\n');
            appendNestedLists(item, out, depth + 1);
        }
    }

    /**
     * Renders the lists nested inside a list item, indented one level deeper. Nested lists are found
     * through any wrapper element, because Asciidoctor wraps a nested list in a {@code div} rather
     * than making it a direct child of the item.
     *
     * @param parent the element to search for nested lists
     * @param out    the output being built
     * @param depth  the indentation depth for the nested items
     */
    private void appendNestedLists(final Element parent, final StringBuilder out, final int depth) {
        for (final Element child : parent.children()) {
            final String tag = child.tagName().toLowerCase(Locale.ROOT);
            if ("ul".equals(tag) || "ol".equals(tag)) {
                appendList(child, out, "ol".equals(tag), depth);
            } else {
                appendNestedLists(child, out, depth);
            }
        }
    }

    private void appendTable(final Element table, final StringBuilder out) {
        final Elements rows = table.select("tr");
        if (rows.isEmpty()) {
            return;
        }
        // The separator row fixes the column count for the whole table, so it is sized from the
        // widest row rather than the first one: a header cell spanning several body columns is
        // common in the guides, and a separator narrower than the body stops Markdown from
        // rendering the block as a table at all.
        final int width = widestRow(rows);
        if (width == 0) {
            return;
        }
        boolean headerWritten = false;
        for (final Element row : rows) {
            final var cells = row.select("th, td");
            if (cells.isEmpty()) {
                continue;
            }
            out.append("| ");
            for (final Element cell : cells) {
                out.append(inline(cell).replace("\n", " ").replace("|", "\\|").strip()).append(" | ");
            }
            out.append(" | ".repeat(width - cells.size()));
            out.append('\n');
            if (!headerWritten) {
                out.append("|");
                out.append(" --- |".repeat(width));
                out.append('\n');
                headerWritten = true;
            }
        }
        out.append('\n');
    }

    private int widestRow(final Elements rows) {
        int width = 0;
        for (final Element row : rows) {
            width = Math.max(width, row.select("th, td").size());
        }
        return width;
    }

    private String inline(final Element element) {
        final StringBuilder sb = new StringBuilder();
        for (final Node node : element.childNodes()) {
            appendInlineNode(node, sb);
        }
        return sb.toString();
    }

    private void appendInlineNode(final Node node, final StringBuilder sb) {
        if (node instanceof TextNode textNode) {
            sb.append(textNode.text());
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        final String tag = element.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "a" -> appendLink(element, sb);
            case "code" -> sb.append('`').append(element.text()).append('`');
            case "strong", "b" -> sb.append("**").append(inline(element)).append("**");
            case "em", "i" -> sb.append('*').append(inline(element)).append('*');
            case "br" -> sb.append('\n');
            // A list is block content: appendList renders it with markers and indentation, so
            // flattening it into the surrounding inline text here would lose its item boundaries.
            case "ul", "ol" -> { }
            case "img" -> { }
            default -> sb.append(inline(element));
        }
    }

    private void appendLink(final Element anchor, final StringBuilder sb) {
        final String text = inline(anchor).strip();
        if (text.isEmpty()) {
            // Drop empty anchors such as the self-links Asciidoctor emits before each heading;
            // they carry no readable content and would otherwise corrupt the surrounding text.
            return;
        }
        //append only link text:
        sb.append(text);
    }
}

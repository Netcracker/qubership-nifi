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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Converts a sanitized HTML content element to Markdown, preserving headings, paragraphs, lists,
 * tables, block quotes, inline code, and code blocks in source order. Output uses LF line endings.
 *
 * <p>A nested list keeps its structure: each level is indented two spaces and keeps its own markers,
 * so the rules and examples the guides express as nested lists reach the reader with their item
 * boundaries intact.</p>
 *
 * <p>Images are omitted and their sources are never requested. Anchors are flattened to their link
 * text, whatever the scheme, so no URL from the source document reaches the output. An anchor with
 * no text is dropped entirely.
 *
 * <p>A tag outside the recognized set is treated as a wrapper when it holds block content, so that
 * content keeps its own structure. Every such tag is reported once per instance: a guide that
 * degrades because NiFi started emitting markup this converter does not model must leave a trace.
 */
public final class HtmlToMarkdown {

    private static final Logger LOG = LoggerFactory.getLogger(HtmlToMarkdown.class);

    private static final int MAX_HEADING_LEVEL = 6;
    private static final String LIST_INDENT = "  ";

    /** The tags {@code convertBlock} renders as blocks, used to decide whether a wrapper holds any. */
    private static final String BLOCK_CONTENT_SELECTOR =
            "h1,h2,h3,h4,h5,h6,p,ul,ol,pre,blockquote,table,hr,div,section,article,main";

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

    private void convertBlockChildren(final Element parent, final StringBuilder out) {
        for (final Node node : parent.childNodes()) {
            if (node instanceof Element element) {
                convertBlock(element, out);
            } else if (node instanceof TextNode textNode && !textNode.isBlank()) {
                out.append(textNode.text().strip()).append("\n\n");
            }
        }
    }

    private void convertBlock(final Element element, final StringBuilder out) {
        final String tag = element.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                final int level = Math.min(tag.charAt(1) - '0', MAX_HEADING_LEVEL);
                out.append("#".repeat(level)).append(' ').append(inline(element).strip()).append("\n\n");
            }
            case "p" -> appendInlineBlock(element, out, UnaryOperator.identity());
            case "ul" -> appendList(element, out, false);
            case "ol" -> appendList(element, out, true);
            case "pre" -> out.append("```\n").append(element.wholeText().stripTrailing()).append("\n```\n\n");
            case "blockquote" -> appendInlineBlock(element, out,
                    text -> "> " + text.replace("\n", "\n> "));
            case "table" -> appendTable(element, out);
            case "hr" -> out.append("---\n\n");
            case "img" -> { }
            case "div", "section", "article", "main", "span" -> convertBlockChildren(element, out);
            default -> convertUnknownBlock(element, out, tag);
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
            LOG.warn("Unrecognized HTML tag <{}> in guide content; rendering its content generically", tag);
        }
        if (element.select(BLOCK_CONTENT_SELECTOR).isEmpty()) {
            appendInlineBlock(element, out, UnaryOperator.identity());
        } else {
            convertBlockChildren(element, out);
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
            final String marker = ordered ? (index + ". ") : "- ";
            final String text = inline(item).strip();
            if (text.isEmpty()) {
                // An item with no text of its own only wraps a nested list; rendering an empty bullet
                // above it would add a level of nesting the source document does not have.
                appendNestedLists(item, out, depth);
            } else {
                out.append(LIST_INDENT.repeat(depth)).append(marker).append(text).append('\n');
                appendNestedLists(item, out, depth + 1);
            }
            index++;
        }
        if (depth == 0) {
            out.append('\n');
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

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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the required Developer's Guide sections (and their descendant headings) from the guide's
 * converted Markdown. Selection uses normalized heading text. For each selected heading, content is
 * included until the next heading at the same or a higher level. A missing or ambiguous required
 * heading fails the build so that an incompatible documentation layout is visible instead of
 * silently incomplete.
 */
public final class DeveloperGuideExtractor {

    /** The required sections in output order. */
    public static final List<String> REQUIRED_SECTIONS = List.of(
            "NiFi Components", "FlowFile", "PropertyDescriptor", "PropertyValue", "Relationship",
            "Common Processor Patterns");

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*\\S)\\s*$");

    /**
     * Extracts the required sections from the given Markdown.
     *
     * @param markdown the full converted guide Markdown
     * @return the extracted sections and the list of selected heading titles
     */
    public ExtractResult extract(final String markdown) {
        final String[] lines = markdown.split("\n", -1);
        final List<Heading> headings = collectHeadings(lines);

        final StringBuilder out = new StringBuilder();
        final List<String> selected = new ArrayList<>();
        for (final String section : REQUIRED_SECTIONS) {
            final Heading heading = requireSingle(section, headings);
            selected.add(heading.title());
            appendSection(lines, headings, heading, out);
        }
        return new ExtractResult(out.toString().strip() + "\n", selected);
    }

    private List<Heading> collectHeadings(final String[] lines) {
        final List<Heading> headings = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            final Matcher matcher = HEADING.matcher(lines[i]);
            if (matcher.matches()) {
                headings.add(new Heading(matcher.group(1).length(), matcher.group(2).strip(), i));
            }
        }
        return headings;
    }

    private Heading requireSingle(final String section, final List<Heading> headings) {
        final String target = normalize(section);
        final List<Heading> matches = headings.stream()
                .filter(h -> normalize(h.title()).equals(target))
                .toList();
        if (matches.isEmpty()) {
            throw new GuideException("Required Developer's Guide section is missing: " + section);
        }
        if (matches.size() > 1) {
            throw new GuideException("Required Developer's Guide section is ambiguous (appears "
                    + matches.size() + " times): " + section);
        }
        return matches.get(0);
    }

    private void appendSection(final String[] lines, final List<Heading> headings, final Heading start,
                               final StringBuilder out) {
        int endLine = lines.length;
        for (final Heading heading : headings) {
            if (heading.line() > start.line() && heading.level() <= start.level()) {
                endLine = heading.line();
                break;
            }
        }
        for (int i = start.line(); i < endLine; i++) {
            out.append(lines[i]).append('\n');
        }
        out.append('\n');
    }

    private static String normalize(final String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private record Heading(int level, String title, int line) {
    }

    /**
     * The result of Developer's Guide extraction.
     *
     * @param markdown         the concatenated selected sections
     * @param selectedHeadings the selected heading titles in output order
     */
    public record ExtractResult(String markdown, List<String> selectedHeadings) {
    }
}

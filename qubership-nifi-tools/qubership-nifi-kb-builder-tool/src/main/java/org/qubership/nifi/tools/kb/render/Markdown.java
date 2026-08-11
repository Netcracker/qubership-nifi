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

/**
 * Escaping helpers for generated Markdown: table cells and inline text. Both collapse runs of
 * whitespace to a single space, so neither is suitable for text whose line structure matters.
 */
public final class Markdown {

    private Markdown() {
        // utility class
    }

    /**
     * Escapes text for use inside a Markdown table cell: pipes are escaped and newlines collapsed to
     * spaces so the cell stays on one row.
     *
     * @param text the raw text
     * @return the escaped cell text
     */
    public static String cell(final String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Collapses runs of whitespace in inline text to single spaces.
     *
     * @param text the raw text
     * @return the collapsed text
     */
    public static String inline(final String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }
}

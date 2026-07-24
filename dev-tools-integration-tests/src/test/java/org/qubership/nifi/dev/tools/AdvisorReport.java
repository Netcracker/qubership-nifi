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
package org.qubership.nifi.dev.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reader for the CSV report produced by {@code upgradeAdvisor.sh}.
 *
 * <p>The report is not RFC 4180: the advisor quotes the solution, version, processor and process
 * group columns but leaves the issue column bare, so an issue text containing the separator splits
 * into extra columns. {@link #columns(String)} therefore only produces trustworthy results for runs
 * that use the semicolon separator, which none of the issue texts contain. Tests that need the comma
 * separator assert on whole lines instead.
 */
final class AdvisorReport {

    /** Column index of the flow name. */
    static final int FLOW_NAME = 0;
    /** Column index of the issue severity. */
    static final int LEVEL = 1;
    /** Column index of the issue description. */
    static final int ISSUE = 2;
    /** Column index of the recommended solution. */
    static final int SOLUTION = 3;
    /** Column index of the Apache NiFi version required to apply the solution. */
    static final int REQUIRED_VERSION = 4;
    /** Column index of the affected component. */
    static final int PROCESSOR = 5;
    /** Column index of the process group holding the affected component. */
    static final int PROCESS_GROUP = 6;
    /** Number of columns a well-formed report row has. */
    static final int COLUMN_COUNT = 7;

    private static final String[] HEADER_COLUMNS = {
        "Flow name", "Level", "Issue", "Solution",
        "Required NiFi version for solution", "Processor", "Process Group",
    };

    private final char separator;
    private final List<String> lines;

    private AdvisorReport(final char reportSeparator, final List<String> reportLines) {
        this.separator = reportSeparator;
        this.lines = reportLines;
    }

    /**
     * Reads a report file. Line endings are normalized, so a report written by a native Windows jq
     * (which emits CRLF) compares equal to one written on Linux.
     *
     * @param reportFile      path to the report
     * @param reportSeparator separator the report was written with
     * @return the parsed report
     * @throws IOException if the report cannot be read
     */
    static AdvisorReport read(final Path reportFile, final char reportSeparator) throws IOException {
        return new AdvisorReport(reportSeparator, Files.readAllLines(reportFile, StandardCharsets.UTF_8));
    }

    /**
     * Returns the header line the advisor writes for the given separator.
     *
     * @param reportSeparator separator to build the header with
     * @return the expected header line
     */
    static String expectedHeader(final char reportSeparator) {
        return String.join(String.valueOf(reportSeparator), HEADER_COLUMNS);
    }

    /**
     * Returns the header line of this report.
     *
     * @return the first line of the report
     */
    String header() {
        return lines.get(0);
    }

    /**
     * Returns every line after the header.
     *
     * @return the report rows, in the order the advisor wrote them
     */
    List<String> dataLines() {
        return lines.subList(1, lines.size());
    }

    /**
     * Returns the rows whose flow name column matches the given export.
     *
     * @param flowName value expected in the first column
     * @return matching rows, possibly empty
     */
    List<String> dataLinesForFlow(final String flowName) {
        List<String> matching = new ArrayList<>();
        for (String line : dataLines()) {
            if (columns(line).get(FLOW_NAME).equals(flowName)) {
                matching.add(line);
            }
        }
        return matching;
    }

    /**
     * Returns the single row reported for the given export, failing when there is not exactly one.
     *
     * @param flowName value expected in the first column
     * @return the columns of the matching row
     */
    List<String> singleRowFor(final String flowName) {
        List<String> matching = dataLinesForFlow(flowName);
        assertEquals(1, matching.size(),
            () -> "Expected exactly one report row for " + flowName + " but got: " + matching);
        return columns(matching.get(0));
    }

    /**
     * Splits a report line into columns, treating double quotes as field delimiters and dropping
     * them. Quotes that the advisor embeds in an unquoted issue text are dropped as well, which is
     * harmless because those tests match on substrings.
     *
     * @param line line to split
     * @return the column values
     */
    List<String> columns(final String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char symbol = line.charAt(i);
            if (symbol == '"') {
                quoted = !quoted;
            } else if (symbol == separator && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(symbol);
            }
        }
        values.add(current.toString());
        return values;
    }
}

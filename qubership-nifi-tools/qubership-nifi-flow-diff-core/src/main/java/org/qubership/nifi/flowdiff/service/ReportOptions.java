package org.qubership.nifi.flowdiff.service;

import java.io.File;

/**
 * The report settings a front end collects from its own parameters and hands to {@link ReportEmitter}. The format is
 * kept as the raw string the user supplied so an unrecognized value is reported with the text they typed.
 *
 * @param format         the requested format name: {@code text}, {@code json}, or {@code md}
 * @param output         the report file, or {@code null} to write a text report to standard output
 * @param maxValueLength the value truncation budget for {@code text} and {@code md}; {@code 0} disables truncation
 * @param showTechnical  whether to list technical changes as well, marked {@code [tech]}
 */
public record ReportOptions(String format, File output, int maxValueLength, boolean showTechnical) {
}

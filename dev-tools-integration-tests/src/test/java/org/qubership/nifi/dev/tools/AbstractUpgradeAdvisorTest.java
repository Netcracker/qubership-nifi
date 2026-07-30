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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior of {@code dev/upgrade-advisor/upgradeAdvisor.sh}, shared by every execution backend.
 *
 * <p>Subclasses supply a way to run the advisor - directly through bash, or inside the Alpine-based
 * container built from {@code dev/upgrade-advisor-autotest/Dockerfile} - and inherit the whole suite,
 * so a regression that only shows up in one environment is caught.
 *
 * <p>Each test copies exactly one scenario directory from {@code src/test/resources/upgrade-advisor}
 * into a private exports directory. Sharing one directory across scenarios is not an option: the
 * reporting-task check greps every file under the exports path, so unrelated fixtures would
 * contaminate each other's reports.
 *
 * <p>Column-level assertions use the semicolon separator throughout, because the advisor leaves the
 * issue column unquoted and a separator inside an issue text would silently split the row. That the
 * texts stay free of the comma is itself asserted, by
 * {@link #keepsEveryCommaSeparatedRowAtSevenColumns(String)}.
 */
abstract class AbstractUpgradeAdvisorTest {

    /** Resource directory holding the scenario fixtures. */
    protected static final String RESOURCE_ROOT = "/upgrade-advisor/";
    /** Report file name the tests ask the advisor to write. */
    protected static final String REPORT_NAME = "report.csv";
    /** Report file name the advisor falls back to when the third argument is omitted. */
    protected static final String DEFAULT_REPORT_NAME = "upgradeAdvisorReport.csv";
    /** Separator used for the golden-file and header assertions. */
    protected static final char COMMA = ',';
    /** Separator used for column-level assertions; no issue or solution text contains it unquoted. */
    protected static final char SEMICOLON = ';';

    private static final String ISSUE_ALERT_HANDLER =
        "The AlertHandler Controller Service is not available in Apache NiFi 2.x.";
    private static final String ISSUE_BASE64 =
        "The Base64EncodeContent Processor is not available in Apache NiFi 2.x.";
    private static final String SOLUTION_BASE64 =
        "Update the flow to use EncodeContent Processor instead of Base64EncodeContent.";
    private static final String ISSUE_BIG_QUERY =
        "The PutBigQueryBatch Processor is not available in Apache NiFi 2.x.";
    private static final String ISSUE_SCRIPT_ENGINE =
        "The ExecuteScript processor has Script Engine = python not supported in Apache NiFi 2.x.";
    private static final String ISSUE_PROXY =
        "Proxy properties in InvokeHTTP processor with name - Invoke With Proxy "
            + "is not available in Apache NiFi 2.x.";
    private static final String ISSUE_VARIABLES = "Variables are not available in Apache NiFi 2.x.";
    private static final String ISSUE_EVENT_DRIVEN =
        "The processor has Scheduling strategy = Event driven that is not supported in Apache NiFi 2.x.";
    private static final String ISSUE_GANGLIA =
        "The StandardGangliaReporter Reporting Task is not available in Apache NiFi 2.x.";

    private Path testRoot;

    /**
     * Runs the advisor with an explicit exports directory.
     *
     * @param workDir    directory to run in; the report and the advisor's temporary files land here
     * @param exportsDir directory holding the NiFi exports to analyze
     * @param arguments  the {@code csvSeparator} and {@code reportFileName} arguments
     * @return the outcome of the run
     */
    protected abstract AdvisorRunResult runAdvisor(Path workDir, Path exportsDir, String... arguments);

    /**
     * Runs the advisor with no arguments at all, so it falls back to every default. The caller is
     * expected to have placed the exports in {@code workDir}, which the advisor scans as {@code "."}.
     *
     * @param workDir directory to run in, holding both the exports and the resulting report
     * @return the outcome of the run
     */
    protected abstract AdvisorRunResult runAdvisorWithoutArguments(Path workDir);

    @BeforeEach
    void createTestRoot() throws IOException {
        testRoot = Files.createTempDirectory("advisor-it-");
        relaxPermissions(testRoot);
    }

    @AfterEach
    void deleteTestRoot() throws IOException {
        if (testRoot == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(testRoot)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    // -------------------------------------------------------------------------
    // Report content
    // -------------------------------------------------------------------------

    @Test
    void reportsDeprecatedProcessorsAndControllerServices() throws Exception {
        AdvisorReport report = reportOf(run("deprecated-component", SEMICOLON), SEMICOLON);
        assertEquals(3, report.dataLines().size(), () -> "Unexpected rows: " + report.dataLines());

        List<String> rows = report.dataLines();
        List<String> alertHandler = report.columns(rows.get(0));
        assertEquals("deprecated-components.json", alertHandler.get(AdvisorReport.FLOW_NAME));
        assertEquals("Error", alertHandler.get(AdvisorReport.LEVEL));
        assertEquals(ISSUE_ALERT_HANDLER, alertHandler.get(AdvisorReport.ISSUE));
        assertEquals("Alert Handler (20000000-0000-0000-0000-000000000201)",
            alertHandler.get(AdvisorReport.PROCESSOR));
        assertEquals("Deprecated PG (20000000-0000-0000-0000-000000000001)",
            alertHandler.get(AdvisorReport.PROCESS_GROUP));

        List<String> base64 = report.columns(rows.get(1));
        assertEquals("Warning", base64.get(AdvisorReport.LEVEL));
        assertEquals(ISSUE_BASE64, base64.get(AdvisorReport.ISSUE));
        assertEquals(SOLUTION_BASE64, base64.get(AdvisorReport.SOLUTION));
        assertEquals("", base64.get(AdvisorReport.REQUIRED_VERSION),
            "Components without a known fix version leave the version column empty");

        List<String> bigQuery = report.columns(rows.get(2));
        assertEquals(ISSUE_BIG_QUERY, bigQuery.get(AdvisorReport.ISSUE));
        assertEquals("1.18.0", bigQuery.get(AdvisorReport.REQUIRED_VERSION));
    }

    @Test
    void reportsDeprecatedScriptEnginesOnly() throws Exception {
        AdvisorReport report = reportOf(run("script-engine", SEMICOLON), SEMICOLON);
        assertEquals(1, report.dataLines().size(), () -> "Unexpected rows: " + report.dataLines());

        List<String> row = report.singleRowFor("script-engine-python.json");
        assertEquals("Warning", row.get(AdvisorReport.LEVEL));
        assertEquals(ISSUE_SCRIPT_ENGINE, row.get(AdvisorReport.ISSUE));
        assertEquals("Update the flow to use Groovy Script Engine.", row.get(AdvisorReport.SOLUTION));
        assertEquals("Execute Python Script (30000000-0000-0000-0000-000000000101)",
            row.get(AdvisorReport.PROCESSOR));
        assertTrue(report.dataLinesForFlow("script-engine-groovy.json").isEmpty(),
            "A Groovy script engine is supported in 2.x and must not be reported");
    }

    @Test
    void reportsInvokeHttpProxyPropertiesOnly() throws Exception {
        AdvisorReport report = reportOf(run("invoke-http-proxy", SEMICOLON), SEMICOLON);
        assertEquals(1, report.dataLines().size(), () -> "Unexpected rows: " + report.dataLines());

        List<String> row = report.singleRowFor("invoke-http-with-proxy.json");
        assertEquals("Warning", row.get(AdvisorReport.LEVEL));
        assertEquals(ISSUE_PROXY, row.get(AdvisorReport.ISSUE));
        assertEquals("1.18.0", row.get(AdvisorReport.REQUIRED_VERSION));
        assertTrue(report.dataLinesForFlow("invoke-http-plain.json").isEmpty(),
            "An InvokeHTTP without proxy properties must not be reported");
    }

    @Test
    void reportsNonEmptyVariablesOnly() throws Exception {
        AdvisorReport report = reportOf(run("variables", SEMICOLON), SEMICOLON);
        assertEquals(1, report.dataLines().size(), () -> "Unexpected rows: " + report.dataLines());

        List<String> row = report.singleRowFor("variables-set.json");
        assertEquals("Warning", row.get(AdvisorReport.LEVEL));
        assertEquals(ISSUE_VARIABLES, row.get(AdvisorReport.ISSUE));
        assertEquals("", row.get(AdvisorReport.REQUIRED_VERSION));
        assertEquals("", row.get(AdvisorReport.PROCESSOR),
            "The variables check reports a process group, so the component column stays empty");
        assertEquals("Variables PG (50000000-0000-0000-0000-000000000001)",
            row.get(AdvisorReport.PROCESS_GROUP));
        assertTrue(report.dataLinesForFlow("variables-empty.json").isEmpty(),
            "An empty variables object must not be reported");
    }

    @Test
    void reportsS3ProcessorsWithoutCredentialsService() throws Exception {
        AdvisorReport report = reportOf(run("s3-credentials", SEMICOLON), SEMICOLON);
        assertEquals(1, report.dataLines().size(), () -> "Unexpected rows: " + report.dataLines());

        List<String> row = report.singleRowFor("s3-without-credentials-service.json");
        assertEquals("Warning", row.get(AdvisorReport.LEVEL));
        assertTrue(row.get(AdvisorReport.ISSUE).contains("PutS3Object"),
            () -> "Issue should name the processor type: " + row.get(AdvisorReport.ISSUE));
        assertTrue(row.get(AdvisorReport.ISSUE).contains("Access Key ID"),
            () -> "Issue should name the removed properties: " + row.get(AdvisorReport.ISSUE));
        assertEquals("Put To S3 (60000000-0000-0000-0000-000000000101)", row.get(AdvisorReport.PROCESSOR));
        assertTrue(report.dataLinesForFlow("s3-with-credentials-service.json").isEmpty(),
            "An S3 processor already using a credentials service must not be reported");
    }

    @Test
    void reportsEventDrivenSchedulingStrategyOnly() throws Exception {
        AdvisorReport report = reportOf(run("event-driven", SEMICOLON), SEMICOLON);
        assertEquals(1, report.dataLines().size(), () -> "Unexpected rows: " + report.dataLines());

        List<String> row = report.singleRowFor("event-driven-processor.json");
        assertEquals("Warning", row.get(AdvisorReport.LEVEL));
        assertEquals(ISSUE_EVENT_DRIVEN, row.get(AdvisorReport.ISSUE));
        assertTrue(row.get(AdvisorReport.SOLUTION).contains("Timer driven"),
            () -> "Solution should point at the timer-driven strategy: " + row.get(AdvisorReport.SOLUTION));
        assertEquals("Event Driven Update Attribute (70000000-0000-0000-0000-000000000101)",
            row.get(AdvisorReport.PROCESSOR));
        assertTrue(report.dataLinesForFlow("timer-driven-processor.json").isEmpty(),
            "A timer-driven processor must not be reported");
    }

    @Test
    void reportsDeprecatedReportingTask() throws Exception {
        AdvisorReport report = reportOf(run("reporting-task", SEMICOLON), SEMICOLON);
        assertEquals(1, report.dataLines().size(), () -> "Unexpected rows: " + report.dataLines());

        List<String> row = report.singleRowFor("ganglia-reporting-task.json");
        assertEquals("Warning", row.get(AdvisorReport.LEVEL));
        assertEquals(ISSUE_GANGLIA, row.get(AdvisorReport.ISSUE));
        assertEquals("", row.get(AdvisorReport.REQUIRED_VERSION));
        assertEquals("", row.get(AdvisorReport.PROCESSOR));
        assertEquals("", row.get(AdvisorReport.PROCESS_GROUP));
    }

    @Test
    void writesOnlyTheHeaderWhenNothingIsDeprecated() throws Exception {
        AdvisorRunResult result = run("clean", SEMICOLON);
        assertEquals(0, result.exitCode(), () -> "Advisor failed:\n" + result.output());

        AdvisorReport report = reportOf(result, SEMICOLON);
        assertTrue(report.dataLines().isEmpty(), () -> "Unexpected rows: " + report.dataLines());
        assertTrue(result.output().contains("Overall result: Success."),
            () -> "Missing success summary:\n" + result.output());
        assertTrue(result.output().contains("All flows are compatible with 2.x, no changes needed."),
            () -> "Missing success detail:\n" + result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"comma", "semicolon"})
    void matchesGoldenReportForTheCombinedScenario(final String separatorName) throws Exception {
        char separator = "comma".equals(separatorName) ? COMMA : SEMICOLON;
        AdvisorRunResult result = run("combined", separator);
        assertEquals(0, result.exitCode(), () -> "Advisor failed:\n" + result.output());

        List<String> actual = Files.readAllLines(
            result.workDir().resolve(REPORT_NAME), StandardCharsets.UTF_8);
        List<String> expected = Files.readAllLines(
            resource(RESOURCE_ROOT + "expected/combined-" + separatorName + ".csv"), StandardCharsets.UTF_8);
        assertIterableEquals(expected, actual, "Report does not match the golden file");
    }

    // -------------------------------------------------------------------------
    // Arguments and file handling
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"comma", "semicolon"})
    void writesTheHeaderWithTheRequestedSeparator(final String separatorName) throws Exception {
        char separator = "comma".equals(separatorName) ? COMMA : SEMICOLON;
        AdvisorReport report = reportOf(run("clean", separator), separator);
        assertEquals(AdvisorReport.expectedHeader(separator), report.header());
    }

    @Test
    void failsOnAnUnsupportedCsvSeparator() throws Exception {
        Path exports = copyScenario("clean");
        Path workDir = newWorkDir();
        AdvisorRunResult result = runAdvisor(workDir, exports, "pipe", REPORT_NAME);

        assertEquals(1, result.exitCode(), () -> "Expected a failure exit code:\n" + result.output());
        assertTrue(result.output().contains(
                "Error: the second argument - 'csvSeparator' is set to unsupported value 'pipe'."),
            () -> "Missing diagnostic:\n" + result.output());
        assertFalse(Files.exists(workDir.resolve(REPORT_NAME)),
            "No report must be written when the separator is rejected");
    }

    @Test
    void appliesDefaultsWhenArgumentsAreOmitted() throws Exception {
        Path workDir = newWorkDir();
        copyScenarioInto("event-driven", workDir);
        AdvisorRunResult result = runAdvisorWithoutArguments(workDir);

        assertEquals(0, result.exitCode(), () -> "Advisor failed:\n" + result.output());
        assertTrue(result.output().contains("The first argument - 'pathToExports' is not set."),
            () -> "Missing default notice for pathToExports:\n" + result.output());
        assertTrue(result.output().contains("The second argument - 'csvSeparator' is not set."),
            () -> "Missing default notice for csvSeparator:\n" + result.output());
        assertTrue(result.output().contains("The third argument - 'reportFileName' is not set."),
            () -> "Missing default notice for reportFileName:\n" + result.output());

        Path report = workDir.resolve(DEFAULT_REPORT_NAME);
        assertTrue(Files.exists(report), "The advisor must fall back to " + DEFAULT_REPORT_NAME);
        AdvisorReport parsed = AdvisorReport.read(report, COMMA);
        assertEquals(AdvisorReport.expectedHeader(COMMA), parsed.header());
        assertEquals("./event-driven-processor.json",
            parsed.columns(parsed.dataLines().get(0)).get(AdvisorReport.FLOW_NAME),
            "Scanning '.' keeps the leading './' in the flow name");
    }

    @Test
    void replacesAnExistingReportFile() throws Exception {
        Path exports = copyScenario("clean");
        Path workDir = newWorkDir();
        Path report = workDir.resolve(REPORT_NAME);
        Files.writeString(report, "stale row from an earlier run\n", StandardCharsets.UTF_8);

        AdvisorRunResult result = runAdvisor(workDir, exports, "semicolon", REPORT_NAME);
        assertEquals(0, result.exitCode(), () -> "Advisor failed:\n" + result.output());

        List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
        assertIterableEquals(List.of(AdvisorReport.expectedHeader(SEMICOLON)), lines,
            "The previous report must be replaced, not appended to");
    }

    @Test
    void removesItsTemporaryFilesFromTheWorkingDirectory() throws Exception {
        AdvisorRunResult result = run("combined", SEMICOLON);
        assertEquals(0, result.exitCode(), () -> "Advisor failed:\n" + result.output());

        assertFalse(Files.exists(result.workDir().resolve("deprecatedComponents.json")),
            "The deprecated component list must be cleaned up");
        assertFalse(Files.exists(result.workDir().resolve("summary_flow.txt")),
            "The per-flow summary must be cleaned up");
    }

    // -------------------------------------------------------------------------
    // Summary and exit code
    // -------------------------------------------------------------------------

    @Test
    void printsAPerFlowSummaryAndTheIncompatibleFlowCount() throws Exception {
        AdvisorRunResult result = run("combined", SEMICOLON);
        String output = result.output();

        assertTrue(output.contains("Overall result: Failed."), () -> "Missing overall result:\n" + output);
        assertTrue(output.contains("1 flows are incompatible with Apache NiFi 2.x"),
            () -> "Missing incompatible flow count:\n" + output);
        assertTrue(output.contains("- clean-flow.json - Success"),
            () -> "Missing per-flow success entry:\n" + output);
        assertTrue(output.contains("- mixed-issues.json - Failed"),
            () -> "Missing per-flow failure entry:\n" + output);
    }

    @Test
    void exitsWithZeroEvenWhenFlowsAreIncompatible() throws Exception {
        // Characterization: the advisor reports incompatibilities on stdout but never in its exit
        // code, so callers cannot gate on it. Change this test deliberately if that is ever fixed.
        AdvisorRunResult result = run("combined", SEMICOLON);
        assertTrue(result.output().contains("Overall result: Failed."),
            () -> "Scenario is expected to be incompatible:\n" + result.output());
        assertEquals(0, result.exitCode(), "The advisor currently exits 0 even on findings");
    }

    @ParameterizedTest
    @ValueSource(strings = {"deprecated-component", "script-engine", "invoke-http-proxy", "variables",
        "s3-credentials", "event-driven", "reporting-task", "combined"})
    void keepsEveryCommaSeparatedRowAtSevenColumns(final String scenario) throws Exception {
        // The advisor writes the issue column unquoted, so a comma anywhere in an issue text splits
        // the row and silently breaks every consumer of the report. Guards against reintroducing one.
        AdvisorReport report = reportOf(run(scenario, COMMA), COMMA);
        assertFalse(report.dataLines().isEmpty(), "Scenario '" + scenario + "' should report something");
        for (String line : report.dataLines()) {
            assertEquals(AdvisorReport.COLUMN_COUNT, report.columns(line).size(),
                () -> "Row does not have " + AdvisorReport.COLUMN_COUNT + " columns: " + line);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AdvisorRunResult run(final String scenario, final char separator) throws Exception {
        Path exports = copyScenario(scenario);
        Path workDir = newWorkDir();
        return runAdvisor(workDir, exports, separatorName(separator), REPORT_NAME);
    }

    private static AdvisorReport reportOf(final AdvisorRunResult result, final char separator)
            throws IOException {
        return AdvisorReport.read(result.workDir().resolve(REPORT_NAME), separator);
    }

    private static String separatorName(final char separator) {
        return separator == COMMA ? "comma" : "semicolon";
    }

    /**
     * Creates a private working directory for one advisor run.
     *
     * @return the created directory
     * @throws IOException if the directory cannot be created
     */
    protected Path newWorkDir() throws IOException {
        Path workDir = Files.createDirectory(testRoot.resolve("work"));
        relaxPermissions(workDir);
        return workDir;
    }

    /**
     * Copies one scenario's fixtures into a private exports directory.
     *
     * @param scenario name of a directory under {@code src/test/resources/upgrade-advisor}
     * @return the exports directory
     * @throws Exception if the fixtures cannot be copied
     */
    protected Path copyScenario(final String scenario) throws Exception {
        Path exports = Files.createDirectory(testRoot.resolve("export"));
        copyScenarioInto(scenario, exports);
        return exports;
    }

    /**
     * Copies one scenario's fixtures into an existing directory.
     *
     * @param scenario name of a directory under {@code src/test/resources/upgrade-advisor}
     * @param target   directory to copy the fixtures into
     * @throws Exception if the fixtures cannot be copied
     */
    protected void copyScenarioInto(final String scenario, final Path target) throws Exception {
        Path source = resource(RESOURCE_ROOT + scenario);
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName().toString()));
            }
        }
        relaxPermissions(target);
    }

    private static Path resource(final String name) throws Exception {
        return Paths.get(AbstractUpgradeAdvisorTest.class.getResource(name).toURI());
    }

    /**
     * Makes a directory and its contents world-readable and world-writable when the filesystem
     * supports POSIX permissions. The container backend runs as UID 1001, which will not match the
     * user that created the temporary directory on every machine.
     *
     * @param directory directory to relax
     * @throws IOException if the permissions cannot be changed
     */
    private static void relaxPermissions(final Path directory) throws IOException {
        if (!Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.toList()) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    Files.isDirectory(path) ? "rwxrwxrwx" : "rw-rw-rw-"));
            }
        }
    }
}

package org.qubership.nifi.flowdiff.cli;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FlowDiffCli}: the three subcommands reaching the core services with the options a user typed, and
 * the exit-code contract - {@code 0} for a run that completed however many changes it found, {@code 1} for an
 * execution failure, {@code 2} for a usage error.
 */
class FlowDiffCliTest {

    private static final String FLOW_ONE = """
            {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","properties":{"k":"1"}}]}}""";
    private static final String FLOW_TWO = """
            {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","properties":{"k":"2"}}]}}""";
    private static final String COMMITTED = """
            {"flowContents":{"identifier":"root-committed","name":"R","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"p1-c",
               "groupIdentifier":"root-committed","properties":{"k":"v"}}]}}""";
    private static final String WORKING = """
            {"flowContents":{"identifier":"root-working","name":"R","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","instanceIdentifier":"p1-w",
               "groupIdentifier":"root-working","properties":{"k":"v2"}}]}}""";

    @TempDir
    private Path dir;

    /**
     * What a run wrote, so a test can assert on the report, the diagnostics, and the exit code together.
     *
     * @param exitCode the exit code the run returned
     * @param out      everything written to standard output
     * @param err      everything written to standard error
     */
    private record Run(int exitCode, String out, String err) {
    }

    private void write(final String relative, final String content) throws Exception {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void commitAll() throws Exception {
        try (Git git = dir.resolve(".git").toFile().exists()
                ? Git.open(dir.toFile()) : Git.init().setDirectory(dir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("commit").setAuthor("t", "t@e").setSign(false).call();
        }
    }

    private Run run(final String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            int exitCode = FlowDiffCli.run(args);
            return new Run(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    void helpListsTheThreeSubcommands() {
        Run run = run("--help");
        assertEquals(0, run.exitCode(), run.err());
        assertTrue(run.out().contains("diff"), run.out());
        assertTrue(run.out().contains("git-diff"), run.out());
        assertTrue(run.out().contains("git-revert-technical"), run.out());
    }

    @Test
    void missingRequiredOptionIsAUsageError() throws Exception {
        write("b.json", FLOW_TWO);
        Run run = run("diff", "--basedir", dir.toString(), "--target", "b.json");
        assertEquals(2, run.exitCode());
        assertTrue(run.err().contains("--baseline"), run.err());
    }

    @Test
    void unknownFormatIsAnExecutionError() throws Exception {
        write("a.json", FLOW_ONE);
        write("b.json", FLOW_TWO);
        Run run = run("diff", "--basedir", dir.toString(),
                "--baseline", "a.json", "--target", "b.json", "--format", "yaml");
        assertEquals(1, run.exitCode());
        assertTrue(run.err().contains("Unknown format 'yaml'"), run.err());
    }

    @Test
    void jsonWithoutOutputNamesTheCommandLineOption() throws Exception {
        write("a.json", FLOW_ONE);
        write("b.json", FLOW_TWO);
        Run run = run("diff", "--basedir", dir.toString(),
                "--baseline", "a.json", "--target", "b.json", "--format", "json");
        assertEquals(1, run.exitCode());
        assertTrue(run.err().contains("requires --output <file>"), run.err());
        assertFalse(run.err().contains("-Doutput"), run.err());
    }

    @Test
    void diffWritesTheReportToStandardOutputByDefault() throws Exception {
        write("a.json", FLOW_ONE);
        write("b.json", FLOW_TWO);
        Run run = run("diff", "--basedir", dir.toString(), "--baseline", "a.json", "--target", "b.json");
        assertEquals(0, run.exitCode(), run.err());
        assertTrue(run.out().contains("properties/k: 1 -> 2"), run.out());
    }

    @Test
    void diffResolvesARelativeOutputAgainstBasedir() throws Exception {
        write("a.json", FLOW_ONE);
        write("b.json", FLOW_TWO);
        Run run = run("diff", "--basedir", dir.toString(), "--baseline", "a.json", "--target", "b.json",
                "--format", "json", "--output", "report.json");
        assertEquals(0, run.exitCode(), run.err());
        String report = Files.readString(dir.resolve("report.json"), StandardCharsets.UTF_8);
        assertTrue(report.contains("\"schemaVersion\""), report);
    }

    @Test
    void gitDiffReportsAgainstTheNamedRevision() throws Exception {
        write("flows/a.json", COMMITTED);
        commitAll();
        write("flows/a.json", WORKING);

        Run run = run("git-diff", "--basedir", dir.toString(), "--path", "flows", "--branch", "HEAD");

        assertEquals(0, run.exitCode(), run.err());
        assertTrue(run.out().contains("properties/k: v -> v2"), run.out());
        assertTrue(run.out().contains("technical: 3"), run.out());
    }

    @Test
    void gitDiffOnAnUnresolvableRevisionExitsOne() throws Exception {
        write("flows/a.json", COMMITTED);
        commitAll();
        Run run = run("git-diff", "--basedir", dir.toString(), "--path", "flows", "--branch", "no-such-branch");
        assertEquals(1, run.exitCode());
        assertTrue(run.err().contains("no-such-branch"), run.err());
    }

    @Test
    void gitRevertTechnicalPrintsTheBreakdownAndTotal() throws Exception {
        write("flows/a.json", COMMITTED);
        commitAll();
        write("flows/a.json", WORKING);

        Run run = run("git-revert-technical", "--basedir", dir.toString(), "--path", "flows/a.json");

        assertEquals(0, run.exitCode(), run.err());
        assertTrue(run.out().contains(
                "flows/a.json: 3 reverted (instanceIdentifier=1, rootIdentifier=1, groupIdentifier=1, "
                        + "endpointGroupId=0)"), run.out());
        assertTrue(run.out().contains("Total: 1 files rewritten, 3 technical changes reverted."), run.out());
    }

    @Test
    void gitRevertTechnicalRejectsAnAbsolutePath() throws Exception {
        write("flows/a.json", COMMITTED);
        commitAll();
        Run run = run("git-revert-technical", "--basedir", dir.toString(),
                "--path", dir.resolve("flows/a.json").toString());
        assertEquals(1, run.exitCode());
        assertTrue(run.err().contains("must be relative"), run.err());
    }
}

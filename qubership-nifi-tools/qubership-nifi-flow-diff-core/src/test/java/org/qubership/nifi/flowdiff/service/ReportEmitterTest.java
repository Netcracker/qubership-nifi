package org.qubership.nifi.flowdiff.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.nifi.flowdiff.report.ReportModel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ReportEmitter}: rejecting an unknown format, requiring an output file for the formats that need one
 * while quoting the caller's own option spelling, writing to a file, and falling back to standard output for text.
 */
class ReportEmitterTest {

    private static final String FLOW_ONE = """
            {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","properties":{"k":"1"}}]}}""";
    private static final String FLOW_TWO = """
            {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","properties":{"k":"2"}}]}}""";

    @TempDir
    private Path dir;

    private ReportModel model() throws Exception {
        Files.writeString(dir.resolve("a.json"), FLOW_ONE, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.json"), FLOW_TWO, StandardCharsets.UTF_8);
        return new FlowDiffService().diff(dir.toFile(), dir.resolve("a.json").toFile(),
                dir.resolve("b.json").toFile(), false);
    }

    private void emit(final ReportModel model, final String format, final File output) {
        new ReportEmitter(new ReportOptions(format, output, 200, false), "--output <file>").emit(model);
    }

    @Test
    void unknownFormatFails() throws Exception {
        ReportModel model = model();
        FlowDiffExecutionException ex = assertThrows(FlowDiffExecutionException.class,
                () -> emit(model, "yaml", null));
        assertTrue(ex.getMessage().contains("Unknown format 'yaml'"), ex.getMessage());
    }

    @Test
    void jsonWithoutOutputFailsQuotingTheCallersOption() throws Exception {
        ReportModel model = model();
        FlowDiffExecutionException ex = assertThrows(FlowDiffExecutionException.class,
                () -> emit(model, "json", null));
        assertTrue(ex.getMessage().contains("requires --output <file>"), ex.getMessage());
    }

    @Test
    void jsonWritesReportToOutputFile() throws Exception {
        File output = dir.resolve("report.json").toFile();
        emit(model(), "json", output);
        String report = Files.readString(output.toPath(), StandardCharsets.UTF_8);
        assertTrue(report.contains("\"schemaVersion\""), report);
    }

    @Test
    void textWritesReportToOutputFile() throws Exception {
        File output = dir.resolve("report.txt").toFile();
        emit(model(), "text", output);
        String report = Files.readString(output.toPath(), StandardCharsets.UTF_8);
        assertTrue(report.contains("properties/k: 1 -> 2"), report);
    }

    @Test
    void textWithoutOutputGoesToStandardOutput() throws Exception {
        ReportModel model = model();
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            emit(model, "text", null);
        } finally {
            System.setOut(original);
        }
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("properties/k: 1 -> 2"),
                buffer.toString(StandardCharsets.UTF_8));
    }
}

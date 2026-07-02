package org.qubership.nifi.maven.flowdiff.mojo;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DiffMojo}: the interim fail-fast for a non-text format, the directory-versus-file mismatch failure,
 * and writing a text report to an output file.
 */
@ExtendWith(MockitoExtension.class)
class DiffMojoTest {

    private static final String FLOW_ONE = """
            {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","properties":{"k":"1"}}]}}""";
    private static final String FLOW_TWO = """
            {"flowContents":{"identifier":"root","name":"Root","componentType":"PROCESS_GROUP","processors":[
              {"identifier":"p1","name":"A","componentType":"PROCESSOR","properties":{"k":"2"}}]}}""";

    @Mock
    private Log log;

    @TempDir
    private Path dir;

    private void setField(final Class<?> clazz, final Object target, final String name, final Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private DiffMojo mojo(final File baseline, final File target, final String format, final File output)
            throws Exception {
        DiffMojo mojo = new DiffMojo();
        mojo.setLog(log);
        setField(AbstractFlowDiffMojo.class, mojo, "basedir", dir.toFile());
        setField(AbstractFlowDiffMojo.class, mojo, "format", format);
        setField(AbstractFlowDiffMojo.class, mojo, "output", output);
        setField(AbstractFlowDiffMojo.class, mojo, "maxValueLength", 200);
        setField(AbstractFlowDiffMojo.class, mojo, "skipMalformed", false);
        setField(DiffMojo.class, mojo, "baseline", baseline);
        setField(DiffMojo.class, mojo, "target", target);
        return mojo;
    }

    private File writeFile(final String name, final String content) throws Exception {
        Path path = dir.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path.toFile();
    }

    @Test
    void jsonFormatWithoutOutputFails() throws Exception {
        File baseline = writeFile("a.json", FLOW_ONE);
        File target = writeFile("b.json", FLOW_TWO);
        DiffMojo mojo = mojo(baseline, target, "json", null);
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("requires -Doutput"), ex.getMessage());
    }

    @Test
    void jsonFormatWritesReportToOutputFile() throws Exception {
        File baseline = writeFile("a.json", FLOW_ONE);
        File target = writeFile("b.json", FLOW_TWO);
        File output = dir.resolve("report.json").toFile();
        mojo(baseline, target, "json", output).execute();
        String report = Files.readString(output.toPath(), StandardCharsets.UTF_8);
        assertTrue(report.contains("\"schemaVersion\""), report);
    }

    @Test
    void directoryVersusFileMismatchFails() throws Exception {
        File baselineDir = Files.createDirectory(dir.resolve("base")).toFile();
        File targetFile = writeFile("b.json", FLOW_TWO);
        DiffMojo mojo = mojo(baselineDir, targetFile, "text", null);
        MojoFailureException ex = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("both be directories or both be single files"), ex.getMessage());
    }

    @Test
    void textFormatWritesReportToOutputFile() throws Exception {
        File baseline = writeFile("a.json", FLOW_ONE);
        File target = writeFile("b.json", FLOW_TWO);
        File output = dir.resolve("report.txt").toFile();
        mojo(baseline, target, "text", output).execute();
        assertTrue(output.exists());
        String report = Files.readString(output.toPath(), StandardCharsets.UTF_8);
        assertTrue(report.contains("properties/k: 1 -> 2"), report);
    }

    @Test
    void missingBaselineFails() throws Exception {
        File target = writeFile("b.json", FLOW_TWO);
        DiffMojo mojo = mojo(dir.resolve("missing.json").toFile(), target, "text", null);
        MojoFailureException ex = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("does not exist"), ex.getMessage());
    }
}

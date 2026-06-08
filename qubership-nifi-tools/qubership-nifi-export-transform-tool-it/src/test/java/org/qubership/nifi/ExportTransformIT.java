package org.qubership.nifi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ExportTransformIT {

    private static final Logger LOG = LoggerFactory.getLogger(ExportTransformIT.class);

    private static final int SUCCESS_EXIT_CODE = 0;
    private static final int MVN_TIMEOUT_SECONDS = 60;
    private static final int DRAIN_THREAD_BUFFER_SIZE = 4096;
    private static final int DRAIN_THREAD_TIMEOUT_MS = 120000;

    /**
     * Full Extract and Build cycle test.
     */
    @Test
    void testExtractThenBuild(@TempDir Path tempDir) throws Exception {
        // --- Setup: copy test resources to temp directory ---
        Path resourcesDir = Path.of("src/test/resources");
        Path configSource = resourcesDir.resolve("conf-for-it.yaml");
        Path flowDirSource = resourcesDir.resolve("nifi/versioned-flow");

        Path configTarget = tempDir.resolve("conf-for-it.yaml");
        Path flowDirTarget = tempDir.resolve("nifi/versioned-flow");

        Files.copy(configSource, configTarget, StandardCopyOption.REPLACE_EXISTING);
        copyDirectory(flowDirSource, flowDirTarget);

        String mvn = resolveMvn();

        // --- Step 1: Extract ---
        List<String> extractCommand = List.of(
                mvn,
                "org.qubership.nifi:qubership-nifi-export-transform-tool:2.5.3-SNAPSHOT:extract",
                "-Dconfig=" + configTarget.toAbsolutePath(),
                "-Dexport-dir=" + flowDirTarget.toAbsolutePath()
        );

        File projectDir = Path.of("").toAbsolutePath().toFile();
        int extractExitCode = runProcess(projectDir, extractCommand);
        assertEquals(SUCCESS_EXIT_CODE, extractExitCode,
                "Extract should complete successfully");

        // --- Verify Extract results ---
        // flowConf_* directories must be created
        try (Stream<Path> stream = Files.walk(flowDirTarget)) {
            boolean hasFlowConfDir = stream
                    .filter(Files::isDirectory)
                    .anyMatch(p -> p.getFileName().toString().startsWith("flowConf_"));
            assertTrue(hasFlowConfDir,
                    "Extract should create at least one flowConf_* directory");
        }

        // Flow JSON files must contain @references (no inline values for configured properties)
        try (Stream<Path> stream = Files.walk(flowDirTarget)) {
            List<Path> flowFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.toString().contains("flowConf_"))
                    .toList();

            assertFalse(flowFiles.isEmpty(),
                    "At least one flow JSON file must exist in the export directory");

            for (Path flowFile : flowFiles) {
                String content = Files.readString(flowFile, StandardCharsets.UTF_8);
                assertTrue(content.contains("\"@"),
                        "Flow file '" + flowFile + "' should contain at least one @reference after Extract");
            }
        }

        // --- Step 2: Build ---
        List<String> buildCommand = List.of(
                mvn,
                "org.qubership.nifi:qubership-nifi-export-transform-tool:2.5.3-SNAPSHOT:build",
                "-Dconfig=" + configTarget.toAbsolutePath(),
                "-Dexport-dir=" + flowDirTarget.toAbsolutePath()
        );

        int buildExitCode = runProcess(projectDir, buildCommand);
        assertEquals(SUCCESS_EXIT_CODE, buildExitCode,
                "Build should complete successfully");

        // --- Verify Build results ---
        // Flow JSON files must no longer contain @references
        try (Stream<Path> stream = Files.walk(flowDirTarget)) {
            List<Path> flowFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.toString().contains("flowConf_"))
                    .toList();

            for (Path flowFile : flowFiles) {
                String content = Files.readString(flowFile, StandardCharsets.UTF_8);
                assertFalse(content.contains("\"@"),
                        "Flow file '" + flowFile + "' should not contain @references after Build");
            }
        }

        // Flow JSON files after Build must match the original (before Extract)
        try (Stream<Path> stream = Files.walk(flowDirSource)) {
            List<Path> originalFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .toList();

            for (Path originalFile : originalFiles) {
                Path relativePath = flowDirSource.relativize(originalFile);
                Path rebuiltFile = flowDirTarget.resolve(relativePath);

                assertTrue(Files.exists(rebuiltFile),
                        "Rebuilt flow file '" + rebuiltFile + "' should exist");

                String originalContent = Files.readString(originalFile, StandardCharsets.UTF_8);
                String rebuiltContent = Files.readString(rebuiltFile, StandardCharsets.UTF_8);

                assertEquals(originalContent, rebuiltContent,
                        "Rebuilt flow file '" + relativePath + "' should match the original");
            }
        }
    }

    // --- Helpers ---

    /**
     * Recursively copies a directory tree from source to target.
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Returns the absolute path to the mvn executable.
     *
     * @return absolute path to mvn/mvn.cmd, or "mvn" as a PATH fallback
     */
    private String resolveMvn() {
        String mavenHome = System.getProperty("maven.home");
        if (mavenHome != null && !mavenHome.isEmpty()) {
            String os = System.getProperty("os.name", "").toLowerCase();
            String exe = os.contains("win") ? "mvn.cmd" : "mvn";
            File candidate = new File(mavenHome, "bin" + File.separator + exe);
            if (candidate.isFile()) {
                return candidate.getAbsolutePath();
            }
        }
        return "mvn"; // PATH fallback
    }

    /**
     * Runs a process, merges stderr into stdout, prints output, returns exit code.
     *
     * @param workDir working directory for the process
     * @param command command and arguments
     * @return process exit code
     * @throws Exception on any I/O or interrupt error
     */
    private int runProcess(File workDir, List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder outputSb = new StringBuilder();
        Thread drainThread = startDrainThread(process.getInputStream(), outputSb);
        boolean finished = process.waitFor(MVN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Process timed out and was killed: " + String.join(" ", command));
        }
        drainThread.join(DRAIN_THREAD_TIMEOUT_MS);
        String output = outputSb.toString();
        int exitCode = process.exitValue();
        System.out.println("=== Command: " + command);
        System.out.println("=== Exit code: " + exitCode);
        System.out.println(output);
        return exitCode;
    }

    /**
     * Drains an InputStream in a background thread to prevent pipe-buffer deadlock
     * when waiting for a subprocess to finish.
     *
     * @param stream   input stream to drain
     * @param outputSb buffer to hold output stream
     * @return the thread that is doing the draining (already started)
     */
    private Thread startDrainThread(InputStream stream, StringBuilder outputSb) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[DRAIN_THREAD_BUFFER_SIZE];
                int n;
                while ((n = stream.read(buf)) != -1) {
                    outputSb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ex) {
                LOG.error("Error reading process output", ex);
            }
        });
        t.start();
        return t;
    }
}

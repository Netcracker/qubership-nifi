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
package org.qubership.nifi;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test that invokes the docs-generator plugin against the whole project
 * and verifies that docs/user-guide.md has no uncommitted changes.
 *
 * <p>Run via: {@code mvn install -P tools-integration-tests -DskipUnitTests=true -Dgpg.skip=true}
 * (the full reactor must be installed first so the plugin is in the local repo).
 */
class DocsGeneratorIT {
    private static final Logger LOG = LoggerFactory.getLogger(DocsGeneratorIT.class);

    private static final int SUCCESS_EXIT_CODE = 0;
    private static final int MVN_TIMEOUT_SECONDS = 60;
    private static final int DRAIN_THREAD_BUFFER_SIZE = 4096;
    private static final int DRAIN_THREAD_TIMEOUT_MS = 120000;

    @Test
    void testDocsGeneratorProducesNoGitChanges() throws Exception {
        String rootDirProp = System.getProperty("project.rootdir");
        if (rootDirProp == null || rootDirProp.isEmpty()) {
            throw new IllegalStateException(
                "System property 'project.rootdir' is not set. "
                + "Run this test via maven-failsafe-plugin "
                + "(mvn verify -P tools-integration-tests -DskipUnitTests=true).");
        }
        File projectRoot = new File(rootDirProp);

        // Step 1: run the docs-generator plugin against the entire project
        int generateExitCode = runProcess(projectRoot, List.of(
            resolveMvn(),
            "--batch-mode",
            "org.qubership.nifi:qubership-nifi-docs-generator:generate"
        ));
        assertEquals(SUCCESS_EXIT_CODE, generateExitCode,
            "docs-generator plugin exited with a non-zero code. Check output above.");

        try (Git git = Git.open(projectRoot)) {
            List<DiffEntry> diffResult = git.diff().setPathFilter(PathFilter.create("docs/user-guide.md")).call();
            assertEquals(0, diffResult.size(),
                "docs/user-guide.md has local changes after running the plugin. "
                + "The committed file is out of date. Re-run "
                + "'mvn org.qubership.nifi:qubership-nifi-docs-generator:generate' "
                + "and commit the result."
            );
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
        //wait for the process to finish, but only for 60 seconds to avoid hanging indefinitely
        boolean finished = process.waitFor(MVN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Process timed out and was killed: " + String.join(" ", command));
        }
        //wait for the thread to finish, but only for 2 minutes to avoid hanging indefinitely
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
     * @param stream input stream to drain
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

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

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test that invokes the docs-generator plugin against the whole project
 * and verifies that docs/user-guide.md has no uncommitted changes.
 *
 * <p>Run via: {@code mvn install -P tools-integration-tests -DskipUnitTests=true -Dgpg.skip=true}
 * (the full reactor must be installed first so the plugin is in the local repo).
 */
class DocsGeneratorIT {

    private static final int SUCCESS_EXIT_CODE = 0;

    @Test
    void testDocsGeneratorProducesNoGitChanges() throws Exception {
        String rootDirProp = System.getProperty("project.rootdir");
        if (rootDirProp == null || rootDirProp.isEmpty()) {
            throw new IllegalStateException(
                "System property 'project.rootdir' is not set. "
                + "Run this test via maven-failsafe-plugin (mvn verify -P tools-integration-tests -DskipUnitTests=true).");
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
        String output = drain(process.getInputStream());
        int exitCode = process.waitFor();
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
     * @return collected output as a UTF-8 string
     * @throws Exception if the draining thread is interrupted
     */
    private String drain(InputStream stream) throws Exception {
        StringBuilder sb = new StringBuilder();
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = stream.read(buf)) != -1) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                // stream closed on process exit — expected
            }
        });
        t.start();
        t.join();
        return sb.toString();
    }
}

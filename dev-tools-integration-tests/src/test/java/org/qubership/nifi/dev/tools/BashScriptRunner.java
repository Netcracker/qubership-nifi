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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a script from {@code dev/} through bash on the host, the way each script's README documents
 * for local use.
 *
 * <p>The bash executable is looked up on the {@code PATH}. Set {@code DEV_SCRIPTS_BASH} to point at
 * a specific bash build. Nothing is downloaded on the fly, and a tool that is missing fails the
 * calling class instead of skipping it: a skipped suite leaves the build green with nothing
 * asserted, which is indistinguishable from a passing run. On a machine that cannot satisfy the
 * prerequisites, leave the integration tests out by not passing {@code -DskipITs=false}.
 *
 * <p>Instances are created through {@link #forScript(String, String...)}, which resolves the script
 * and the tools once and fails with a message naming what is missing.
 */
final class BashScriptRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BashScriptRunner.class);

    /** How far up from a starting directory {@link #locate} looks for the repository root. */
    private static final int MAX_PARENT_LOOKUPS = 5;

    private final String bash;
    private final Path script;

    private BashScriptRunner(final String bashExecutable, final Path scriptPath) {
        this.bash = bashExecutable;
        this.script = scriptPath;
    }

    /**
     * Resolves the bash executable, the script, and the command-line tools the script shells out
     * to.
     *
     * @param relativePath  path of the script from the repository root, e.g.
     *                      {@code "dev/upgrade-advisor/upgradeAdvisor.sh"}
     * @param requiredTools executables the script calls by their bare name, each of which must
     *                      answer {@code --version} on the {@code PATH}
     * @return a runner bound to that script
     */
    static BashScriptRunner forScript(final String relativePath, final String... requiredTools) {
        String bash = System.getenv().getOrDefault("DEV_SCRIPTS_BASH", "bash");
        assertTrue(isAvailable(bash),
            "'" + bash + "' is not available on this machine. Install bash or set DEV_SCRIPTS_BASH to one.");
        for (String tool : requiredTools) {
            assertTrue(isAvailable(tool),
                "'" + tool + "' is not available on the PATH. Install it - " + relativePath
                    + " shells out to the bare '" + tool + "' command.");
        }

        Path script = locate(relativePath);
        assertNotNull(script,
            "Could not locate " + relativePath + "; set the project.rootdir system property");
        assertTrue(canRead(bash, script),
            "'" + bash + "' cannot read " + forBash(script) + "; on Windows the first bash on the PATH is "
                + "often the WSL one, which does not see native paths - set DEV_SCRIPTS_BASH to a "
                + "bash that shares the filesystem, for example C:/Program Files/Git/bin/bash.exe");
        LOG.info("Running {} with {}", script, bash);
        return new BashScriptRunner(bash, script);
    }

    /**
     * Runs the script and waits for it to exit.
     *
     * @param workDir     working directory of the run, where the script's output files land
     * @param environment environment variables to add to the ones this process was started with
     * @param arguments   command-line arguments passed to the script
     * @return the exit code and the merged standard output and standard error
     */
    ScriptRun run(final Path workDir, final Map<String, String> environment, final String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(bash);
        command.add(forBash(script));
        command.addAll(List.of(arguments));

        LOG.info("Running {} in {} with {}", command, workDir, environment.keySet());
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true);
        builder.environment().putAll(environment);
        try {
            Process process = builder.start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            int exitCode = process.waitFor();
            LOG.debug("Script output:\n{}", output);
            return new ScriptRun(exitCode, output, workDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command, e);
        }
    }

    /**
     * Renders a path with forward slashes. On Windows the command line Java builds is re-parsed by
     * the C runtime, which swallows the backslashes of a native path before bash ever sees them.
     *
     * @param path path to render
     * @return the path with forward slashes; unchanged on POSIX systems
     */
    static String forBash(final Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /**
     * Checks that the chosen bash sees the script at the path this class passes to it. A bash from a
     * different filesystem namespace, such as WSL, resolves on the {@code PATH} but fails at run
     * time.
     *
     * @param bash   bash executable to probe
     * @param script script the probe tries to read
     * @return whether the script is readable from inside the chosen bash
     */
    private static boolean canRead(final String bash, final Path script) {
        try {
            Process process = new ProcessBuilder(bash, "-c", "test -r \"$1\"", "bash", forBash(script))
                .redirectErrorStream(true)
                .start();
            try (InputStream stream = process.getInputStream()) {
                stream.readAllBytes();
            }
            return process.waitFor() == 0;
        } catch (IOException e) {
            LOG.info("'{}' cannot read {}: {}", bash, script, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Finds a script starting from the Maven execution root, falling back to walking up from the
     * working directory so the calling class also runs when Maven is invoked inside the module.
     *
     * @param relativePath path of the script from the repository root
     * @return the script path, or {@code null} when it cannot be found
     */
    private static Path locate(final String relativePath) {
        List<String> startingPoints = new ArrayList<>();
        String rootDir = System.getProperty("project.rootdir");
        if (rootDir != null && !rootDir.isEmpty()) {
            startingPoints.add(rootDir);
        }
        startingPoints.add(System.getProperty("user.dir", "."));

        for (String startingPoint : startingPoints) {
            Path candidate = Paths.get(startingPoint).toAbsolutePath();
            for (int level = 0; level <= MAX_PARENT_LOOKUPS && candidate != null; level++) {
                Path found = candidate.resolve(relativePath);
                if (Files.isRegularFile(found)) {
                    return found;
                }
                candidate = candidate.getParent();
            }
        }
        return null;
    }

    private static boolean isAvailable(final String executable) {
        try {
            Process process = new ProcessBuilder(executable, "--version")
                .redirectErrorStream(true)
                .start();
            try (InputStream stream = process.getInputStream()) {
                stream.readAllBytes();
            }
            return process.waitFor() == 0;
        } catch (IOException e) {
            LOG.info("'{}' is not usable: {}", executable, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

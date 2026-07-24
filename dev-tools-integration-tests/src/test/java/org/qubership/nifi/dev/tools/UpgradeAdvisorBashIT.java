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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
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

/**
 * Runs {@code upgradeAdvisor.sh} the way {@code dev/upgrade-advisor/README.md} documents for local
 * use: bash on the host, with {@code jq} on the {@code PATH}.
 *
 * <p>Both binaries are looked up on the {@code PATH} by default. Set {@code UPGRADE_ADVISOR_BASH} or
 * {@code UPGRADE_ADVISOR_JQ} to point at a specific build. When either is missing the whole class is
 * skipped rather than failing - nothing is downloaded on the fly.
 */
final class UpgradeAdvisorBashIT extends AbstractUpgradeAdvisorTest {

    private static final Logger LOG = LoggerFactory.getLogger(UpgradeAdvisorBashIT.class);

    private static final String SCRIPT_RELATIVE_PATH = "dev/upgrade-advisor/upgradeAdvisor.sh";
    private static final int MAX_PARENT_LOOKUPS = 5;

    private static String bash;
    private static Path script;

    @BeforeAll
    static void resolveScriptAndTools() {
        bash = System.getenv().getOrDefault("UPGRADE_ADVISOR_BASH", "bash");
        String jq = System.getenv().getOrDefault("UPGRADE_ADVISOR_JQ", "jq");
        Assumptions.assumeTrue(isAvailable(bash), "'" + bash + "' is not available on this machine");
        Assumptions.assumeTrue(isAvailable(jq), "'" + jq + "' is not available on this machine");

        script = locateScript();
        Assumptions.assumeTrue(script != null,
            "Could not locate " + SCRIPT_RELATIVE_PATH + "; set the project.rootdir system property");
        Assumptions.assumeTrue(canReadScript(),
            "'" + bash + "' cannot read " + forBash(script) + "; on Windows the first bash on the PATH is "
                + "often the WSL one, which does not see native paths - set UPGRADE_ADVISOR_BASH to a "
                + "bash that shares the filesystem, for example C:/Program Files/Git/bin/bash.exe");
        LOG.info("Running the upgrade advisor from {} with {}", script, bash);
    }

    /**
     * Checks that the chosen bash sees the script at the path this class will pass to it. A bash from
     * a different filesystem namespace, such as WSL, resolves on the PATH but fails at run time.
     *
     * @return whether the script is readable from inside the chosen bash
     */
    private static boolean canReadScript() {
        try {
            Process process = new ProcessBuilder(bash, "-c", "test -r \"$1\"", "bash", forBash(script))
                .redirectErrorStream(true)
                .start();
            try (InputStream stream = process.getInputStream()) {
                stream.readAllBytes();
            }
            return process.waitFor() == 0;
        } catch (IOException e) {
            LOG.info("'{}' cannot read the advisor script: {}", bash, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    protected AdvisorRunResult runAdvisor(final Path workDir, final Path exportsDir, final String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(bash);
        command.add(forBash(script));
        command.add(forBash(exportsDir));
        command.addAll(List.of(arguments));
        return execute(workDir, command);
    }

    @Override
    protected AdvisorRunResult runAdvisorWithoutArguments(final Path workDir) {
        return execute(workDir, List.of(bash, forBash(script)));
    }

    /**
     * Renders a path with forward slashes. On Windows the command line Java builds is re-parsed by
     * the C runtime, which swallows the backslashes of a native path before bash ever sees them.
     *
     * @param path path to render
     * @return the path with forward slashes; unchanged on POSIX systems
     */
    private static String forBash(final Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static AdvisorRunResult execute(final Path workDir, final List<String> command) {
        LOG.info("Running {} in {}", command, workDir);
        try {
            Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            int exitCode = process.waitFor();
            LOG.debug("Advisor output:\n{}", output);
            return new AdvisorRunResult(exitCode, output, workDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command, e);
        }
    }

    /**
     * Finds the advisor script starting from the Maven execution root, falling back to walking up
     * from the working directory so the class also runs when Maven is invoked inside the module.
     *
     * @return the script path, or {@code null} when it cannot be found
     */
    private static Path locateScript() {
        List<String> startingPoints = new ArrayList<>();
        String rootDir = System.getProperty("project.rootdir");
        if (rootDir != null && !rootDir.isEmpty()) {
            startingPoints.add(rootDir);
        }
        startingPoints.add(System.getProperty("user.dir", "."));

        for (String startingPoint : startingPoints) {
            Path candidate = Paths.get(startingPoint).toAbsolutePath();
            for (int level = 0; level <= MAX_PARENT_LOOKUPS && candidate != null; level++) {
                Path found = candidate.resolve(SCRIPT_RELATIVE_PATH);
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

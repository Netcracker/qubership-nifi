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

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Runs {@code upgradeAdvisor.sh} inside the Alpine-based image built from
 * {@code dev/upgrade-advisor-autotest/Dockerfile}, the way {@code dev/upgrade-advisor/README.md}
 * documents for containerized use.
 *
 * <p>The image is not built by Maven; build it first with
 * {@code docker build -t qubership-nifi-upgrade-advisor:test . -f dev/upgrade-advisor-autotest/Dockerfile}
 * from the repository root, or point {@code -Dupgrade.advisor.image=...} at an equivalent image. The
 * class is skipped when no Docker daemon is reachable.
 *
 * <p>The exports are bound read-only, and the working directory is bound separately: the advisor
 * writes its report and temporary files into the current directory, and the reporting-task check
 * greps every file under the exports path, so keeping the two apart avoids false positives.
 */
final class UpgradeAdvisorDockerIT extends AbstractUpgradeAdvisorTest {

    private static final Logger LOG = LoggerFactory.getLogger(UpgradeAdvisorDockerIT.class);

    private static final String DEFAULT_IMAGE = "qubership-nifi-upgrade-advisor:test";
    private static final String CONTAINER_EXPORTS_DIR = "/data/export";
    private static final String CONTAINER_WORK_DIR = "/work";
    private static final int TIMEOUT_MINUTES = 2;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
            "No Docker daemon is reachable on this machine");
    }

    @Override
    protected AdvisorRunResult runAdvisor(final Path workDir, final Path exportsDir, final String... arguments) {
        String[] command = new String[arguments.length + 1];
        command[0] = CONTAINER_EXPORTS_DIR;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        return execute(workDir, exportsDir, command);
    }

    @Override
    protected AdvisorRunResult runAdvisorWithoutArguments(final Path workDir) {
        return execute(workDir, null, new String[0]);
    }

    private static AdvisorRunResult execute(final Path workDir, final Path exportsDir, final String[] command) {
        String image = System.getProperty("upgrade.advisor.image", DEFAULT_IMAGE);
        ExitCodeCheckStrategy startupCheck = new ExitCodeCheckStrategy();
        startupCheck.withTimeout(Duration.ofMinutes(TIMEOUT_MINUTES));

        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))) {
            container.withFileSystemBind(workDir.toAbsolutePath().toString(),
                    CONTAINER_WORK_DIR, BindMode.READ_WRITE)
                .withWorkingDirectory(CONTAINER_WORK_DIR)
                .withStartupCheckStrategy(startupCheck)
                .withCommand(command);
            if (exportsDir != null) {
                container.withFileSystemBind(exportsDir.toAbsolutePath().toString(),
                    CONTAINER_EXPORTS_DIR, BindMode.READ_ONLY);
            }

            container.start();
            String logs = container.getLogs();
            LOG.debug("Advisor container output:\n{}", logs);
            return new AdvisorRunResult(startupCheck.exitCode(), logs, workDir);
        }
    }

    /**
     * Waits for the one-shot container to exit and records its exit code.
     *
     * <p>{@code OneShotStartupCheckStrategy} treats a non-zero exit as a startup failure and throws,
     * which would hide the exit codes this suite asserts on. This strategy accepts any exit instead.
     */
    private static final class ExitCodeCheckStrategy extends StartupCheckStrategy {

        private volatile int exitCode = -1;

        @Override
        public StartupStatus checkStartupState(final DockerClient dockerClient, final String containerId) {
            InspectContainerResponse.ContainerState state = getCurrentState(dockerClient, containerId);
            if (Boolean.TRUE.equals(state.getRunning())) {
                return StartupStatus.NOT_YET_KNOWN;
            }
            Long code = state.getExitCodeLong();
            exitCode = code == null ? -1 : code.intValue();
            return StartupStatus.SUCCESSFUL;
        }

        int exitCode() {
            return exitCode;
        }
    }
}

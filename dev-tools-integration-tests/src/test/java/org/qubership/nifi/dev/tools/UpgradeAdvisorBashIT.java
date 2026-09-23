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

import org.junit.jupiter.api.BeforeAll;

import java.nio.file.Path;
import java.util.Map;

/**
 * Runs {@code upgradeAdvisor.sh} the way {@code dev/upgrade-advisor/README.md} documents for local
 * use: bash on the host, with {@code jq} on the {@code PATH}.
 *
 * <p>{@link BashScriptRunner} resolves both binaries and states what to set when one is missing.
 */
final class UpgradeAdvisorBashIT extends AbstractUpgradeAdvisorTest {

    private static final String SCRIPT_RELATIVE_PATH = "dev/upgrade-advisor/upgradeAdvisor.sh";

    private static BashScriptRunner runner;

    @BeforeAll
    static void resolveScriptAndTools() {
        runner = BashScriptRunner.forScript(SCRIPT_RELATIVE_PATH, "jq");
    }

    @Override
    protected AdvisorRunResult runAdvisor(final Path workDir, final Path exportsDir, final String... arguments) {
        String[] command = new String[arguments.length + 1];
        command[0] = BashScriptRunner.forBash(exportsDir);
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        return toAdvisorResult(runner.run(workDir, Map.of(), command));
    }

    @Override
    protected AdvisorRunResult runAdvisorWithoutArguments(final Path workDir) {
        return toAdvisorResult(runner.run(workDir, Map.of()));
    }

    private static AdvisorRunResult toAdvisorResult(final ScriptRun run) {
        return new AdvisorRunResult(run.exitCode(), run.output(), run.workDir());
    }
}

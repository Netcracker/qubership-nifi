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

package org.qubership.nifi.tools.kb.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildCommandTest {

    private static final String URL = "https://nifi.example.com/nifi";

    private BuildCommand parse(final String... args) {
        return TestCommands.populate(args);
    }

    @Test
    void parsesEveryOption() {
        final BuildCommand command = parse("--nifi-url", URL, "--auth", "certificate",
                "--certificate-file", "client.p12", "--ca-file", "ca.pem",
                "--output-dir", "kb", "--skip-guides");

        assertThat(command.nifiUrl()).isEqualTo(URL);
        assertThat(command.auth()).isEqualTo(AuthMode.CERTIFICATE);
        assertThat(command.certificateFile()).isEqualTo(Paths.get("client.p12"));
        assertThat(command.caFile()).isEqualTo(Paths.get("ca.pem"));
        assertThat(command.outputDir()).isEqualTo(Paths.get("kb"));
        assertThat(command.skipGuides()).isTrue();
    }

    @Test
    void defaultsOptionalOptions() {
        final BuildCommand command = parse("--nifi-url", URL, "--auth", "token", "--output-dir", "kb");

        assertThat(command.auth()).isEqualTo(AuthMode.TOKEN);
        assertThat(command.certificateFile()).isNull();
        assertThat(command.caFile()).isNull();
        assertThat(command.skipGuides()).isFalse();
    }

    @Test
    void parsesCookieAuthMode() {
        final BuildCommand command = parse("--nifi-url", URL, "--auth", "cookie", "--output-dir", "kb");

        assertThat(command.auth()).isEqualTo(AuthMode.COOKIE);
    }

    @Test
    void rejectsUnknownOption() {
        assertThatThrownBy(() -> parse("--nifi-url", URL, "--auth", "token", "--output-dir", "kb", "--nope"))
                .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    @Test
    void rejectsPositionalArgument() {
        assertThatThrownBy(() -> parse("--nifi-url", URL, "--auth", "token", "--output-dir", "kb", "extra"))
                .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    @Test
    void rejectsMissingRequiredOption() {
        assertThatThrownBy(() -> parse("--auth", "token", "--output-dir", "kb"))
                .isInstanceOf(CommandLine.MissingParameterException.class)
                .hasMessageContaining("--nifi-url");
    }

    @Test
    void rejectsMissingOptionValue() {
        assertThatThrownBy(() -> parse("--nifi-url", URL, "--auth", "token", "--output-dir"))
                .isInstanceOf(CommandLine.MissingParameterException.class);
    }

    @Test
    void rejectsUnknownAuthMode() {
        assertThatThrownBy(() -> parse("--nifi-url", URL, "--auth", "basic", "--output-dir", "kb"))
                .isInstanceOf(CommandLine.ParameterException.class)
                .hasMessageContaining("'token', 'cookie', or 'certificate'");
    }

    @Test
    void reportsUsageExitCodeForInvalidInput() {
        final CommandLine commandLine = new CommandLine(TestCommands.newCommand());
        assertThat(commandLine.getCommandSpec().exitCodeOnInvalidInput()).isEqualTo(ExitCodes.USAGE);
    }

    @Test
    void rejectsRepeatedOption() {
        assertThatThrownBy(() -> parse("--nifi-url", URL, "--auth", "token",
                "--output-dir", "first", "--output-dir", "second"))
                .isInstanceOf(CommandLine.OverwrittenOptionException.class)
                .hasMessageContaining("--output-dir");
    }
}

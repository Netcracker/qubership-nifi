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
import org.qubership.nifi.tools.kb.output.OutputException;
import org.qubership.nifi.tools.nifi.common.http.NiFiApiException;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails when a build failure reaches the error stream without a usable reason or with the wrong
 * exit code.
 *
 * <p>The reported line must always name the reason and state that the previous output survived. A
 * failure carrying no message must fall back to its type rather than printing {@code null}, and a
 * NiFi API failure must name the request that failed: a build walks thousands of endpoints, so a
 * status code on its own leaves the reader with nothing to investigate.</p>
 */
class ExitCodeExceptionHandlerTest {

    private static final String UNCHANGED = "The previous output, if any, was left unchanged.";
    private static final int HTTP_SERVER_ERROR = 500;

    @Test
    void reportsTheFailureMessageAndItsExitCode() {
        final StringWriter err = new StringWriter();

        final int exitCode = handle(new OutputException("Staging directory could not be replaced."), err);

        assertThat(err.toString()).contains("Staging directory could not be replaced.").contains(UNCHANGED);
        assertThat(exitCode).isEqualTo(ExitCodes.OUTPUT);
    }

    @Test
    void namesTheFailedRequestAndResponseWhenTheNiFiApiFailed() {
        final StringWriter err = new StringWriter();
        final String uri = "https://nifi.example.com/nifi-api/flow/processor-definition/g/a/2.5.0/org.P";

        final int exitCode = handle(new NiFiApiException("GET", uri, HTTP_SERVER_ERROR,
                "{\"message\":\"Processor definition unavailable\"}",
                "GET request did not succeed (status 500)"), err);

        assertThat(err.toString())
                .contains("status 500")
                .contains(uri)
                .contains("Processor definition unavailable")
                .contains(UNCHANGED);
        assertThat(exitCode).isEqualTo(ExitCodes.COLLECTION);
    }

    @Test
    void fallsBackToTheFailureTypeWhenThereIsNoMessage() {
        final StringWriter err = new StringWriter();

        final int exitCode = handle(new NullPointerException(), err);

        assertThat(err.toString())
                .doesNotStartWith("null")
                .contains(NullPointerException.class.getName())
                .contains(UNCHANGED);
        assertThat(exitCode).isEqualTo(ExitCodes.COLLECTION);
    }

    private static int handle(final Exception failure, final StringWriter err) {
        final CommandLine commandLine = new CommandLine(TestCommands.newCommand());
        commandLine.setErr(new PrintWriter(err, true));
        return new ExitCodeExceptionHandler().handleExecutionException(failure, commandLine, null);
    }
}
